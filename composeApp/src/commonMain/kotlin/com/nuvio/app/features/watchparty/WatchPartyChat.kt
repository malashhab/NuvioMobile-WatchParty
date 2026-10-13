package com.nuvio.app.features.watchparty

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WatchPartyChatMessage(
    val id: String,
    val senderId: String,
    val name: String,
    val text: String,
    val isOwn: Boolean,
)

data class WatchPartyChatState(
    val messages: List<WatchPartyChatMessage> = emptyList(),
    val unreadCount: Int = 0,
)

enum class ChatSendResult {
    Sent,
    Empty,
    TooFast,
}

/**
 * The room's chat. History lives only in memory on each phone, so it ends with the room. Text is
 * cleaned and capped, and each sender is rate limited so nobody can flood the room.
 */
@OptIn(ExperimentalUuidApi::class)
class WatchPartyChat(
    private val localMemberId: String,
    private val localName: () -> String,
    private val send: (WatchPartyMessage) -> Unit,
    private val nowMs: () -> Long,
    private val newId: () -> String = { Uuid.random().toString() },
) {
    private val _state = MutableStateFlow(WatchPartyChatState())
    val state: StateFlow<WatchPartyChatState> = _state.asStateFlow()

    // Messages that arrived while the chat was closed, for a short popup.
    private val _incoming = MutableSharedFlow<WatchPartyChatMessage>(extraBufferCapacity = 16)
    val incoming: SharedFlow<WatchPartyChatMessage> = _incoming.asSharedFlow()

    private var isOpen = false
    private val ownSendTimes = ArrayDeque<Long>()
    private val receiveTimesBySender = mutableMapOf<String, ArrayDeque<Long>>()
    private val seenIds = ArrayDeque<String>()

    fun send(text: String): ChatSendResult {
        val clean = cleanText(text) ?: return ChatSendResult.Empty
        if (!withinRate(ownSendTimes)) return ChatSendResult.TooFast
        val message = WatchPartyMessage.Chat(
            senderId = localMemberId,
            id = newId(),
            name = cleanName(localName()),
            text = clean,
        )
        send(message)
        append(message.toChatMessage(isOwn = true), countAsUnread = false)
        return ChatSendResult.Sent
    }

    fun onMessage(message: WatchPartyMessage.Chat) {
        if (message.senderId == localMemberId) return
        if (message.id in seenIds) return
        val clean = cleanText(message.text) ?: return
        if (!withinRate(receiveTimesBySender.getOrPut(message.senderId) { ArrayDeque() })) return
        rememberSeen(message.id)
        val chatMessage = message.copy(name = cleanName(message.name), text = clean).toChatMessage(isOwn = false)
        append(chatMessage, countAsUnread = !isOpen)
        if (!isOpen) _incoming.tryEmit(chatMessage)
    }

    /** Opening the chat marks everything as read. */
    fun setOpen(open: Boolean) {
        isOpen = open
        if (open) _state.update { it.copy(unreadCount = 0) }
    }

    private fun append(message: WatchPartyChatMessage, countAsUnread: Boolean) {
        _state.update { current ->
            current.copy(
                messages = (current.messages + message).takeLast(MAX_HISTORY),
                unreadCount = if (countAsUnread) current.unreadCount + 1 else current.unreadCount,
            )
        }
    }

    private fun withinRate(times: ArrayDeque<Long>): Boolean {
        val now = nowMs()
        while (times.isNotEmpty() && now - times.first() >= RATE_WINDOW_MS) times.removeFirst()
        if (times.size >= RATE_MAX_MESSAGES) return false
        times.addLast(now)
        return true
    }

    private fun rememberSeen(id: String) {
        seenIds.addLast(id)
        if (seenIds.size > MAX_HISTORY) seenIds.removeFirst()
    }

    private fun WatchPartyMessage.Chat.toChatMessage(isOwn: Boolean) =
        WatchPartyChatMessage(id = id, senderId = senderId, name = name, text = text, isOwn = isOwn)

    companion object {
        const val MAX_TEXT_LENGTH = 500
        const val MAX_HISTORY = 100
        private const val MAX_NAME_LENGTH = 40
        private const val RATE_MAX_MESSAGES = 5
        private const val RATE_WINDOW_MS = 5_000L
        private const val FALLBACK_NAME = "Guest"

        /** One line of visible text: control characters removed, whitespace collapsed, length capped. */
        internal fun cleanText(text: String): String? =
            text.map { if (it.isWhitespace()) ' ' else it }
                .filterNot { it.isISOControl() }
                .joinToString("")
                .replace(Regex(" {2,}"), " ")
                .trim()
                .take(MAX_TEXT_LENGTH)
                .takeIf { it.isNotEmpty() }

        private fun cleanName(name: String): String =
            cleanText(name)?.take(MAX_NAME_LENGTH) ?: FALLBACK_NAME
    }
}
