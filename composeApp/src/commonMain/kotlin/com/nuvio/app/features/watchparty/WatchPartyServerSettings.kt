package com.nuvio.app.features.watchparty

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which server this device hosts rooms on: the user's own from Settings, else the one built into
 * this copy of the app (personal builds only; public builds ship without one). Guests joining through
 * an invite use the host's server instead.
 */
object WatchPartyServerSettings {
    private val _custom = MutableStateFlow<WatchPartyServer?>(null)

    /** The user's own server from Settings, if any. */
    val custom: StateFlow<WatchPartyServer?> = _custom.asStateFlow()
    private var loaded = false

    val builtIn: WatchPartyServer? by lazy {
        (WatchPartyServerValidator.check(WatchPartyConfig.SUPABASE_URL, WatchPartyConfig.SUPABASE_KEY)
            as? WatchPartyServerCheck.Valid)?.server
    }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val url = WatchPartyServerStorage.loadUrl() ?: return
        val key = WatchPartyServerStorage.loadKey() ?: return
        _custom.value = (WatchPartyServerValidator.check(url, key) as? WatchPartyServerCheck.Valid)?.server
    }

    /** The server rooms are created on, or null when this device can only join through invites. */
    fun hostingServer(): WatchPartyServer? {
        ensureLoaded()
        return _custom.value ?: builtIn
    }

    fun save(server: WatchPartyServer) {
        WatchPartyServerStorage.save(server.url, server.key)
        _custom.value = server
        loaded = true
    }

    fun clear() {
        WatchPartyServerStorage.clear()
        _custom.value = null
        loaded = true
    }
}
