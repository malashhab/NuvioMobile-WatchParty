package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableAudioSyncTest {
    private val durationMs = 100 * 60_000L

    /** Irregular dialogue over the whole film, as a real subtitle file has. */
    private fun cues(seed: Int = 7): List<SubtitleSyncCue> {
        val random = Random(seed)
        val out = ArrayList<SubtitleSyncCue>()
        var t = 60_000L
        while (t < durationMs - 60_000L) {
            val length = 800L + random.nextLong(3_200L)
            out += SubtitleSyncCue(t, t + length, "line ${out.size}")
            t += length + 300L + random.nextLong(6_000L)
        }
        return out
    }

    /** What the speech detector would hear in the planned spots, when speech runs [shiftMs] after the subtitles. */
    private fun timelineFor(cues: List<SubtitleSyncCue>, shiftMs: Long): SpeechTimeline {
        val track = SubtitleSpeechTrack.fromCues(cues.map { Triple(it.startTimeMs, it.endTimeMs, it.text) })
        val spots = DialogueSpotPlanner.plan(listOf(track), durationMs, 6, PortableAudioSync.SPOT_MS)
        val timeline = SpeechTimeline()
        val frameMs = SpeechTimeline.FRAME_DURATION_MS.toLong()
        val noise = Random(3)
        for (spot in spots) {
            for (frame in (spot / frameMs).toInt() until ((spot + PortableAudioSync.SPOT_MS) / frameMs).toInt()) {
                val t = frame * frameMs - shiftMs
                val speaking = cues.any { t >= it.startTimeMs && t < it.endTimeMs }
                val p = if (speaking) 0.85f + noise.nextFloat() * 0.1f else noise.nextFloat() * 0.1f
                timeline.record(frame, p)
            }
        }
        return timeline
    }

    @Test
    fun findsTheDelayOfLateSpeech() {
        val cues = cues()
        val track = SubtitleSpeechTrack.fromCues(cues.map { Triple(it.startTimeMs, it.endTimeMs, it.text) })
        val fix = assertNotNull(PortableAudioSync.decide(timelineFor(cues, shiftMs = 2_000L), track))
        assertEquals(1.0, fix.speed)
        assertTrue(abs(fix.delayMs - 2_000) < 200, "delay ${fix.delayMs}")
    }

    @Test
    fun unrelatedAudioMovesNothing() {
        val track = SubtitleSpeechTrack.fromCues(cues().map { Triple(it.startTimeMs, it.endTimeMs, it.text) })
        // Speech from a different film's subtitles.
        assertNull(PortableAudioSync.decide(timelineFor(cues(seed = 99), shiftMs = 0L), track))
    }

    @Test
    fun readsMpvWav() {
        val samples = shortArrayOf(0, 16_384, -16_384, 32_767)
        val wav = wav(sampleRate = 16_000, channels = 1, samples)
        val pcm = assertNotNull(readPcmWav(wav))
        assertEquals(16_000, pcm.sampleRate)
        assertEquals(4, pcm.samples.size)
        assertEquals(0.5f, pcm.samples[1])
        assertEquals(-0.5f, pcm.samples[2])
    }

    @Test
    fun mixesStereoAndToleratesUnknownDataSize() {
        val wav = wav(sampleRate = 48_000, channels = 2, shortArrayOf(16_384, 0, 16_384, 16_384), dataSize = 0xFFFFFFFFL)
        val pcm = assertNotNull(readPcmWav(wav))
        assertEquals(listOf(0.25f, 0.5f), pcm.samples.toList())
        assertNull(readPcmWav("not a wav file at all, just some text here....".encodeToByteArray()))
    }

    private fun wav(sampleRate: Int, channels: Int, samples: ShortArray, dataSize: Long? = null): ByteArray {
        val out = ArrayList<Byte>()
        fun ascii(s: String) { out += s.encodeToByteArray().toList() }
        fun u16(v: Int) { out += v.toByte(); out += (v shr 8).toByte() }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16((v shr 16).toInt()) }
        ascii("RIFF"); u32(36L + samples.size * 2); ascii("WAVE")
        ascii("fmt "); u32(16); u16(1); u16(channels); u32(sampleRate.toLong())
        u32(sampleRate.toLong() * channels * 2); u16(channels * 2); u16(16)
        ascii("data"); u32(dataSize ?: (samples.size * 2L))
        samples.forEach { u16(it.toInt()) }
        return out.toByteArray()
    }
}
