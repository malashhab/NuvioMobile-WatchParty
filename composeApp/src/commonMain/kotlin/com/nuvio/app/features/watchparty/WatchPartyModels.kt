package com.nuvio.app.features.watchparty

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the room is watching. Only content identity is shared, never stream URLs or headers:
 * every member resolves the stream from their own addons, and URLs can carry private tokens.
 */
@Serializable
data class WatchPartyContent(
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

@Serializable
data class WatchPartyPlayback(
    val content: WatchPartyContent,
    val isPlaying: Boolean,
    val positionMs: Long,
)

data class WatchPartyRoom(
    val code: String,
    val hostMemberId: String,
)

@Serializable
sealed interface WatchPartyMessage {
    val senderId: String

    /**
     * The host's full playback state. Play, pause, seek and episode changes are all sent as a
     * complete state, so a lost or repeated message never leaves a guest half-updated.
     */
    @Serializable
    @SerialName("state")
    data class State(
        override val senderId: String,
        val epoch: String,
        val seq: Long,
        val playback: WatchPartyPlayback,
    ) : WatchPartyMessage

    /** Sent by a guest that joined or reconnected and needs the current state. */
    @Serializable
    @SerialName("state_request")
    data class StateRequest(
        override val senderId: String,
    ) : WatchPartyMessage
}
