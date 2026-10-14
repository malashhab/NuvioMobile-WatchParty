package com.nuvio.app.features.watchparty

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchPartyServerTest {
    private val url = "https://abcd1234.supabase.co"
    private val publishable = "sb_publishable_example_123"

    private fun problemOf(url: String, key: String) =
        (WatchPartyServerValidator.check(url, key) as? WatchPartyServerCheck.Invalid)?.problem

    @OptIn(ExperimentalEncodingApi::class)
    private fun legacyJwt(role: String): String {
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = encoder.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val payload = encoder.encode("""{"iss":"supabase","role":"$role"}""".encodeToByteArray())
        return "$header.$payload.signature"
    }

    @Test
    fun publishableKeyAndHttpsUrlAreAccepted() {
        val check = WatchPartyServerValidator.check("  $url/  ", " $publishable ")

        assertEquals(WatchPartyServerCheck.Valid(WatchPartyServer(url, publishable)), check)
    }

    @Test
    fun urlWithoutSchemeGetsHttps() {
        val check = WatchPartyServerValidator.check("abcd1234.supabase.co", publishable)

        assertEquals(url, (check as WatchPartyServerCheck.Valid).server.url)
    }

    @Test
    fun legacyAnonKeyIsAccepted() {
        assertNull(problemOf(url, legacyJwt("anon")))
    }

    @Test
    fun secretKeysAreRefused() {
        assertEquals(WatchPartyServerProblem.SecretKey, problemOf(url, "sb_secret_abc"))
        assertEquals(WatchPartyServerProblem.SecretKey, problemOf(url, legacyJwt("service_role")))
    }

    @Test
    fun badUrlsAreRefused() {
        assertEquals(WatchPartyServerProblem.MissingUrl, problemOf(" ", publishable))
        assertEquals(WatchPartyServerProblem.NotHttps, problemOf("http://abcd1234.supabase.co", publishable))
        assertEquals(WatchPartyServerProblem.InvalidUrl, problemOf("https://localhost", publishable))
    }

    @Test
    fun missingKeyIsRefused() {
        assertEquals(WatchPartyServerProblem.MissingKey, problemOf(url, "  "))
    }

    @Test
    fun displayHostIsShortAndReadable() {
        assertEquals("abcd1234.supabase.co", WatchPartyServer(url, publishable).displayHost)
    }
}
