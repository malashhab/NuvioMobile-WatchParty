package com.nuvio.app.features.watchparty

/**
 * Delivers messages between room members. Tests use an in-memory version; the network
 * version (Supabase Realtime) plugs in here later without changing the sync rules.
 */
interface WatchPartyTransport {
    fun send(message: WatchPartyMessage)
    fun setListener(listener: ((WatchPartyMessage) -> Unit)?)
}

/** The coordinator's view of the local player. The player screen implements this. */
interface WatchPartyPlayerPort {
    /** What is loaded or loading right now, or null when nothing is open. */
    fun currentContent(): WatchPartyContent?

    /** Live playback state, or null while the content is still loading. */
    fun currentPlayback(): WatchPartyPlayback?

    /** Position even while loading, e.g. the target of a seek in progress; null when unknown. */
    fun currentPositionMs(): Long?

    /** Shareable description of the current stream, available even while loading. */
    fun currentSource(): WatchPartySource?

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** Playback rate; followers nudge it slightly to close small gaps without seeking. */
    fun setSpeed(speed: Float)
    fun openContent(content: WatchPartyContent, positionMs: Long, source: WatchPartySource?)
}
