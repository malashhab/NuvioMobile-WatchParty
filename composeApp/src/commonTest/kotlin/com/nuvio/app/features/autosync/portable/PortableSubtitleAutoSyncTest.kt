package com.nuvio.app.features.autosync.portable

import com.nuvio.app.features.autosync.AutoSyncSubtitleCandidate
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PortableSubtitleAutoSyncTest {
    // A film's worth of dialogue: irregular gaps and line lengths, like real subtitles.
    private val dialogue: List<SubtitleSyncCue> = run {
        val random = Random(42)
        var time = 60_000L
        List(400) { index ->
            time += 800L + random.nextLong(6_000L)
            val duration = 1_000L + random.nextLong(3_000L)
            SubtitleSyncCue(startTimeMs = time, endTimeMs = time + duration, text = "Line $index").also {
                time += duration
            }
        }
    }

    // Minimal Matroska writer: every element uses an 8-byte size, which the reader accepts.
    private fun element(id: Long, vararg children: ByteArray): ByteArray {
        val payload = children.fold(ByteArray(0)) { all, child -> all + child }
        var idLength = 1
        while (idLength < 4 && id >= (1L shl (idLength * 8))) idLength++
        val idBytes = ByteArray(idLength) { ((id shr ((idLength - it - 1) * 8)) and 0xFF).toByte() }
        val size = payload.size.toLong()
        val sizeBytes = ByteArray(8) { if (it == 0) 0x01 else ((size shr ((7 - it) * 8)) and 0xFF).toByte() }
        return idBytes + sizeBytes + payload
    }

    private fun uint(id: Long, value: Long) = element(id, ByteArray(8) { ((value shr ((7 - it) * 8)) and 0xFF).toByte() })

    private fun text(id: Long, value: String) = element(id, value.encodeToByteArray())

    private fun subtitleTrack(number: Long, language: String, forced: Boolean = false) = element(
        0xAEL,
        uint(0xD7L, number),
        uint(0x83L, 17L),
        text(0x22B59CL, language),
        text(0x86L, "S_TEXT/UTF8"),
        uint(0x55AAL, if (forced) 1L else 0L),
    )

    private fun matroska(tracks: List<ByteArray>, cues: Map<Long, List<SubtitleSyncCue>>): ByteArray {
        val cuePoints = cues.flatMap { (track, lines) ->
            lines.map { cue ->
                element(
                    0xBBL,
                    uint(0xB3L, cue.startTimeMs),
                    element(0xB7L, uint(0xF7L, track), uint(0xB2L, cue.endTimeMs - cue.startTimeMs)),
                )
            }
        }
        return element(0x1A45DFA3L) + element(
            0x18538067L,
            element(0x1549A966L, uint(0x2AD7B1L, 1_000_000L)),
            element(0x1654AE6BL, *tracks.toTypedArray()),
            element(0x1C53BB6BL, *cuePoints.toTypedArray()),
        )
    }

    private fun sourceOf(file: ByteArray) = ByteRangeSource { start, length, _, requireExactLength ->
        if (start >= file.size) return@ByteRangeSource null
        val end = minOf(file.size.toLong(), start + length).toInt()
        if (requireExactLength && end - start.toInt() != length) return@ByteRangeSource null
        ByteRange(file.copyOfRange(start.toInt(), end), file.size.toLong())
    }

    private fun srt(cues: List<SubtitleSyncCue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(srtTime(cue.startTimeMs)).append(" --> ").append(srtTime(cue.endTimeMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    private fun srtTime(ms: Long): String {
        fun pad(value: Long, width: Int) = value.toString().padStart(width, '0')
        return "${pad(ms / 3_600_000, 2)}:${pad(ms / 60_000 % 60, 2)}:${pad(ms / 1_000 % 60, 2)},${pad(ms % 1_000, 3)}"
    }

    private fun shifted(by: Long) = dialogue.map {
        it.copy(startTimeMs = it.startTimeMs + by, endTimeMs = it.endTimeMs + by)
    }

    private suspend fun runAutoSync(
        subtitles: Map<String, String>,
        selected: String = "selected.srt",
        alternatives: List<AutoSyncSubtitleCandidate> = emptyList(),
        file: ByteArray = matroska(listOf(subtitleTrack(3, "eng")), mapOf(3L to dialogue)),
    ) = PortableSubtitleAutoSync.run(
        sourceUrl = "https://example.com/film.mkv",
        sourceHeaders = emptyMap(),
        selectedUrl = selected,
        selectedLanguage = "en",
        subtitleHeaders = { emptyMap() },
        alternatives = { alternatives },
        toleranceMs = 0,
        loadIndex = { MatroskaSubtitleIndex.load(sourceOf(file)) },
        loadSubtitle = { url, _ -> subtitles[url] },
    )

    @Test
    fun readsSubtitleTimingFromTheMatroskaIndex() = runTest {
        val file = matroska(
            tracks = listOf(subtitleTrack(3, "eng"), subtitleTrack(4, "fre", forced = true)),
            cues = mapOf(3L to dialogue, 4L to dialogue.take(20)),
        )

        val result = MatroskaSubtitleIndex.load(sourceOf(file))

        assertIs<EmbeddedSubtitleIndexResult.Found>(result)
        val english = result.tracks.single { it.language == "eng" }
        assertEquals(dialogue.map { it.startTimeMs }, english.cues.map { it.startTimeMs })
        assertEquals(dialogue.map { it.endTimeMs }, english.cues.map { it.endTimeMs })
        assertTrue(result.tracks.single { it.language == "fre" }.forced)
    }

    @Test
    fun aVideoWithoutBuiltInSubtitlesIsReported() = runTest {
        val video = element(0xAEL, uint(0xD7L, 1L), uint(0x83L, 1L))
        val file = element(0x1A45DFA3L) + element(0x18538067L, element(0x1654AE6BL, video))

        assertEquals(EmbeddedSubtitleIndexResult.NoSubtitleTracks, MatroskaSubtitleIndex.load(sourceOf(file)))
    }

    @Test
    fun aFileThatIsNotMatroskaIsUnavailable() = runTest {
        val mp4 = ByteArray(4_096) { if (it in 4..7) "ftyp"[it - 4].code.toByte() else 0 }

        assertEquals(EmbeddedSubtitleIndexResult.Unavailable, MatroskaSubtitleIndex.load(sourceOf(mp4)))
    }

    @Test
    fun aLateSubtitleIsPulledEarlierByTheRightAmount() = runTest {
        val result = runAutoSync(mapOf("selected.srt" to srt(shifted(by = 2_500L))))

        assertIs<PortableAutoSyncResult.Fixed>(result)
        assertEquals("selected.srt", result.fix.subtitleUrl)
        assertEquals(1.0, result.fix.speed)
        assertTrue(abs(result.fix.delayMs + 2_500) <= 50, "delay ${result.fix.delayMs}")
    }

    @Test
    fun anEarlySubtitleIsPushedLater() = runTest {
        val result = runAutoSync(mapOf("selected.srt" to srt(shifted(by = -4_000L))))

        assertIs<PortableAutoSyncResult.Fixed>(result)
        assertTrue(abs(result.fix.delayMs - 4_000) <= 50, "delay ${result.fix.delayMs}")
    }

    @Test
    fun aSubtitleForAnotherFrameRateIsStretched() = runTest {
        // Made for 25 fps while the video runs at 23.976: every line comes a little earlier.
        val ratio = 23.976 / 25.0
        val fast = dialogue.map {
            it.copy(startTimeMs = (it.startTimeMs * ratio).toLong(), endTimeMs = (it.endTimeMs * ratio).toLong())
        }

        val result = runAutoSync(mapOf("selected.srt" to srt(fast)))

        assertIs<PortableAutoSyncResult.Fixed>(result)
        // Shown at speed × time + delay, which must land each line back on the video's timing.
        val first = fast.first().startTimeMs * result.fix.speed + result.fix.delayMs
        val last = fast.last().startTimeMs * result.fix.speed + result.fix.delayMs
        assertTrue(abs(first - dialogue.first().startTimeMs) <= 150, "first line at $first")
        assertTrue(abs(last - dialogue.last().startTimeMs) <= 150, "last line at $last")
    }

    @Test
    fun aSubtitleThatMatchesNothingTriesAnotherOneInTheSameLanguage() = runTest {
        val unrelated = Random(7).let { random ->
            var time = 0L
            List(300) { index ->
                time += 500L + random.nextLong(9_000L)
                SubtitleSyncCue(startTimeMs = time, endTimeMs = time + 1_500L, text = "Other $index")
            }
        }

        val result = runAutoSync(
            subtitles = mapOf(
                "selected.srt" to srt(unrelated),
                "other.srt" to srt(shifted(by = 1_200L)),
                "french.srt" to srt(shifted(by = 0L)),
            ),
            alternatives = listOf(
                AutoSyncSubtitleCandidate(url = "french.srt", language = "fr"),
                AutoSyncSubtitleCandidate(url = "other.srt", language = "en"),
            ),
        )

        assertIs<PortableAutoSyncResult.Fixed>(result)
        assertEquals("other.srt", result.fix.subtitleUrl)
        assertTrue(abs(result.fix.delayMs + 1_200) <= 50, "delay ${result.fix.delayMs}")
    }

    @Test
    fun withoutBuiltInSubtitlesThereIsNothingToCompareWith() = runTest {
        val video = element(0xAEL, uint(0xD7L, 1L), uint(0x83L, 1L))
        val file = element(0x1A45DFA3L) + element(0x18538067L, element(0x1654AE6BL, video))

        val result = runAutoSync(mapOf("selected.srt" to srt(dialogue)), file = file)

        assertEquals(PortableAutoSyncResult.NoReference, result)
    }

    @Test
    fun forcedTracksAreOnlyUsedWhenNothingElseExists() {
        fun track(key: String, language: String, forced: Boolean, cues: Int) = EmbeddedSubtitleReference(
            key = key,
            language = language,
            label = key,
            cues = dialogue.take(cues),
            forced = forced,
            commentary = false,
            hearingImpaired = false,
            describesVideo = false,
            estimatedEndStartsMs = emptySet(),
        )
        val ranked = PortableSubtitleAutoSync.rankReferences(
            listOf(
                track("forced-en", "eng", forced = true, cues = 50),
                track("full-fr", "fre", forced = false, cues = 400),
                track("full-en", "eng", forced = false, cues = 300),
            ),
            language = "en",
        )

        assertEquals(listOf("full-en", "full-fr"), ranked.map { it.key })
    }
}
