package com.nuvio.app.features.watchparty

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WatchPartySourceTest {
    // Debrid addons are installed from personal links that carry the user's API key.
    private val myInstall = "addon:com.stremio.torrentio.addon:https://torrentio.strem.fun/torbox=MY-SECRET-KEY/manifest.json"
    private val friendInstall = "addon:com.stremio.torrentio.addon:https://torrentio.strem.fun/torbox=FRIEND-KEY/manifest.json"

    private fun torrentio(addonId: String, description: String = "Show.S01E02.1080p.WEB.mkv\n👤 52") =
        WatchPartySource.shareable(
            addonId = addonId,
            addonName = "Torrentio",
            name = "Torrentio\n[TB+] 1080p",
            description = description,
            bingeGroup = "torrentio|1080p|WEB",
        )

    @Test
    fun addonInstallLinkIsRemoved() {
        assertEquals("addon:com.stremio.torrentio.addon", torrentio(myInstall).addonId)
    }

    @Test
    fun sharedFingerprintNeverContainsTheInstallLinkOrKey() {
        val json = Json.encodeToString(WatchPartySource.serializer(), torrentio(myInstall))

        assertFalse("MY-SECRET-KEY" in json, json)
        assertFalse("://" in json, json)
    }

    @Test
    fun sameAddonInstalledFromDifferentPersonalLinksMatches() {
        val friendsCopy = torrentio(friendInstall, description = "Show.S01E02.1080p.WEB.mkv\n👤 61")

        val result = WatchPartySourceMatcher.bestMatch(torrentio(myInstall), listOf(friendsCopy)) { it }

        assertEquals(friendsCopy, result?.item)
    }

    @Test
    fun anyFieldHoldingALinkIsDropped() {
        val source = WatchPartySource.shareable(
            addonId = "plugin-repo:https://example.com/repo?token=abc",
            addonName = "Plugins",
            name = "Stream",
            description = "Watch at https://example.com/video?token=abc",
        )

        assertNull(source.addonId)
        assertNull(source.description)
        assertEquals("Stream", source.name)
    }

    @Test
    fun simpleIdsAreKeptAsTheyAre() {
        assertEquals("plugin:scraper-1", WatchPartySource.shareableAddonId("plugin:scraper-1"))
        assertEquals("embedded", WatchPartySource.shareableAddonId("embedded"))
    }
}
