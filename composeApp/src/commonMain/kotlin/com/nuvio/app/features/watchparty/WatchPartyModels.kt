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
    /** Whoever created the room. They decide who controls playback. */
    val ownerMemberId: String,
)

@Serializable
data class WatchPartyMember(
    val memberId: String,
    val name: String,
)

@Serializable
sealed interface WatchPartyMessage {
    val senderId: String

    /**
     * The controller's full playback state. Play, pause, seek and episode changes are all sent as
     * a complete state, so a lost or repeated message never leaves a follower half-updated.
     */
    @Serializable
    @SerialName("state")
    data class State(
        override val senderId: String,
        val epoch: String,
        val seq: Long,
        val playback: WatchPartyPlayback,
    ) : WatchPartyMessage

    /** Sent by a member that joined or reconnected and needs the current state. */
    @Serializable
    @SerialName("state_request")
    data class StateRequest(
        override val senderId: String,
    ) : WatchPartyMessage

    /** A member asks the owner for control. */
    @Serializable
    @SerialName("control_request")
    data class ControlRequest(
        override val senderId: String,
        val name: String,
    ) : WatchPartyMessage

    /** The owner announces who controls playback now. Only the owner's announcements count. */
    @Serializable
    @SerialName("control_changed")
    data class ControlChanged(
        override val senderId: String,
        val epoch: String,
        val seq: Long,
        val controllerId: String,
    ) : WatchPartyMessage

    @Serializable
    @SerialName("control_denied")
    data class ControlDenied(
        override val senderId: String,
        val memberId: String,
    ) : WatchPartyMessage

    /** The current controller hands control back to the owner. */
    @Serializable
    @SerialName("control_return")
    data class ControlReturn(
        override val senderId: String,
    ) : WatchPartyMessage
}
