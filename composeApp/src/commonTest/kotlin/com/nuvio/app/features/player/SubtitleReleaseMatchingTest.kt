package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleReleaseMatchingTest {
    private val release = "Spider-Man.Across.the.Spider-Verse.2023.1080p.AMZN.WEB-DL.DDP5.1.H.264-FLUX"

    @Test
    fun sameGroupAndSourceComesFirst() {
        val subtitles = listOf(
            "Spider-Man.Across.the.Spider-Verse.2023.1080p.BluRay.x264-SPARKS.srt",
            "English",
            "Spider-Man.Across.the.Spider-Verse.2023.1080p.AMZN.WEB-DL.DDP5.1.H.264-FLUX.srt",
        )
        val ranked = SubtitleReleaseMatching.rank(subtitles, release) { it }
        assertEquals(subtitles[2], ranked.first())
        // A subtitle that says nothing about its release beats one made for another release.
        assertEquals(listOf(subtitles[2], subtitles[1], subtitles[0]), ranked)
    }

    @Test
    fun webDlAndWebRipCountAsTheSameFamilyForPlainWeb() {
        assertTrue(
            SubtitleReleaseMatching.score("Show.S01E01.WEBRip.x264-ION10", "Show S01E01 1080p WEB H264-GROUP") > 0,
        )
    }

    @Test
    fun noReleaseInfoKeepsOrder() {
        val subtitles = listOf("English", "English (SDH)", "English 2")
        assertEquals(subtitles, SubtitleReleaseMatching.rank(subtitles, "Some Movie") { it })
        assertEquals(subtitles, SubtitleReleaseMatching.rank(subtitles, null) { it })
    }

    @Test
    fun languageWordIsNotTakenAsGroup() {
        assertEquals(0, SubtitleReleaseMatching.score("Movie-English.srt", "Movie.2020.1080p.WEB-DL-ENGLISH"))
    }

    @Test
    fun bestRespectsFilter() {
        val subtitles = listOf("fr: Movie.2020.1080p.WEB-DL-FLUX", "en: Movie.2020.BluRay-GRP", "en: Movie.2020.1080p.WEB-DL-FLUX")
        val best = SubtitleReleaseMatching.best(subtitles, "Movie 2020 1080p WEB-DL-FLUX", { it }) { it.startsWith("en") }
        assertEquals(subtitles[2], best)
    }
}
