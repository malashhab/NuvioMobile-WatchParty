package com.nuvio.app.features.autosync.portable

import com.nuvio.app.features.player.SubtitleSyncCue

/**
 * The MP4 counterpart of [MatroskaSubtitleIndex]. An MP4's "moov" box lists every sample of every
 * track with its duration and size. For a text subtitle track (mov_text/tx3g or WebVTT) an empty
 * sample is the gap between two lines, so the times of the lines come from those tables alone,
 * without reading any subtitle text or video. Only the moov box is downloaded, wherever it sits.
 */
internal object Mp4SubtitleIndex {
    private const val HEADER_PROBE_BYTES = 64 * 1024
    private const val BOX_HEADER_BYTES = 16
    private const val MAX_MOOV_BYTES = 20 * 1024 * 1024
    private const val MAX_TOP_LEVEL_HOPS = 8
    private const val MIN_INDEXED_CUES = 8
    private const val MIN_INDEXED_SPAN_MS = 30_000L

    // An empty tx3g sample is just its 2-byte text length; an empty WebVTT sample is one 8-byte "vtte" box.
    private const val EMPTY_TX3G_SAMPLE_BYTES = 2L
    private const val EMPTY_WVTT_SAMPLE_BYTES = 8L

    private val subtitleHandlers = setOf("sbtl", "text", "subt")
    private val firstBoxTypes = setOf("ftyp", "moov", "free", "skip", "wide", "mdat")

    suspend fun load(source: ByteRangeSource): EmbeddedSubtitleIndexResult {
        val moov = findMoov(source) ?: return EmbeddedSubtitleIndexResult.Unavailable
        return parseMoov(moov)
    }

    /** Walks the top-level boxes (ftyp, mdat, moov…) with small reads until it finds moov. */
    private suspend fun findMoov(source: ByteRangeSource): ByteArray? {
        val probe = source.read(0L, HEADER_PROBE_BYTES, requirePartialContent = false, requireExactLength = false)
            ?: return null
        val first = readBoxHeader(probe.bytes, 0, probe.bytes.size.toLong()) ?: return null
        if (first.type !in firstBoxTypes) return null
        val fileLength = probe.totalLength
        var offset = 0L
        repeat(MAX_TOP_LEVEL_HOPS) {
            val remaining = fileLength?.minus(offset) ?: Long.MAX_VALUE
            val header = if (offset + BOX_HEADER_BYTES <= probe.bytes.size) {
                readBoxHeader(probe.bytes, offset.toInt(), remaining)
            } else {
                val bytes = source.read(offset, BOX_HEADER_BYTES, requirePartialContent = true, requireExactLength = false)
                    ?.bytes ?: return null
                readBoxHeader(bytes, 0, remaining)?.copy(start = offset)
            } ?: return null
            val boxStart = header.start
            if (header.type == "moov") {
                if (header.size > MAX_MOOV_BYTES) return null
                val size = header.size.toInt()
                if (boxStart + size <= probe.bytes.size) {
                    return probe.bytes.copyOfRange(boxStart.toInt(), (boxStart + size).toInt())
                }
                return source.read(boxStart, size, requirePartialContent = boxStart > 0, requireExactLength = true)?.bytes
            }
            if (header.size <= 0L) return null
            offset = boxStart + header.size
            if (fileLength != null && offset >= fileLength) return null
        }
        return null
    }

    // A damaged table reads past the box; that is the same as having no usable index.
    internal fun parseMoov(moov: ByteArray): EmbeddedSubtitleIndexResult =
        runCatching { parseMoovUnchecked(moov) }.getOrDefault(EmbeddedSubtitleIndexResult.Unavailable)

    private fun parseMoovUnchecked(moov: ByteArray): EmbeddedSubtitleIndexResult {
        val root = readBoxHeader(moov, 0, moov.size.toLong()) ?: return EmbeddedSubtitleIndexResult.Unavailable
        if (root.type != "moov" || root.size > moov.size) return EmbeddedSubtitleIndexResult.Unavailable
        val movieTimescale = child(moov, root, "mvhd")?.let { parseMvhdTimescale(moov, it) }
        var sawSubtitleTrack = false
        val references = children(moov, root).filter { it.type == "trak" }.mapNotNull { trak ->
            val track = parseTrack(moov, trak, movieTimescale) ?: return@mapNotNull null
            sawSubtitleTrack = true
            val cues = track.cues
            if (cues.size < MIN_INDEXED_CUES) return@mapNotNull null
            if (cues.last().startTimeMs - cues.first().startTimeMs < MIN_INDEXED_SPAN_MS) return@mapNotNull null
            EmbeddedSubtitleReference(
                key = "mp4-stbl:${track.id}",
                language = track.language,
                label = track.name ?: "Track ${track.id}" + (track.language?.let { " ($it)" } ?: ""),
                cues = cues,
                forced = false,
                commentary = false,
                hearingImpaired = false,
                describesVideo = false,
                estimatedEndStartsMs = emptySet(),
            )
        }
        return when {
            references.isNotEmpty() -> EmbeddedSubtitleIndexResult.Found(references)
            !sawSubtitleTrack -> EmbeddedSubtitleIndexResult.NoSubtitleTracks
            else -> EmbeddedSubtitleIndexResult.Unavailable
        }
    }

