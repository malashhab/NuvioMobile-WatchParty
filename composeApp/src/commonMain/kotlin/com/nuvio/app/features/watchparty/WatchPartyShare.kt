package com.nuvio.app.features.watchparty

import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.NuvioToastController
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_already_in_room
import nuvio.composeapp.generated.resources.watch_party_joined_from_invite
import org.jetbrains.compose.resources.getString

/** Opens the system share sheet with some text, or null where sharing isn't supported. */
@Composable
expect fun rememberTextSharer(): ((String) -> Unit)?

/** A room to join, and the server it lives on when the invite says so. */
data class WatchPartyInvite(
    val code: String,
    val server: WatchPartyServer? = null,
)

/**
 * nuvio://watchparty/CODE?server=…&key=… opens the app and joins the room on the host's server, so
 * guests don't need a server of their own.
 */
fun buildWatchPartyInviteUrl(code: String, server: WatchPartyServer? = null): String = buildString {
    append("nuvio://watchparty/")
    append(code)
    if (server != null) {
        append("?server=")
        append(server.url.encodeURLParameter())
        append("&key=")
        append(server.key.encodeURLParameter())
    }
}

private val inviteLinkPattern = Regex("""nuvio://watchparty\S*""", RegexOption.IGNORE_CASE)

/**
 * Reads an invite from whatever the user pasted: a whole shared message, just the link, or just the
 * room code. Returns null when there's no valid code, or the invite names an unsafe server.
 */
fun parseWatchPartyInvite(text: String): WatchPartyInvite? {
    val link = inviteLinkPattern.find(text)?.value
    if (link == null) return WatchPartyRoomCode.normalize(text)?.let { WatchPartyInvite(code = it) }
    val parsed = runCatching { Url(link) }.getOrNull() ?: return null
    val code = (parsed.segments.firstOrNull { it.isNotBlank() } ?: parsed.parameters["code"])
        ?.let(WatchPartyRoomCode::normalize)
        ?: return null
    val serverUrl = parsed.parameters["server"]
    val key = parsed.parameters["key"]
    if (serverUrl == null && key == null) return WatchPartyInvite(code = code)
    return when (val check = WatchPartyServerValidator.check(serverUrl.orEmpty(), key.orEmpty())) {
        is WatchPartyServerCheck.Valid -> WatchPartyInvite(code = code, server = check.server)
        // A malformed or secret server makes the whole invite untrustworthy.
        is WatchPartyServerCheck.Invalid -> null
    }
}

/** Joins the room from an invite link, without dropping out of it if we're already there. */
suspend fun handleWatchPartyInvite(invite: WatchPartyInvite) {
    val formatted = WatchPartyRoomCode.format(invite.code)
    if (WatchPartyRoomController.status.value.code == invite.code) {
        NuvioToastController.show(getString(Res.string.watch_party_already_in_room, formatted))
        return
    }
    if (WatchPartyRoomController.join(invite) == WatchPartyJoinResult.Joined) {
        val host = WatchPartyRoomController.status.value.server?.displayHost.orEmpty()
        NuvioToastController.show(getString(Res.string.watch_party_joined_from_invite, formatted, host))
    }
}
