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

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun openContent(content: WatchPartyContent, positionMs: Long)
}
