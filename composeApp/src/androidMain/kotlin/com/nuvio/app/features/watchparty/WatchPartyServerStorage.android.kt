package com.nuvio.app.features.watchparty

import android.content.Context
import android.content.SharedPreferences

internal actual object WatchPartyServerStorage {
    private const val preferencesName = "nuvio_watch_party"
    private const val urlKey = "server_url"
    private const val keyKey = "server_key"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadUrl(): String? = preferences?.getString(urlKey, null)

    actual fun loadKey(): String? = preferences?.getString(keyKey, null)

    actual fun save(url: String, key: String) {
        preferences?.edit()?.putString(urlKey, url)?.putString(keyKey, key)?.apply()
    }

    actual fun clear() {
        preferences?.edit()?.remove(urlKey)?.remove(keyKey)?.apply()
    }
}
