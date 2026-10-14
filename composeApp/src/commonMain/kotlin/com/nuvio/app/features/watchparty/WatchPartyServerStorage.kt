package com.nuvio.app.features.watchparty

/** The user's own Watch Party server, saved on this device only (not synced, not per profile). */
internal expect object WatchPartyServerStorage {
    fun loadUrl(): String?
    fun loadKey(): String?
    fun save(url: String, key: String)
    fun clear()
}
