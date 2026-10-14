package com.nuvio.app.core.deeplink

import com.nuvio.app.features.watchparty.WatchPartyServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUrlBridgeTest {

    @Test
    fun `parses watch party invite deeplink`() {
        assertEquals(
            AppDeepLink.WatchPartyJoin(code = "K7QMP4XD"),
            parseAppDeepLink("nuvio://watchparty/k7qm-p4xd"),
        )
    }

    @Test
    fun `parses watch party invite with code parameter`() {
        assertEquals(
            AppDeepLink.WatchPartyJoin(code = "K7QMP4XD"),
            parseAppDeepLink("nuvio://watchparty?code=K7QMP4XD"),
        )
    }

    @Test
    fun `parses watch party invite with the host's server`() {
        assertEquals(
            AppDeepLink.WatchPartyJoin(
                code = "K7QMP4XD",
                server = WatchPartyServer(url = "https://abcd1234.supabase.co", key = "sb_publishable_x"),
            ),
            parseAppDeepLink("nuvio://watchparty/K7QMP4XD?server=https%3A%2F%2Fabcd1234.supabase.co&key=sb_publishable_x"),
        )
    }

    @Test
    fun `ignores watch party invite with invalid code`() {
        assertNull(parseAppDeepLink("nuvio://watchparty/NOT-VALID-0"))
        assertNull(parseAppDeepLink("nuvio://watchparty"))
    }

    @Test
    fun `parses existing notification meta deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            parseAppDeepLink("nuvio://meta?type=series&id=tt0944947"),
        )
    }

    @Test
    fun `parses direct nuvio addon install deeplink`() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            parseAppDeepLink("nuvio://free.nebulapro.xyz/sports/i/free/manifest.json"),
        )
    }

    @Test
    fun `parses stremio addon install deeplink`() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            parseAppDeepLink("stremio://free.nebulapro.xyz/sports/i/free/manifest.json"),
        )
    }

    @Test
    fun `parses direct imdb detail deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            parseAppDeepLink("nuvio://series/tt0944947"),
        )
    }

    @Test
    fun `parses provider imdb detail deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            parseAppDeepLink("nuvio://imdb/series/tt0944947"),
        )
    }

    @Test
    fun `parses provider tmdb detail deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tmdb:1399"),
            parseAppDeepLink("nuvio://tmdb/tv/1399"),
        )
    }

    @Test
    fun `does not treat reserved auth link as addon install`() {
        assertNull(parseAppDeepLink("nuvio://auth/trakt?code=abc"))
    }

    @Test
    fun `does not treat non-host stremio link as addon install`() {
        assertNull(parseAppDeepLink("stremio://detail/series/tt0944947"))
    }
}
