package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.watchparty.ChatSendResult
import com.nuvio.app.features.watchparty.WatchPartyChat
import com.nuvio.app.features.watchparty.WatchPartyChatMessage
import com.nuvio.app.features.watchparty.WatchPartyChatState
import com.nuvio.app.features.watchparty.WatchPartyRoomController
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.watch_party_chat
import nuvio.composeapp.generated.resources.watch_party_chat_empty
import nuvio.composeapp.generated.resources.watch_party_chat_placeholder
import nuvio.composeapp.generated.resources.watch_party_chat_send
import nuvio.composeapp.generated.resources.watch_party_chat_too_fast
import nuvio.composeapp.generated.resources.watch_party_chat_you
import nuvio.composeapp.generated.resources.watch_party_title
import org.jetbrains.compose.resources.stringResource

private const val CHAT_POPUP_DURATION_MS = 4_000L

/** Null hides the chat button, i.e. outside a room. */
@Composable
internal fun PlayerScreenRuntime.watchPartyChatClickHandler(): (() -> Unit)? {
    val chat by WatchPartyRoomController.chat.collectAsStateWithLifecycle()
    return if (chat != null) {
        { showWatchPartyChat = true }
    } else {
        null
    }
}

@Composable
internal fun watchPartyChatUnreadCount(): Int {
    val chat by WatchPartyRoomController.chat.collectAsStateWithLifecycle()
    val state = chat?.state?.collectAsStateWithLifecycle()?.value ?: return 0
    return state.unreadCount
}

/** How many people are in the room, shown on the Watch Party button; 0 outside a room. */
@Composable
internal fun watchPartyMemberCount(): Int {
    val status by WatchPartyRoomController.status.collectAsStateWithLifecycle()
    return if (status.code != null) status.members.size else 0
}

@Composable
internal fun WatchPartyAction(memberCount: Int, onClick: () -> Unit) {
    val description = stringResource(Res.string.watch_party_title)
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        BadgedBox(
            badge = {
                if (memberCount > 0) {
                    // White, so it reads differently from the red unread-chat badge.
                    Badge(containerColor = Color.White, contentColor = Color.Black) { Text(memberCount.toString()) }
                }
            },
        ) {
            Icon(Icons.Rounded.Groups, description, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun WatchPartyChatAction(unreadCount: Int, onClick: () -> Unit) {
    val description = stringResource(Res.string.watch_party_chat)
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        BadgedBox(
            badge = {
                if (unreadCount > 0) Badge { Text(if (unreadCount > 9) "9+" else unreadCount.toString()) }
            },
        ) {
            Icon(Icons.AutoMirrored.Rounded.Chat, description, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

/** The chat panel and the popup for new messages. Drawn above the player controls. */
@Composable
internal fun PlayerScreenRuntime.RenderWatchPartyChat() {
    val chat = WatchPartyRoomController.chat.collectAsStateWithLifecycle().value
    if (chat == null) {
        LaunchedEffect(Unit) { showWatchPartyChat = false }
        return
    }
    val state by chat.state.collectAsStateWithLifecycle()

    LaunchedEffect(chat, showWatchPartyChat) { chat.setOpen(showWatchPartyChat) }

    ChatPopup(chat = chat, chatOpen = showWatchPartyChat, onOpen = { showWatchPartyChat = true })

    PlayerSidePanel(
        visible = showWatchPartyChat,
        onDismiss = { showWatchPartyChat = false },
        width = 400.dp,
        // See-through, so the video stays watchable while chatting.
        scrimAlpha = 0.1f,
        panelAlpha = 0.72f,
    ) {
        ChatPanelContent(chat = chat, state = state, onClose = { showWatchPartyChat = false })
    }
}

@Composable
private fun ChatPanelContent(chat: WatchPartyChat, state: WatchPartyChatState, onClose: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    var input by remember { mutableStateOf("") }
    var tooFast by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    fun sendInput() {
        when (chat.send(input)) {
            ChatSendResult.Sent -> {
                input = ""
                tooFast = false
            }
            ChatSendResult.TooFast -> tooFast = true
            ChatSendResult.Empty -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp),
    ) {
        PlayerPanelHeader(title = stringResource(Res.string.watch_party_chat)) {
            PlayerDialogButton(label = stringResource(Res.string.action_close), onClick = onClose)
        }

        Spacer(Modifier.height(16.dp))

        if (state.messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.watch_party_chat_empty),
                    color = tokens.colors.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.messages, key = { it.id }) { message -> ChatMessageRow(message) }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (tooFast) {
            Text(
                text = stringResource(Res.string.watch_party_chat_too_fast),
                color = tokens.colors.danger,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
        }

        NuvioInputField(
            value = input,
            onValueChange = {
                input = it.take(WatchPartyChat.MAX_TEXT_LENGTH)
                tooFast = false
            },
            placeholder = stringResource(Res.string.watch_party_chat_placeholder),
            trailingContent = {
                IconButton(onClick = ::sendInput) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        stringResource(Res.string.watch_party_chat_send),
                        tint = tokens.colors.accent,
                    )
                }
            },
        )
    }
}

@Composable
private fun ChatMessageRow(message: WatchPartyChatMessage) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (message.isOwn) stringResource(Res.string.watch_party_chat_you) else message.name,
            color = if (message.isOwn) tokens.colors.accent else tokens.colors.textSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = message.text,
            color = tokens.colors.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A short popup in the corner for messages that arrive while the chat is closed; tap to open the chat. */
@Composable
private fun ChatPopup(chat: WatchPartyChat, chatOpen: Boolean, onOpen: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    var latest by remember(chat) { mutableStateOf<WatchPartyChatMessage?>(null) }

    LaunchedEffect(chat) { chat.incoming.collect { latest = it } }
    LaunchedEffect(latest) {
        if (latest != null) {
            delay(CHAT_POPUP_DURATION_MS)
            latest = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = latest != null && !chatOpen,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeContent)
                .padding(16.dp),
        ) {
            val message = latest ?: return@AnimatedVisibility
            Row(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.colors.surfaceElevated.copy(alpha = 0.92f))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Chat, null, tint = tokens.colors.accent, modifier = Modifier.size(18.dp))
                Text(
                    text = "${message.name}: ${message.text}",
                    color = tokens.colors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
