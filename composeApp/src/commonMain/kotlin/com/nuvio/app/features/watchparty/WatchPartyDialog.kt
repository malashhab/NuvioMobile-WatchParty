package com.nuvio.app.features.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_close
import nuvio.composeapp.generated.resources.watch_party_code_placeholder
import nuvio.composeapp.generated.resources.watch_party_connect_failed
import nuvio.composeapp.generated.resources.watch_party_create
import nuvio.composeapp.generated.resources.watch_party_guest_hint
import nuvio.composeapp.generated.resources.watch_party_guest_message
import nuvio.composeapp.generated.resources.watch_party_host_message
import nuvio.composeapp.generated.resources.watch_party_intro
import nuvio.composeapp.generated.resources.watch_party_invalid_code
import nuvio.composeapp.generated.resources.watch_party_join
import nuvio.composeapp.generated.resources.watch_party_leave
import nuvio.composeapp.generated.resources.watch_party_status_connected
import nuvio.composeapp.generated.resources.watch_party_status_connecting
import nuvio.composeapp.generated.resources.watch_party_status_reconnecting
import nuvio.composeapp.generated.resources.watch_party_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WatchPartyDialog(onDismiss: () -> Unit) {
    val status by WatchPartyRoomController.status.collectAsStateWithLifecycle()
    val code = status.code

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.watch_party_title),
        message = if (code == null) stringResource(Res.string.watch_party_intro) else null,
    ) {
        if (code == null) {
            JoinOrCreateContent(onDismiss = onDismiss)
        } else {
            RoomContent(status = status, code = code, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun JoinOrCreateContent(onDismiss: () -> Unit) {
    var codeInput by remember { mutableStateOf("") }
    var invalidCode by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        NuvioInputField(
            value = codeInput,
            onValueChange = {
                codeInput = it.take(WatchPartyRoomCode.LENGTH + 1).uppercase()
                invalidCode = false
            },
            placeholder = stringResource(Res.string.watch_party_code_placeholder),
        )
        if (invalidCode) {
            Text(
                text = stringResource(Res.string.watch_party_invalid_code),
                color = tokens.colors.danger,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    DialogButtons {
        DialogButton(
            text = stringResource(Res.string.watch_party_join),
            onClick = { invalidCode = !WatchPartyRoomController.joinRoom(codeInput) },
        )
        DialogButton(
            text = stringResource(Res.string.watch_party_create),
            onClick = WatchPartyRoomController::createRoom,
            style = DialogButtonStyle.Primary,
        )
    }
    DialogButtons {
        DialogButton(text = stringResource(Res.string.watch_party_close), onClick = onDismiss)
    }
}

@Composable
private fun RoomContent(status: WatchPartyStatus, code: String, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        Text(
            text = stringResource(
                if (status.isHost) Res.string.watch_party_host_message else Res.string.watch_party_guest_message,
            ),
            color = tokens.colors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = WatchPartyRoomCode.format(code),
            color = tokens.colors.textPrimary,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
        )
        Text(
            text = stringResource(
                when (status.connection) {
                    WatchPartyConnection.Connected -> Res.string.watch_party_status_connected
                    WatchPartyConnection.Connecting -> Res.string.watch_party_status_connecting
                    WatchPartyConnection.Disconnected -> Res.string.watch_party_status_reconnecting
                },
            ),
            color = tokens.colors.textMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (status.connectFailed) {
            Text(
                text = stringResource(Res.string.watch_party_connect_failed),
                color = tokens.colors.danger,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!status.isHost) {
            Text(
                text = stringResource(Res.string.watch_party_guest_hint),
                color = tokens.colors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    DialogButtons {
        DialogButton(
            text = stringResource(Res.string.watch_party_leave),
            onClick = WatchPartyRoomController::leave,
            style = DialogButtonStyle.Destructive,
        )
        DialogButton(text = stringResource(Res.string.watch_party_close), onClick = onDismiss)
    }
}