    private class Mp4SubtitleTrack(val id: Int, val language: String?, val name: String?, val cues: List<SubtitleSyncCue>)

    private fun parseTrack(bytes: ByteArray, trak: Box, movieTimescale: Long?): Mp4SubtitleTrack? {
        val mdia = child(bytes, trak, "mdia") ?: return null
        val handler = child(bytes, mdia, "hdlr")?.let { fourCc(bytes, it.dataStart + 8) } ?: return null
        if (handler !in subtitleHandlers) return null
        val mdhd = child(bytes, mdia, "mdhd") ?: return null
        val version = bytes[mdhd.dataStart].toInt() and 0xFF
        val timescale = if (version == 1) u32(bytes, mdhd.dataStart + 20) else u32(bytes, mdhd.dataStart + 12)
        if (timescale <= 0L) return null
        val languageOffset = if (version == 1) mdhd.dataStart + 32 else mdhd.dataStart + 20
        val language = child(bytes, mdia, "elng")?.let { cString(bytes, it.dataStart + 4, it.end) }
            ?: packedLanguage(u16(bytes, languageOffset))
        val stbl = child(bytes, mdia, "minf")?.let { child(bytes, it, "stbl") } ?: return null
        val sampleEntry = child(bytes, stbl, "stsd")?.let { fourCc(bytes, it.dataStart + 12) } ?: return null
        val emptySampleBytes = when (sampleEntry) {
            "tx3g", "text" -> EMPTY_TX3G_SAMPLE_BYTES
            "wvtt" -> EMPTY_WVTT_SAMPLE_BYTES
            else -> return null // picture or TTML subtitles: lines can't be told from gaps by size
        }
        val durations = child(bytes, stbl, "stts")?.let { sampleDurations(bytes, it) } ?: return null
        val sizes = child(bytes, stbl, "stsz")?.let { sampleSizes(bytes, it, durations.size) } ?: return null
        val shiftMs = child(bytes, trak, "edts")?.let { child(bytes, it, "elst") }
            ?.let { editShiftMs(bytes, it, movieTimescale, timescale) } ?: 0L

        val cues = ArrayList<SubtitleSyncCue>()
        var time = 0L
        for (i in durations.indices) {
            val duration = durations[i]
            if (sizes[i] > emptySampleBytes && duration > 0L) {
                val start = time * 1000L / timescale + shiftMs
                val end = (time + duration) * 1000L / timescale + shiftMs
                if (start >= 0L && end > start) cues += SubtitleSyncCue(startTimeMs = start, endTimeMs = end, text = "")
            }
            time += duration
        }
        val trackId = child(bytes, trak, "tkhd")?.let { tkhd ->
            val tkhdVersion = bytes[tkhd.dataStart].toInt() and 0xFF
            u32(bytes, tkhd.dataStart + if (tkhdVersion == 1) 20 else 12).toInt()
        } ?: 0
        val name = child(bytes, trak, "udta")?.let { child(bytes, it, "name") }
            ?.let { cString(bytes, it.dataStart, it.end) }
        return Mp4SubtitleTrack(trackId, language, name, cues)
    }

    /** stts: runs of (sample count, duration). Expanded to one duration per sample. */
    private fun sampleDurations(bytes: ByteArray, stts: Box): LongArray? {
        val entries = u32(bytes, stts.dataStart + 4)
        var total = 0L
        for (i in 0 until entries) total += u32(bytes, stts.dataStart + 8 + (i * 8).toInt())
        if (total <= 0L || total > 2_000_000L) return null
        val out = LongArray(total.toInt())
        var index = 0
        for (i in 0 until entries) {
            val at = stts.dataStart + 8 + (i * 8).toInt()
            val count = u32(bytes, at).toInt()
            val delta = u32(bytes, at + 4)
            repeat(count) { out[index++] = delta }
        }
        return out
    }

