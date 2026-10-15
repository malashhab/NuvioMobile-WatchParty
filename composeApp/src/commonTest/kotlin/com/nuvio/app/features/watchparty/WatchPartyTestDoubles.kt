package com.nuvio.app.features.watchparty

/** Delivers messages instantly between connections, like a perfect network. */
internal class InMemoryWatchPartyHub {
    private val connections = mutableListOf<Connection>()
    val sentMessages = mutableListOf<WatchPartyMessage>()

    fun connect(): Connection = Connection().also { connections += it }

    inner class Connection : WatchPartyTransport {
        var isConnected = true
            private set
        private var listener: ((WatchPartyMessage) -> Unit)? = null

        override fun send(message: WatchPartyMessage) {
            if (!isConnected) return
            sentMessages += message
            connections
                .filter { it !== this && it.isConnected }
                .forEach { it.listener?.invoke(message) }
        }

        override fun setListener(listener: ((WatchPartyMessage) -> Unit)?) {
            this.listener = listener
        }

        fun disconnect() {
            isConnected = false
        }

        fun reconnect() {
            isConnected = true
        }
    }
}

internal class FakeWatchPartyPlayer(
    var content: WatchPartyContent? = null,
    var isReady: Boolean = true,
    var isPlaying: Boolean = false,
    var positionMs: Long = 0L,
) : WatchPartyPlayerPort {
    val actions = mutableListOf<String>()

    /** False mimics the real player, which only switches once the user picks a source. */
    var switchesContentOnOpen = true

    /** True mimics a source that has to load after every seek. */
    var loadsAfterSeek = false

    var playbackSpeed = 1f

    /** Kept apart from [actions], so tests about play, pause and seek aren't affected by speed nudges. */
    val speedChanges = mutableListOf<Float>()

    override fun currentContent(): WatchPartyContent? = content

    override fun currentPositionMs(): Long? = content?.let { positionMs }

    var source: WatchPartySource? = null

    /** The source passed with each [openContent]. */
    val openedSources = mutableListOf<WatchPartySource?>()

    override fun currentSource(): WatchPartySource? = source

    override fun currentPlayback(): WatchPartyPlayback? {
        val current = content ?: return null
        if (!isReady) return null
        return WatchPartyPlayback(
            content = current,
            isPlaying = isPlaying,
            positionMs = positionMs,
            source = source,
            speed = playbackSpeed,
        )
    }

    override fun setSpeed(speed: Float) {
        playbackSpeed = speed
        speedChanges += speed
    }

    override fun play() {
        isPlaying = true
        actions += "play"
    }

    override fun pause() {
        isPlaying = false
        actions += "pause"
    }

    override fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
        if (loadsAfterSeek) isReady = false
        actions += "seek:$positionMs"
    }

    override fun openContent(content: WatchPartyContent, positionMs: Long, source: WatchPartySource?) {
        actions += "open:${content.videoId}@$positionMs"
        openedSources += source
        if (!switchesContentOnOpen) return
        this.content = content
        this.positionMs = positionMs
        isReady = false
        isPlaying = false
    }
}

internal val episodeOne = WatchPartyContent(
    parentMetaId = "tt0903747",
    parentMetaType = "series",
    videoId = "tt0903747:1:1",
    seasonNumber = 1,
    episodeNumber = 1,
)

internal val episodeTwo = episodeOne.copy(videoId = "tt0903747:1:2", episodeNumber = 2)
