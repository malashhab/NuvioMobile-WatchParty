package com.nuvio.app.features.watchparty

import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.NuvioToastController
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_already_in_room
import nuvio.composeapp.generated.resources.watch_party_joined_from_invite
import org.jetbrains.compose.resources.getString

/** Opens the system share sheet with some text, or null where sharing isn't supported. */
@Composable
expect fun rememberTextSharer(): ((String) -> Unit)?

/** nuvio://watchparty/CODE opens the app and joins the room. */
fun buildWatchPartyInviteUrl(code: String): String = "nuvio://watchparty/$code"

/** Joins the room from an invite link, without dropping out of it if we're already there. */
suspend fun handleWatchPartyInvite(code: String) {
    if (!WatchPartyRoomController.isConfigured) return
    val formatted = WatchPartyRoomCode.format(code)
    if (WatchPartyRoomController.status.value.code == code) {
        NuvioToastController.show(getString(Res.string.watch_party_already_in_room, formatted))
        return
    }
    if (WatchPartyRoomController.joinRoom(code)) {
        NuvioToastController.show(getString(Res.string.watch_party_joined_from_invite, formatted))
    }
}
