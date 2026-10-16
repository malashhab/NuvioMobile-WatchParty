package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Mono audio samples (-1..1) of one stretch of the video. */
internal class PcmSpot(val samples: FloatArray, val sampleRate: Int)

/** Show each subtitle line at `speed × its time + delayMs`, as in the text-subtitle sync. */
internal data class AudioSyncFix(val delayMs: Int, val speed: Double)

/**
 * Sync to audio without a player audio tap, for players that can only read the stream again
 * (iPhone's mpv): a few stretches of the film's audio, chosen where the subtitle shows dense
 * dialogue, go through the same Silero speech detector and matcher as Android, and only a
 * confirmed match (the same strict lock Android uses) moves the subtitles.
 */
internal object PortableAudioSync {
    const val SPOT_MS = 40_000L
    private const val SPOT_COUNT = 6
    private const val PARALLEL_SPOTS = 2
    private const val MIN_DIALOGUE_LINES = 20

    /**
     * [readSpot] decodes the audio from a start time (ms) for a length (ms), or null when it can't.
     * Returns null when the audio doesn't confirm any timing.
     */
    suspend fun run(
        cues: List<SubtitleSyncCue>,
        durationMs: Long,
        weights: SileroVadWeights,
        readSpot: suspend (startMs: Long, lengthMs: Long) -> PcmSpot?,
    ): AudioSyncFix? {
        val track = SubtitleSpeechTrack.fromCues(cues.map { Triple(it.startTimeMs, it.endTimeMs, it.text) })
        if (track.size < MIN_DIALOGUE_LINES) return null
        val spots = DialogueSpotPlanner.plan(listOf(track), durationMs, SPOT_COUNT, SPOT_MS)
        if (spots.isEmpty()) return null

        val timeline = SpeechTimeline()
        // The timeline and detector aren't shared between threads on every platform: one at a time.
        val analysis = Mutex()
        val downloads = Semaphore(PARALLEL_SPOTS)
        coroutineScope {
            spots.map { startMs ->
                async {
                    val pcm = downloads.withPermit { readSpot(startMs, SPOT_MS) } ?: return@async
                    withContext(Dispatchers.Default) {
                        analysis.withLock { analyse(pcm, startMs, weights, timeline) }
                    }
                }
            }.awaitAll()
        }
        return decide(timeline, track)
    }

    internal fun analyse(pcm: PcmSpot, startMs: Long, weights: SileroVadWeights, timeline: SpeechTimeline) {
        val analyzer = SpeechAnalyzer(SileroVad(weights), timeline)
        val block = 16_000
        var offset = 0
        val buffer = FloatArray(block)
        while (offset < pcm.samples.size) {
            val count = minOf(block, pcm.samples.size - offset)
            pcm.samples.copyInto(buffer, 0, offset, offset + count)
            val timeUs = startMs * 1_000L + offset * 1_000_000L / pcm.sampleRate
            analyzer.accept(buffer, count, pcm.sampleRate, timeUs)
            offset += count
        }
    }

    /** The strict lock Android uses: enough dialogue and speech, a clear peak, both halves agree. */
    internal fun decide(timeline: SpeechTimeline, track: SubtitleSpeechTrack): AudioSyncFix? {
        val tracker = AudioSyncTracker(track, AudioSyncTracker.ProvisionalPolicy(enabled = false))
        val locked = tracker.update(timeline, playbackPositionMs = 0L) as? AudioSyncTracker.Outcome.Locked ?: return null
        val segment = locked.model.segments.firstOrNull() ?: return null
        return AudioSyncFix(delayMs = segment.shiftMs.toInt(), speed = segment.scale)
    }
}

/** Reads 16-bit PCM WAV (as mpv's pcm output writes it), mixing channels down to mono. */
internal fun readPcmWav(bytes: ByteArray): PcmSpot? {
    fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
    if (bytes.size < 44 || bytes.decodeToString(0, 4) != "RIFF" || bytes.decodeToString(8, 12) != "WAVE") return null
    var at = 12
    var channels = 0
    var sampleRate = 0
    var bits = 0
    while (at + 8 <= bytes.size) {
        val id = bytes.decodeToString(at, at + 4)
        // mpv writes an unknown (huge) data size when it can't seek back; read to the end then.
        val size = u32(at + 4).coerceAtMost((bytes.size - at - 8).toLong()).toInt()
        val body = at + 8
        when (id) {
            "fmt " -> if (size >= 16) {
                channels = u16(body + 2)
                sampleRate = u32(body + 4).toInt()
                bits = u16(body + 14)
            }
            "data" -> {
                if (channels <= 0 || sampleRate <= 0 || bits != 16) return null
                val frames = size / (2 * channels)
                val samples = FloatArray(frames)
                for (frame in 0 until frames) {
                    var sum = 0
                    for (channel in 0 until channels) {
                        val sample = u16(body + (frame * channels + channel) * 2).toShort().toInt()
                        sum += sample
                    }
                    samples[frame] = sum / (channels * 32_768f)
                }
                return PcmSpot(samples, sampleRate)
            }
        }
        at = body + size + (size and 1)
    }
    return null
}