    private fun sampleSizes(bytes: ByteArray, stsz: Box, sampleCount: Int): LongArray? {
        val fixedSize = u32(bytes, stsz.dataStart + 4)
        val count = u32(bytes, stsz.dataStart + 8).toInt()
        if (count != sampleCount) return null
        if (fixedSize != 0L) return LongArray(count) { fixedSize }
        return LongArray(count) { u32(bytes, stsz.dataStart + 12 + it * 4) }
    }

    /** Where the track's media starts on the movie timeline, from its first edits. */
    private fun editShiftMs(bytes: ByteArray, elst: Box, movieTimescale: Long?, mediaTimescale: Long): Long {
        val version = bytes[elst.dataStart].toInt() and 0xFF
        val entries = u32(bytes, elst.dataStart + 4)
        var shiftMs = 0L
        var at = elst.dataStart + 8
        for (i in 0 until minOf(entries, 2L)) {
            val segmentDuration = if (version == 1) u64(bytes, at) else u32(bytes, at)
            val mediaTime = if (version == 1) u64(bytes, at + 8) else u32(bytes, at + 4).toInt().toLong()
            at += if (version == 1) 20 else 12
            if (mediaTime == -1L) {
                // An empty edit delays the whole track.
                if (movieTimescale != null && movieTimescale > 0L) shiftMs += segmentDuration * 1000L / movieTimescale
            } else {
                shiftMs -= mediaTime * 1000L / mediaTimescale
                break
            }
        }
        return shiftMs
    }

    private fun parseMvhdTimescale(bytes: ByteArray, mvhd: Box): Long? {
        val version = bytes[mvhd.dataStart].toInt() and 0xFF
        return u32(bytes, mvhd.dataStart + if (version == 1) 20 else 12).takeIf { it > 0L }
    }

    /** ISO 639-2 code packed as three 5-bit letters; "und" means unknown. */
    private fun packedLanguage(packed: Int): String? {
        if (packed == 0 || packed == 0x7FFF) return null
        val code = buildString {
            append(((packed shr 10 and 0x1F) + 0x60).toChar())
            append(((packed shr 5 and 0x1F) + 0x60).toChar())
            append(((packed and 0x1F) + 0x60).toChar())
        }
        return code.takeIf { it.all { c -> c in 'a'..'z' } && it != "und" }
    }

    internal data class Box(val type: String, val start: Long, val size: Long, val headerSize: Int) {
        val dataStart: Int get() = (start + headerSize).toInt()
        val end: Int get() = (start + size).toInt()
    }

    private fun readBoxHeader(bytes: ByteArray, offset: Int, remaining: Long): Box? {
        if (offset < 0 || offset + 8 > bytes.size) return null
        val size32 = u32(bytes, offset)
        val type = fourCc(bytes, offset + 4) ?: return null
        return when (size32) {
            0L -> if (remaining == Long.MAX_VALUE) null else Box(type, offset.toLong(), remaining, 8)
            1L -> {
                if (offset + 16 > bytes.size) return null
                val size = u64(bytes, offset + 8)
                if (size < 16L) null else Box(type, offset.toLong(), size, 16)
            }
            else -> if (size32 < 8L) null else Box(type, offset.toLong(), size32, 8)
        }
    }

    private fun children(bytes: ByteArray, parent: Box): List<Box> {
        // Boxes with fields before their children.
        val skip = when (parent.type) {
            "stsd" -> 8
            else -> 0
        }
        val out = ArrayList<Box>()
        var at = parent.dataStart + skip
        val end = minOf(parent.end, bytes.size)
        while (at + 8 <= end) {
            val box = readBoxHeader(bytes, at, (end - at).toLong()) ?: break
            if (box.end > end) break
            out += box
            at = box.end
        }
        return out
    }

    private fun child(bytes: ByteArray, parent: Box, type: String): Box? =
        children(bytes, parent).firstOrNull { it.type == type }

    private fun fourCc(bytes: ByteArray, at: Int): String? {
        if (at < 0 || at + 4 > bytes.size) return null
        val chars = CharArray(4) { (bytes[at + it].toInt() and 0xFF).toChar() }
        return chars.concatToString().takeIf { s -> s.all { it.code in 0x20..0x7E } }
    }

    private fun cString(bytes: ByteArray, from: Int, end: Int): String? {
        if (from < 0 || from >= minOf(end, bytes.size)) return null
        var stop = from
        while (stop < minOf(end, bytes.size) && bytes[stop] != 0.toByte()) stop++
        return bytes.copyOfRange(from, stop).decodeToString().trim().ifEmpty { null }
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, at: Int): Long =
        (u16(bytes, at).toLong() shl 16) or u16(bytes, at + 2).toLong()

    private fun u64(bytes: ByteArray, at: Int): Long = (u32(bytes, at) shl 32) or u32(bytes, at + 4)
}
