package com.nuvio.app.features.autosync.portable

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Mp4SubtitleIndexTest {
    @Test
    fun readsLineTimesFromTablesWithMoovAtTheEnd() = runTest {
        val file = mp4(moov(videoTrak(), subtitleTrak(id = 3, lines = 20)), mdatBytes = 200_000)
        val result = Mp4SubtitleIndex.load(ArraySource(file))
        val found = assertIs<EmbeddedSubtitleIndexResult.Found>(result)
        val track = found.tracks.single()
        assertEquals("mp4-stbl:3", track.key)
        assertEquals("eng", track.language)
        assertEquals(20, track.cues.size)
        // Each line is a 1 s gap then a 2 s line: lines start at 1 s, 4 s, 7 s…
        assertEquals(1_000L, track.cues[0].startTimeMs)
        assertEquals(3_000L, track.cues[0].endTimeMs)
        assertEquals(4_000L, track.cues[1].startTimeMs)
    }

    @Test
    fun readsMoovAtTheStart() = runTest {
        val file = mp4(moov(subtitleTrak(id = 2, lines = 12)), mdatBytes = 1_000, moovFirst = true)
        val found = assertIs<EmbeddedSubtitleIndexResult.Found>(Mp4SubtitleIndex.load(ArraySource(file)))
        assertEquals(12, found.tracks.single().cues.size)
    }

    @Test
    fun emptyEditDelaysTheTrack() {
        val result = Mp4SubtitleIndex.parseMoov(moov(subtitleTrak(id = 2, lines = 12, emptyEditMs = 500)))
        val track = assertIs<EmbeddedSubtitleIndexResult.Found>(result).tracks.single()
        assertEquals(1_500L, track.cues[0].startTimeMs)
    }

    @Test
    fun videoOnlyHasNoSubtitleTracks() {
        assertEquals(EmbeddedSubtitleIndexResult.NoSubtitleTracks, Mp4SubtitleIndex.parseMoov(moov(videoTrak())))
    }

    @Test
    fun notAnMp4IsUnavailable() = runTest {
        assertEquals(
            EmbeddedSubtitleIndexResult.Unavailable,
            Mp4SubtitleIndex.load(ArraySource(ByteArray(4096) { 0x1A })),
        )
        assertEquals(EmbeddedSubtitleIndexResult.Unavailable, Mp4SubtitleIndex.parseMoov(moov(subtitleTrak(2, 12)).copyOf(60)))
    }

    private class ArraySource(private val file: ByteArray) : ByteRangeSource {
        override suspend fun read(start: Long, length: Int, requirePartialContent: Boolean, requireExactLength: Boolean): ByteRange? {
            if (start >= file.size) return null
            val end = minOf(file.size.toLong(), start + length).toInt()
            if (requireExactLength && end - start.toInt() != length) return null
            return ByteRange(file.copyOfRange(start.toInt(), end), file.size.toLong())
        }
    }

    // --- A tiny MP4 writer, just enough boxes for the index ---

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { acc, p -> acc + p }
        return u32(8L + body.size) + type.encodeToByteArray() + body
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun zeros(n: Int) = ByteArray(n)

    private fun mp4(moov: ByteArray, mdatBytes: Int, moovFirst: Boolean = false): ByteArray {
        val ftyp = box("ftyp", "isom".encodeToByteArray(), u32(0), "isom".encodeToByteArray())
        val mdat = box("mdat", zeros(mdatBytes))
        return if (moovFirst) ftyp + moov + mdat else ftyp + mdat + moov
    }

    private fun moov(vararg traks: ByteArray) =
        box("moov", box("mvhd", zeros(12), u32(1000), zeros(80)), *traks)

    private fun mdhd(language: String?) = box(
        "mdhd", zeros(12), u32(1000), u32(0),
        u16(language?.fold(0) { acc, c -> (acc shl 5) or (c.code - 0x60) } ?: 0x55C4), u16(0),
    )

    private fun videoTrak() = box(
        "trak",
        box("tkhd", zeros(12), u32(1), zeros(68)),
        box("mdia", mdhd(null), box("hdlr", zeros(8), "vide".encodeToByteArray(), zeros(12)), box("minf")),
    )

    private fun subtitleTrak(id: Int, lines: Int, emptyEditMs: Int = 0): ByteArray {
        // Alternating samples: a 1 s empty gap (2 bytes), then a 2 s line (30 bytes).
        val count = lines * 2
        val stts = box("stts", zeros(4), u32(count.toLong()), *(0 until count).map { i ->
            u32(1) + u32(if (i % 2 == 0) 1000L else 2000L)
        }.toTypedArray())
        val stsz = box("stsz", zeros(4), u32(0), u32(count.toLong()), *(0 until count).map { i ->
            u32(if (i % 2 == 0) 2L else 30L)
        }.toTypedArray())
        val stsd = box("stsd", zeros(4), u32(1), box("tx3g", zeros(8)))
        val edts = if (emptyEditMs > 0) {
            box("edts", box("elst", zeros(4), u32(2), u32(emptyEditMs.toLong()), u32(0xFFFFFFFFL), u32(0x00010000), u32(0), u32(0), u32(0x00010000)))
        } else {
            ByteArray(0)
        }
        return box(
            "trak",
            box("tkhd", zeros(12), u32(id.toLong()), zeros(68)),
            edts,
            box(
                "mdia",
                mdhd("eng"),
                box("hdlr", zeros(8), "sbtl".encodeToByteArray(), zeros(12)),
                box("minf", box("stbl", stsd, stts, stsz)),
            ),
        )
    }
}
