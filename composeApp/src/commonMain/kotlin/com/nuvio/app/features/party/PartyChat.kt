package com.nuvio.app.features.party

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class PartyChatMessage(
    val id: String,
    val memberId: String,
    val name: String,
    val text: String,
    val isOwn: Boolean,
)

data class PartyChatState(
    val messages: List<PartyChatMessage> = emptyList(),
    val unreadCount: Int = 0,
)

enum class PartyChatSendResult {
    Sent,
    Empty,
    TooFast,
}

/**
 * The party's chat, sent as "chat" broadcasts on the party channel. History lives only in memory
 * on each device, so it ends with the party. Text is cleaned and capped, and every sender is rate
 * limited so nobody can flood the party. Names come from the party's member list when the sender
 * is in it, so a message can't claim to be from someone else's name.
 */
@OptIn(ExperimentalUuidApi::class)
class PartyChat(
    private val localMemberId: String,
    private val nameOf: (memberId: String) -> String?,
    private val send: (JsonObject) -> Unit,
    private val nowMs: () -> Long,
    private val newId: () -> String = { Uuid.random().toString() },
) {
    private val _state = MutableStateFlow(PartyChatState())
    val state: StateFlow<PartyChatState> = _state.asStateFlow()

    // Messages that arrive while the chat is closed, for a short popup.
    private val _incoming = MutableSharedFlow<PartyChatMessage>(extraBufferCapacity = 16)
    val incoming: SharedFlow<PartyChatMessage> = _incoming.asSharedFlow()

    private var isOpen = false
    private val ownSendTimes = ArrayDeque<Long>()
    private val receiveTimesBySender = mutableMapOf<String, ArrayDeque<Long>>()
    private val seenIds = ArrayDeque<String>()

    fun send(text: String): PartyChatSendResult {
        val clean = cleanText(text) ?: return PartyChatSendResult.Empty
        if (!withinRate(ownSendTimes)) return PartyChatSendResult.TooFast
        val id = newId()
        val name = cleanName(nameOf(localMemberId))
        send(
            buildJsonObject {
                put("id", id)
                put("member_id", localMemberId)
                put("name", name)
                put("text", clean)
            },
        )
        append(PartyChatMessage(id, localMemberId, name, clean, isOwn = true), countAsUnread = false)
        return PartyChatSendResult.Sent
    }

    fun onMessage(payload: JsonObject) {
        val memberId = payload.string("member_id") ?: return
        val id = payload.string("id") ?: return
        if (memberId == localMemberId || id in seenIds) return
        val clean = cleanText(payload.string("text").orEmpty()) ?: return
        if (!withinRate(receiveTimesBySender.getOrPut(memberId) { ArrayDeque() })) return
        rememberSeen(id)
        val name = cleanName(nameOf(memberId) ?: payload.string("name"))
        val message = PartyChatMessage(id, memberId, name, clean, isOwn = false)
        append(message, countAsUnread = !isOpen)
        if (!isOpen) _incoming.tryEmit(message)
    }

    /** Opening the chat marks everything as read. */
    fun setOpen(open: Boolean) {
        isOpen = open
        if (open) _state.update { it.copy(unreadCount = 0) }
    }

    private fun append(message: PartyChatMessage, countAsUnread: Boolean) {
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

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

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

        private fun cleanName(name: String?): String =
            name?.let(::cleanText)?.take(MAX_NAME_LENGTH) ?: FALLBACK_NAME
    }
}
