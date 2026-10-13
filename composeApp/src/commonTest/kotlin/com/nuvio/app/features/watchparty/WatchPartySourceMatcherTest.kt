package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartySourceMatcherTest {
    private fun match(wanted: WatchPartySource, vararg candidates: WatchPartySource) =
        WatchPartySourceMatcher.bestMatch(wanted, candidates.toList()) { it }

    private val torrentio1080 = WatchPartySource(
        addonId = "torrentio",
        name = "Torrentio\n1080p",
        description = "Show.S01E02.1080p.WEB.mkv\n👤 52 💾 1.4 GB",
        bingeGroup = "torrentio|1080p|WEB",
    )

    @Test
    fun sameTorrentFileWinsOverEverythingElse() {
        val wanted = WatchPartySource(infoHash = "ABC123", fileIdx = 2)
        val other = torrentio1080
        val sameFile = WatchPartySource(addonId = "other", infoHash = "abc123", fileIdx = 2)

        val result = match(wanted, other, sameFile)

        assertEquals(sameFile, result?.item)
        assertTrue(result!!.isExactFile)
    }

    @Test
    fun sameTorrentButDifferentFileIsNotAMatch() {
        val result = match(
            WatchPartySource(infoHash = "abc123", fileIdx = 2),
            WatchPartySource(infoHash = "abc123", fileIdx = 3),
        )

        assertNull(result)
    }

    @Test
    fun sameFileNameMatchesAcrossServices() {
        val result = match(
            WatchPartySource(addonId = "a", filename = "Show.S01E02.1080p.WEB.mkv"),
            WatchPartySource(addonId = "b", filename = "show.s01e02.1080p.web.mkv"),
        )

        assertEquals(1, result?.tier)
    }

    @Test
    fun releaseLineMatchIgnoresChangingSeederCounts() {
        val mine = torrentio1080.copy(description = "Show.S01E02.1080p.WEB.mkv\n👤 61 💾 1.4 GB")

        val result = match(torrentio1080, mine)

        assertEquals(3, result?.tier)
        assertFalse(result!!.isExactFile)
    }

    @Test
    fun qualityGroupIsUsedWhenTheReleaseDiffers() {
        val otherRelease = torrentio1080.copy(description = "Show.S01E02.1080p.AMZN.mkv")

        assertEquals(4, match(torrentio1080, otherRelease)?.tier)
    }

    @Test
    fun strongerMatchIsPreferredEvenWhenListedLater() {
        val sameGroup = torrentio1080.copy(description = "Different.Release.mkv")
        val sameRelease = torrentio1080

        assertEquals(sameRelease, match(torrentio1080, sameGroup, sameRelease)?.item)
    }

    @Test
    fun differentAddonWithSameNameIsNotAMatch() {
        assertNull(match(torrentio1080, torrentio1080.copy(addonId = "another-addon")))
    }

    @Test
    fun unknownAddonsNeverMatchEachOther() {
        val wanted = WatchPartySource(name = "1080p", videoSize = 100L)

        assertNull(match(wanted, WatchPartySource(name = "1080p", videoSize = 100L)))
    }

    @Test
    fun displayLabelFlattensLineBreaks() {
        assertEquals("Torrentio 1080p", torrentio1080.displayLabel)
    }
}
