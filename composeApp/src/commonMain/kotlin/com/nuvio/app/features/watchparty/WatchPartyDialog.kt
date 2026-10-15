package com.nuvio.app.features.watchparty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.SurfaceEdge
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val MAX_INVITE_INPUT_LENGTH = 2_000

// The player is always landscape, where a single tall column runs off the screen.
private val RoomDialogMaxWidth = 760.dp
private val TwoColumnMinWidth = 600.dp

@Composable
internal fun WatchPartyDialog(onDismiss: () -> Unit) {
    val status by WatchPartyRoomController.status.collectAsStateWithLifecycle()
    val code = status.code

    if (code == null) {
        WatchPartyDialogFrame(
            onDismiss = onDismiss,
            widthLimit = MaterialTheme.nuvio.components.dialogMaxWidth,
        ) {
            DialogTitle()
            Text(
                text = stringResource(Res.string.watch_party_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
            JoinOrCreateContent(onDismiss = onDismiss)
        }
    } else {
        WatchPartyDialogFrame(
            onDismiss = onDismiss,
            widthLimit = RoomDialogMaxWidth,
            secondColumn = { RoomPeople(status = status, onDismiss = onDismiss) },
        ) {
            DialogTitle()
            RoomOverview(status = status, code = code)
        }
    }
}

/**
 * Looks like Nuvio's standard dialog, but can be wider, splits into two columns when there's room,
 * and scrolls instead of running off short screens.
 */
@Composable
private fun WatchPartyDialogFrame(
    onDismiss: () -> Unit,
    widthLimit: Dp,
    secondColumn: (@Composable ColumnScope.() -> Unit)? = null,
    firstColumn: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // A plain Dialog: Material's alert dialog quietly caps the width at 560 dp, too narrow for two columns.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = widthLimit)
                .fillMaxWidth(),
            shape = tokens.shapes.dialog,
            color = tokens.colors.surfaceDialog,
            contentColor = tokens.colors.textPrimary,
            border = BorderStroke(tokens.borders.thin, SurfaceEdge),
        ) {
            BoxWithConstraints {
                val columnArrangement = Arrangement.spacedBy(tokens.spacing.listGap)
                if (secondColumn != null && maxWidth >= TwoColumnMinWidth) {
                    Row(
                        modifier = Modifier.padding(tokens.spacing.dialogPadding),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = columnArrangement,
                            content = firstColumn,
                        )
                        Column(
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = columnArrangement,
                            content = secondColumn,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .padding(tokens.spacing.dialogPadding)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = columnArrangement,
                    ) {
                        firstColumn()
                        secondColumn?.invoke(this)
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogTitle() {
    Text(
        text = stringResource(Res.string.watch_party_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.nuvio.colors.textPrimary,
    )
}

@Composable
private fun JoinOrCreateContent(onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<StringResource?>(null) }
    // An invite for someone else's server waits for a yes before we connect to it.
    var inviteToConfirm by remember { mutableStateOf<WatchPartyInvite?>(null) }
    val canHost = remember { WatchPartyRoomController.canHost }
    val tokens = MaterialTheme.nuvio

    val pending = inviteToConfirm
    if (pending?.server != null) {
        Text(
            text = stringResource(
                Res.string.watch_party_join_other_server,
                WatchPartyRoomCode.format(pending.code),
                pending.server.displayHost,
            ),
            color = tokens.colors.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(Res.string.watch_party_join_other_server_hint),
            color = tokens.colors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = { inviteToConfirm = null })
            DialogButton(
                text = stringResource(Res.string.watch_party_join),
                onClick = { WatchPartyRoomController.join(pending) },
                style = DialogButtonStyle.Primary,
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        NuvioInputField(
            value = input,
            onValueChange = {
                // Room for a whole pasted invite message, not just the code.
                input = it.take(MAX_INVITE_INPUT_LENGTH)
                problem = null
            },
            placeholder = stringResource(Res.string.watch_party_code_placeholder),
        )
        problem?.let {
            Text(
                text = stringResource(it),
                color = tokens.colors.danger,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!canHost) {
            Text(
                text = stringResource(Res.string.watch_party_no_server_hint),
                color = tokens.colors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    DialogButtons {
        DialogButton(
            text = stringResource(Res.string.watch_party_join),
            onClick = {
                val invite = parseWatchPartyInvite(input)
                when {
                    invite == null -> problem = Res.string.watch_party_invalid_code
                    invite.server != null && invite.server != WatchPartyServerSettings.hostingServer() ->
                        inviteToConfirm = invite
                    WatchPartyRoomController.join(invite) == WatchPartyJoinResult.NeedsServer ->
                        problem = Res.string.watch_party_needs_invite
                }
            },
            style = if (canHost) DialogButtonStyle.Secondary else DialogButtonStyle.Primary,
        )
        if (canHost) {
            DialogButton(
                text = stringResource(Res.string.watch_party_create),
                onClick = { WatchPartyRoomController.createRoom() },
                style = DialogButtonStyle.Primary,
            )
        }
    }
    DialogButtons {
        DialogButton(text = stringResource(Res.string.watch_party_close), onClick = onDismiss)
    }
}

/** Left side: the code to share, and how the connection is doing. */
@Composable
private fun RoomOverview(status: WatchPartyStatus, code: String) {
    val tokens = MaterialTheme.nuvio

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
    InviteButtons(code = code, server = status.server)
    status.server?.let { server ->
        Text(
            text = stringResource(Res.string.watch_party_server_label, server.displayHost),
            color = tokens.colors.textMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
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

/** Right side: who's here, who controls playback, and leaving. */
@Composable
private fun RoomPeople(status: WatchPartyStatus, onDismiss: () -> Unit) {
    status.pendingRequests.forEach { member ->
        ControlRequestRow(member)
    }

    MemberList(status)

    ControlSection(status)

    LeaveButtons(isOwner = status.isOwner, onDismiss = onDismiss)
}


/** Both buttons hand out the whole invite, including the server, so guests need no server of their own. */
@Composable
private fun InviteButtons(code: String, server: WatchPartyServer?) {
    val clipboard = LocalClipboardManager.current
    val share = rememberTextSharer()
    val formatted = WatchPartyRoomCode.format(code)
    val copiedMessage = stringResource(Res.string.watch_party_code_copied)
    val inviteText = stringResource(
        Res.string.watch_party_invite_message,
        formatted,
        buildWatchPartyInviteUrl(code, server),
    )

    DialogButtons {
        DialogButton(
            text = stringResource(Res.string.watch_party_copy_code),
            onClick = {
                clipboard.setText(AnnotatedString(inviteText))
                NuvioToastController.show(copiedMessage)
            },
        )
        share?.let {
            DialogButton(
                text = stringResource(Res.string.watch_party_share_invite),
                onClick = { it(inviteText) },
            )
        }
    }
}

/** Leaving asks first, so a mis-tap doesn't drop you out of the room. */
@Composable
private fun LeaveButtons(isOwner: Boolean, onDismiss: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio

    if (confirming) {
        Text(
            text = stringResource(
                if (isOwner) Res.string.watch_party_leave_confirm_owner else Res.string.watch_party_leave_confirm,
            ),
            color = tokens.colors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        DialogButtons {
            DialogButton(text = stringResource(Res.string.watch_party_stay), onClick = { confirming = false })
            DialogButton(
                text = stringResource(Res.string.watch_party_leave),
                onClick = WatchPartyRoomController::leave,
                style = DialogButtonStyle.Destructive,
            )
        }
    } else {
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.watch_party_leave),
                onClick = { confirming = true },
                style = DialogButtonStyle.Destructive,
            )
            DialogButton(text = stringResource(Res.string.watch_party_close), onClick = onDismiss)
        }
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
    // The owner can remove anyone else; removing asks first, like leaving does.
    var removing by remember { mutableStateOf<WatchPartyMember?>(null) }

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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tags.isNotEmpty()) {
                        Text(
                            text = tags.joinToString(" · "),
                            color = tokens.colors.textMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (status.isOwner && member.memberId != status.localMemberId) {
                        Text(
                            text = stringResource(Res.string.watch_party_remove),
                            color = tokens.colors.danger,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(NuvioTokens.Space.s8))
                                .clickable(role = Role.Button) { removing = member }
                                .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s4),
                        )
                    }
                }
            }
        }

        // Still in the room? They may have left while we were asking.
        removing?.takeIf { pending -> status.members.any { it.memberId == pending.memberId } }?.let { member ->
            Text(
                text = stringResource(Res.string.watch_party_remove_confirm, member.name),
                color = tokens.colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            DialogButtons {
                DialogButton(text = stringResource(Res.string.watch_party_stay), onClick = { removing = null })
                DialogButton(
                    text = stringResource(Res.string.watch_party_remove),
                    onClick = {
                        WatchPartyRoomController.removeMember(member.memberId)
                        removing = null
                    },
                    style = DialogButtonStyle.Destructive,
                )
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
