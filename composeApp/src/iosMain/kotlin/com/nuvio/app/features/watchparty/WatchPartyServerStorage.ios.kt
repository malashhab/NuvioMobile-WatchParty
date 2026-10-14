package com.nuvio.app.features.watchparty

import platform.Foundation.NSUserDefaults

internal actual object WatchPartyServerStorage {
    private const val urlKey = "watch_party_server_url"
    private const val keyKey = "watch_party_server_key"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    actual fun loadUrl(): String? = defaults.stringForKey(urlKey)

    actual fun loadKey(): String? = defaults.stringForKey(keyKey)

    actual fun save(url: String, key: String) {
        defaults.setObject(url, forKey = urlKey)
        defaults.setObject(key, forKey = keyKey)
    }

    actual fun clear() {
        defaults.removeObjectForKey(urlKey)
        defaults.removeObjectForKey(keyKey)
    }
}
