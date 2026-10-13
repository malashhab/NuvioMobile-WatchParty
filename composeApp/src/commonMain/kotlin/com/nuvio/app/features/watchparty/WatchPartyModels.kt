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

/**
 * Describes the controller's stream well enough for followers to find the same one among their own
 * sources. Never includes the stream URL or headers, which can carry private debrid tokens.
 */
@Serializable
data class WatchPartySource(
    val addonId: String? = null,
    val addonName: String? = null,
    val name: String? = null,
    val description: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val bingeGroup: String? = null,
) {
    /** Short label for messages, e.g. "Torrentio 1080p". */
    val displayLabel: String
        get() = (name ?: addonName).orEmpty().replace(Regex("\\s+"), " ").trim().take(80)

    companion object {
        /**
         * Builds a fingerprint that is safe to share. Addon ids embed the install URL, which for debrid
         * addons carries the user's API key, so only the public manifest id is kept. Any other field that
         * contains a URL is dropped as well.
         */
        fun shareable(
            addonId: String?,
            addonName: String?,
            name: String?,
            description: String?,
            infoHash: String? = null,
            fileIdx: Int? = null,
            filename: String? = null,
            videoSize: Long? = null,
            bingeGroup: String? = null,
        ): WatchPartySource =
            WatchPartySource(
                addonId = shareableAddonId(addonId),
                addonName = addonName.withoutUrl(),
                name = name.withoutUrl(),
                description = description.withoutUrl(),
                infoHash = infoHash.withoutUrl(),
                fileIdx = fileIdx,
                filename = filename.withoutUrl(),
                videoSize = videoSize,
                bingeGroup = bingeGroup.withoutUrl(),
            )

        /** "addon:<manifest id>:<install url>" becomes "addon:<manifest id>"; other URL-bearing ids are dropped. */
        internal fun shareableAddonId(addonId: String?): String? {
            val id = addonId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (id.startsWith(ADDON_ID_PREFIX)) {
                val manifestId = id.removePrefix(ADDON_ID_PREFIX).substringBefore(':')
                return manifestId.takeIf { it.isNotEmpty() }?.let { ADDON_ID_PREFIX + it }
            }
            return id.withoutUrl()
        }

        private fun String?.withoutUrl(): String? = this?.takeUnless { "://" in it }

        private const val ADDON_ID_PREFIX = "addon:"
    }
}

@Serializable
data class WatchPartyPlayback(
    val content: WatchPartyContent,
    val isPlaying: Boolean,
    val positionMs: Long,
    val source: WatchPartySource? = null,
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
        /** Set while the room is held: get ready at this position, answer with [Ready], and wait. */
        val holdId: String? = null,
    ) : WatchPartyMessage

    /** A member is loaded and positioned for the hold the controller announced. */
    @Serializable
    @SerialName("ready")
    data class Ready(
        override val senderId: String,
        val holdId: String,
    ) : WatchPartyMessage

    /** Sent by a member that joined or reconnected and needs the current state. */
    @Serializable
    @SerialName("state_request")
    data class StateRequest(
        override val senderId: String,
    ) : WatchPartyMessage

    /** "Still here" between updates: from the controller while it loads, and from followers in the player. */
    @Serializable
    @SerialName("keep_alive")
    data class KeepAlive(
        override val senderId: String,
    ) : WatchPartyMessage

    /** A follower's video stalled or recovered mid-play, so the controller can hold the room for them. */
    @Serializable
    @SerialName("loading")
    data class LoadingChanged(
        override val senderId: String,
        val isLoading: Boolean,
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
