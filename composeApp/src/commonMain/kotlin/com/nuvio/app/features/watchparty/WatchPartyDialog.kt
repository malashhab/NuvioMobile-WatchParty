package com.nuvio.app.features.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import nuvio.composeapp.generated.resources.*
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
                if (status.isOwner) Res.string.watch_party_host_message else Res.string.watch_party_guest_message,
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
    }

    status.pendingRequests.forEach { member ->
        ControlRequestRow(member)
    }

    MemberList(status)

    ControlSection(status)

    DialogButtons {
        DialogButton(
            text = stringResource(Res.string.watch_party_leave),
            onClick = WatchPartyRoomController::leave,
            style = DialogButtonStyle.Destructive,
        )
        DialogButton(text = stringResource(Res.string.watch_party_close), onClick = onDismiss)
    }
}

@Composable
private fun ControlRequestRow(member: WatchPartyMember) {
    val tokens = MaterialTheme.nuvio
    Column {
        Text(
            text = stringResource(Res.string.watch_party_request_from, member.name),
            color = tokens.colors.textPrimary,
            style = MaterialTheme.typography.titleSmall,
        )
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.watch_party_deny),
                onClick = { WatchPartyRoomController.denyControl(member.memberId) },
            )
            DialogButton(
                text = stringResource(Res.string.watch_party_allow),
                onClick = { WatchPartyRoomController.grantControl(member.memberId) },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

@Composable
private fun MemberList(status: WatchPartyStatus) {
    if (status.members.isEmpty()) return
    val tokens = MaterialTheme.nuvio
    val ownerLabel = stringResource(Res.string.watch_party_owner)
    val controllingLabel = stringResource(Res.string.watch_party_controlling)

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
        Text(
            text = stringResource(Res.string.watch_party_members),
            color = tokens.colors.textSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
        status.members.forEach { member ->
            val tags = listOfNotNull(
                ownerLabel.takeIf { member.memberId == status.ownerMemberId },
                controllingLabel.takeIf { member.memberId == status.controllerId },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (member.memberId == status.localMemberId) {
                        stringResource(Res.string.watch_party_member_you, member.name)
                    } else {
                        member.name
                    },
                    color = tokens.colors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (tags.isNotEmpty()) {
                    Text(
                        text = tags.joinToString(" · "),
                        color = tokens.colors.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlSection(status: WatchPartyStatus) {
    val tokens = MaterialTheme.nuvio
    val controllerName = status.nameOf(status.controllerId) ?: stringResource(Res.string.watch_party_default_controller)

    Text(
        text = if (status.isController) {
            stringResource(Res.string.watch_party_you_control)
        } else {
            stringResource(Res.string.watch_party_controlled_by, controllerName)
        },
        color = tokens.colors.textSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    if (!status.isController) {
        when (status.requestState) {
            ControlRequestState.Waiting -> Text(
                text = stringResource(Res.string.watch_party_request_waiting),
                color = tokens.colors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            ControlRequestState.Denied -> Text(
                text = stringResource(Res.string.watch_party_request_denied),
                color = tokens.colors.danger,
                style = MaterialTheme.typography.bodySmall,
            )
            ControlRequestState.None -> Unit
        }
    }

    when {
        status.isOwner && !status.isController -> DialogButtons {
            DialogButton(
                text = stringResource(Res.string.watch_party_take_back),
                onClick = WatchPartyRoomController::takeBackControl,
                style = DialogButtonStyle.Primary,
            )
        }
        !status.isOwner && status.isController -> DialogButtons {
            DialogButton(
                text = stringResource(Res.string.watch_party_give_back),
                onClick = WatchPartyRoomController::returnControl,
            )
        }
        !status.isOwner -> DialogButtons {
            DialogButton(
                text = stringResource(Res.string.watch_party_ask_control),
                onClick = WatchPartyRoomController::requestControl,
                style = DialogButtonStyle.Primary,
                enabled = status.requestState != ControlRequestState.Waiting,
            )
        }
    }
}
