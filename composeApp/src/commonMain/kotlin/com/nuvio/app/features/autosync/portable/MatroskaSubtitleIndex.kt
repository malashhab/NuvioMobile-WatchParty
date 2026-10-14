package com.nuvio.app.features.autosync.portable

import com.nuvio.app.features.player.SubtitleSyncCue

/** A piece of the video file, plus the file's full size when the server reported it. */
internal class ByteRange(val bytes: ByteArray, val totalLength: Long?)

/** Reads byte ranges of the video file. Returns null when the server can't serve that range. */
internal fun interface ByteRangeSource {
    suspend fun read(
        start: Long,
        length: Int,
        requirePartialContent: Boolean,
        requireExactLength: Boolean,
    ): ByteRange?
}

/** One subtitle track built into the video, with the times its lines appear. */
internal data class EmbeddedSubtitleReference(
    val key: String,
    val language: String?,
    val label: String,
    val cues: List<SubtitleSyncCue>,
    val forced: Boolean,
    val commentary: Boolean,
    val hearingImpaired: Boolean,
    val describesVideo: Boolean,
    /** Cue starts whose end time was guessed because the index had no duration. */
    val estimatedEndStartsMs: Set<Long>,
)

internal sealed interface EmbeddedSubtitleIndexResult {
    data class Found(val tracks: List<EmbeddedSubtitleReference>) : EmbeddedSubtitleIndexResult
    data object NoSubtitleTracks : EmbeddedSubtitleIndexResult
    /** Not a Matroska file, or its index couldn't be read. */
    data object Unavailable : EmbeddedSubtitleIndexResult
}

/**
 * Shared copy of the Matroska part of Android's EmbeddedSubtitleTimelineLoader, so iPhone can use it.
 *
 * Matroska files (MKV/WebM) keep an index ("Cues") that normally lists the time of every subtitle
 * line. Reading the file's header and that index with a few small range requests gives the exact
 * timing of each built-in subtitle track without downloading or playing the video.
 */
internal object MatroskaSubtitleIndex {
    private const val INITIAL_PROBE_BYTES = 512 * 1024
    private const val HEADER_PROBE_BYTES = 64
    private const val TAIL_PROBE_BYTES = 4 * 1024 * 1024
    private const val MAX_SEEK_HEAD_BYTES = 2 * 1024 * 1024
    private const val MAX_INFO_BYTES = 512 * 1024
    private const val MAX_TRACKS_BYTES = 4 * 1024 * 1024
    private const val MAX_CUES_BYTES = 8 * 1024 * 1024
    private const val MAX_SEEK_HEAD_HOPS = 4
    private const val DEFAULT_TIMESTAMP_SCALE_NS = 1_000_000L
    private const val LAST_MKV_CUE_ESTIMATED_DURATION_MS = 2_000L
    private const val MAX_MKV_INTER_CUE_ESTIMATED_DURATION_MS = 4_000L
    private const val MATROSKA_PGS_CODEC_ID = "S_HDMV/PGS"
    private const val MIN_INDEXED_CUES = 8
    private const val MIN_INDEXED_SPAN_MS = 30_000L

    // Top-level Matroska/EBML IDs.
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_CLUSTER = 0x1F43B675L

    // SeekHead.
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL

    // Info.
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L

    // Tracks.
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_FLAG_FORCED = 0x55AAL
    private const val ID_FLAG_HEARING_IMPAIRED = 0x55ABL
    private const val ID_FLAG_VISUAL_IMPAIRED = 0x55ACL
    private const val ID_FLAG_TEXT_DESCRIPTIONS = 0x55ADL
    private const val ID_FLAG_COMMENTARY = 0x55AFL
    private const val ID_NAME = 0x536EL
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_LANGUAGE_IETF = 0x22B59DL
    private const val ID_CODEC_ID = 0x86L
    private const val TRACK_TYPE_SUBTITLE = 17L

    // Cues.
    private const val ID_CUE_POINT = 0xBBL
    private const val ID_CUE_TIME = 0xB3L
    private const val ID_CUE_TRACK_POSITIONS = 0xB7L
    private const val ID_CUE_TRACK = 0xF7L
    private const val ID_CUE_DURATION = 0xB2L

    suspend fun load(source: ByteRangeSource): EmbeddedSubtitleIndexResult {
        val initial = source.read(
            start = 0L,
            length = INITIAL_PROBE_BYTES,
            requirePartialContent = false,
            requireExactLength = false,
        ) ?: return EmbeddedSubtitleIndexResult.Unavailable
        val initialBytes = initial.bytes
        val segment = findSegment(initialBytes) ?: return EmbeddedSubtitleIndexResult.Unavailable
        val segmentDataStart = segment.dataStart.toLong()
        val directPositions = findInitialTopLevelPositions(initialBytes, segment)

        // Resolve SeekHead chains. SeekPosition is relative to Segment payload start.
        val resolvedPositions = directPositions.toMutableMap()
        val seekHeadQueue = ArrayDeque<Long>()
        directPositions[ID_SEEK_HEAD]?.let(seekHeadQueue::addLast)
        val visitedSeekHeads = mutableSetOf<Long>()
        var seekHeadHops = 0
        while (seekHeadQueue.isNotEmpty() && seekHeadHops < MAX_SEEK_HEAD_HOPS) {
            val seekHeadPosition = seekHeadQueue.removeFirst()
            if (!visitedSeekHeads.add(seekHeadPosition)) continue
            seekHeadHops++
            val seekHeadBytes = element(source, initialBytes, seekHeadPosition, ID_SEEK_HEAD, MAX_SEEK_HEAD_BYTES)
                ?: continue
            parseSeekHead(seekHeadBytes).forEach { (id, relativePosition) ->
                val absolute = segmentDataStart + relativePosition
                if (id !in resolvedPositions) resolvedPositions[id] = absolute
                if (id == ID_SEEK_HEAD && absolute !in visitedSeekHeads) seekHeadQueue.addLast(absolute)
            }
        }

        val timestampScaleNs = resolvedPositions[ID_INFO]
            ?.let { element(source, initialBytes, it, ID_INFO, MAX_INFO_BYTES) }
            ?.let(::parseTimestampScaleNs)
            ?: DEFAULT_TIMESTAMP_SCALE_NS

        val tracksPosition = resolvedPositions[ID_TRACKS] ?: return EmbeddedSubtitleIndexResult.Unavailable
        val subtitleTracks = element(source, initialBytes, tracksPosition, ID_TRACKS, MAX_TRACKS_BYTES)
            ?.let(::parseSubtitleTracks)
            ?: return EmbeddedSubtitleIndexResult.Unavailable
        if (subtitleTracks.isEmpty()) return EmbeddedSubtitleIndexResult.NoSubtitleTracks

        val parsedCues = resolvedPositions[ID_CUES]
            ?.let { element(source, initialBytes, it, ID_CUES, MAX_CUES_BYTES) }
            ?.let { parseSubtitleCueTimelines(it, subtitleTracks, timestampScaleNs) }
            ?: findAndParseCuesNearFileEnd(source, initial.totalLength, subtitleTracks, timestampScaleNs)
            ?: return EmbeddedSubtitleIndexResult.Unavailable

        val references = subtitleTracks.mapNotNull { track ->
            val timeline = parsedCues[track.number] ?: return@mapNotNull null
            val cues = timeline.cues.sortedBy { it.startTimeMs }.distinctBy { it.startTimeMs }
            if (cues.size < MIN_INDEXED_CUES) return@mapNotNull null
            if (cues.last().startTimeMs - cues.first().startTimeMs < MIN_INDEXED_SPAN_MS) return@mapNotNull null
            EmbeddedSubtitleReference(
                key = "mkv-cues:${track.number}",
                language = track.languageIetf?.takeIf { it.isNotBlank() }
                    ?: track.language?.takeIf { it.isNotBlank() },
                label = track.name?.takeIf { it.isNotBlank() } ?: fallbackTrackLabel(track),
                cues = cues,
                forced = track.forced,
                commentary = track.commentary,
                hearingImpaired = track.hearingImpaired,
                describesVideo = track.visualImpaired || track.textDescriptions,
                estimatedEndStartsMs = timeline.estimatedEndStartsMs,
            )
        }
        return if (references.isEmpty()) {
            EmbeddedSubtitleIndexResult.Unavailable
        } else {
            EmbeddedSubtitleIndexResult.Found(references)
        }
    }

    /** Uses the first probe when it already holds the whole element, and fetches it otherwise. */
    private suspend fun element(
        source: ByteRangeSource,
        initialBytes: ByteArray,
        absolutePosition: Long,
        expectedId: Long,
        maxElementBytes: Int,
    ): ByteArray? =
        extractElementFromInitialProbe(initialBytes, absolutePosition, expectedId, maxElementBytes)
            ?: fetchElementAt(source, absolutePosition, expectedId, maxElementBytes)

    private suspend fun findAndParseCuesNearFileEnd(
        source: ByteRangeSource,
        totalLength: Long?,
        subtitleTracks: List<MatroskaSubtitleTrack>,
        timestampScaleNs: Long,
    ): Map<Int, IndexedSubtitleTimeline>? {
        val fileLength = totalLength?.takeIf { it > 0L } ?: return null
        val start = maxOf(0L, fileLength - TAIL_PROBE_BYTES)
        if (start == 0L) return null
        val tailLength = (fileLength - start).coerceAtMost(TAIL_PROBE_BYTES.toLong()).toInt()
        val tail = source.read(start, tailLength, requirePartialContent = true, requireExactLength = false)
            ?: return null
        for (relativeOffset in findElementIdOffsets(tail.bytes, ID_CUES).asReversed()) {
            val cuesBytes = fetchElementAt(source, start + relativeOffset, ID_CUES, MAX_CUES_BYTES) ?: continue
            val parsed = parseSubtitleCueTimelines(cuesBytes, subtitleTracks, timestampScaleNs)
            if (parsed.values.any { it.cues.size >= MIN_INDEXED_CUES }) return parsed
        }
        return null
    }

    internal fun findSegment(bytes: ByteArray): EbmlElement? {
        var position = 0
        var elementCount = 0
        while (position < bytes.size && elementCount++ < 32) {
            val element = readElement(bytes, position, bytes.size) ?: return null
            if (element.id == ID_SEGMENT) return element
            position = element.endWithin(bytes.size) ?: return null
        }
        return null
    }

    private fun findInitialTopLevelPositions(bytes: ByteArray, segment: EbmlElement): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        var position = segment.dataStart
        var elementCount = 0
        while (position < bytes.size && elementCount++ < 128) {
            val element = readElement(bytes, position, bytes.size) ?: break
            when (element.id) {
                ID_SEEK_HEAD, ID_INFO, ID_TRACKS, ID_CUES ->
                    if (element.id !in result) result[element.id] = position.toLong()
                ID_CLUSTER -> break
            }
            position = element.endWithin(bytes.size) ?: break
        }
        return result
    }

    private fun parseSeekHead(seekHeadElement: ByteArray): Map<Long, Long> {
        val root = readElement(seekHeadElement, 0, seekHeadElement.size) ?: return emptyMap()
        if (root.id != ID_SEEK_HEAD) return emptyMap()
        val rootEnd = root.endWithin(seekHeadElement.size) ?: return emptyMap()
        val result = mutableMapOf<Long, Long>()
        forEachChild(seekHeadElement, root.dataStart, rootEnd) { seek ->
            if (seek.id != ID_SEEK) return@forEachChild
            val seekEnd = seek.endWithin(rootEnd) ?: return@forEachChild
            var targetId: Long? = null
            var position: Long? = null
            forEachChild(seekHeadElement, seek.dataStart, seekEnd) { child ->
                when (child.id) {
                    ID_SEEK_ID -> targetId = readBinaryId(seekHeadElement, child)
                    ID_SEEK_POSITION -> position = readUnsigned(seekHeadElement, child)
                }
            }
            val id = targetId
            val relativePosition = position
            if (id != null && relativePosition != null && id !in result) result[id] = relativePosition
        }
        return result
    }

    private fun parseTimestampScaleNs(infoElement: ByteArray): Long {
        val root = readElement(infoElement, 0, infoElement.size) ?: return DEFAULT_TIMESTAMP_SCALE_NS
        if (root.id != ID_INFO) return DEFAULT_TIMESTAMP_SCALE_NS
        val rootEnd = root.endWithin(infoElement.size) ?: return DEFAULT_TIMESTAMP_SCALE_NS
        var scale = DEFAULT_TIMESTAMP_SCALE_NS
        forEachChild(infoElement, root.dataStart, rootEnd) { child ->
            if (child.id == ID_TIMESTAMP_SCALE) {
                readUnsigned(infoElement, child)?.takeIf { it > 0L }?.let { scale = it }
            }
        }
        return scale
    }

    private fun parseSubtitleTracks(tracksElement: ByteArray): List<MatroskaSubtitleTrack>? {
        val root = readElement(tracksElement, 0, tracksElement.size) ?: return null
        if (root.id != ID_TRACKS) return null
        val rootEnd = root.endWithin(tracksElement.size) ?: return null
        val result = mutableListOf<MatroskaSubtitleTrack>()
        forEachChild(tracksElement, root.dataStart, rootEnd) { entry ->
            if (entry.id != ID_TRACK_ENTRY) return@forEachChild
            val entryEnd = entry.endWithin(rootEnd) ?: return@forEachChild
            var number: Int? = null
            var type: Long? = null
            var name: String? = null
            var language: String? = null
            var languageIetf: String? = null
            var codecId: String? = null
            var forced = false
            var hearingImpaired = false
            var visualImpaired = false
            var textDescriptions = false
            var commentary = false
            forEachChild(tracksElement, entry.dataStart, entryEnd) { child ->
                when (child.id) {
                    ID_TRACK_NUMBER -> number = readUnsigned(tracksElement, child)?.toInt()
                    ID_TRACK_TYPE -> type = readUnsigned(tracksElement, child)
                    ID_NAME -> name = readUtf8(tracksElement, child)
                    ID_LANGUAGE -> language = readUtf8(tracksElement, child)
                    ID_LANGUAGE_IETF -> languageIetf = readUtf8(tracksElement, child)
                    ID_CODEC_ID -> codecId = readUtf8(tracksElement, child)
                    ID_FLAG_FORCED -> forced = readUnsigned(tracksElement, child) == 1L
                    ID_FLAG_HEARING_IMPAIRED -> hearingImpaired = readUnsigned(tracksElement, child) == 1L
                    ID_FLAG_VISUAL_IMPAIRED -> visualImpaired = readUnsigned(tracksElement, child) == 1L
                    ID_FLAG_TEXT_DESCRIPTIONS -> textDescriptions = readUnsigned(tracksElement, child) == 1L
                    ID_FLAG_COMMENTARY -> commentary = readUnsigned(tracksElement, child) == 1L
                }
            }
            val trackNumber = number
            if (trackNumber != null && type == TRACK_TYPE_SUBTITLE) {
                result += MatroskaSubtitleTrack(
                    number = trackNumber,
                    name = name,
                    language = language,
                    languageIetf = languageIetf,
                    codecId = codecId,
                    forced = forced,
                    hearingImpaired = hearingImpaired,
                    visualImpaired = visualImpaired,
                    textDescriptions = textDescriptions,
                    commentary = commentary,
                )
            }
        }
        return result
    }

    private fun parseSubtitleCueTimelines(
        cuesElement: ByteArray,
        subtitleTracks: List<MatroskaSubtitleTrack>,
        timestampScaleNs: Long,
    ): Map<Int, IndexedSubtitleTimeline> {
        val root = readElement(cuesElement, 0, cuesElement.size) ?: return emptyMap()
        if (root.id != ID_CUES) return emptyMap()
        val rootEnd = root.endWithin(cuesElement.size) ?: return emptyMap()
        val subtitleTrackNumbers = subtitleTracks.mapTo(mutableSetOf()) { it.number }
        val pendingByTrack = subtitleTrackNumbers.associateWith { mutableListOf<PendingIndexedCue>() }

        forEachChild(cuesElement, root.dataStart, rootEnd) { cuePoint ->
            if (cuePoint.id != ID_CUE_POINT) return@forEachChild
            val pointEnd = cuePoint.endWithin(rootEnd) ?: return@forEachChild
            var cueTimeTicks: Long? = null
            val positions = mutableListOf<CueTrackPosition>()
            forEachChild(cuesElement, cuePoint.dataStart, pointEnd) { child ->
                when (child.id) {
                    ID_CUE_TIME -> cueTimeTicks = readUnsigned(cuesElement, child)
                    ID_CUE_TRACK_POSITIONS -> {
                        val positionEnd = child.endWithin(pointEnd)
                        if (positionEnd != null) {
                            var trackNumber: Int? = null
                            var durationTicks: Long? = null
                            forEachChild(cuesElement, child.dataStart, positionEnd) { positionChild ->
                                when (positionChild.id) {
                                    ID_CUE_TRACK -> trackNumber = readUnsigned(cuesElement, positionChild)?.toInt()
                                    ID_CUE_DURATION -> durationTicks = readUnsigned(cuesElement, positionChild)
                                }
                            }
                            trackNumber?.let { positions += CueTrackPosition(it, durationTicks) }
                        }
                    }
                }
            }
            val startMs = cueTimeTicks?.let { ticksToMs(it, timestampScaleNs) } ?: return@forEachChild
            positions.forEach { position ->
                if (position.trackNumber !in subtitleTrackNumbers) return@forEach
                val durationMs = position.durationTicks
                    ?.let { ticksToMs(it, timestampScaleNs) }
                    ?.takeIf { it > 0L }
                pendingByTrack[position.trackNumber]?.add(PendingIndexedCue(startMs, durationMs))
            }
        }

        val tracksByNumber = subtitleTracks.associateBy { it.number }
        val peerDialogueCueCounts = subtitleTracks
            .filter { !it.isPgs && !it.forced && !it.commentary }
            .mapNotNull { track -> pendingByTrack[track.number]?.size?.takeIf { it >= MIN_INDEXED_CUES } }
            .sorted()
        val peerDialogueCueCount = peerDialogueCueCounts.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

        return pendingByTrack.mapValues { (trackNumber, pending) ->
            val sorted = pending.sortedBy { it.startTimeMs }.distinctBy { it.startTimeMs }
            if (tracksByNumber[trackNumber]?.isPgs == true) {
                buildPgsIndexedTimeline(sorted, peerDialogueCueCount)
            } else {
                buildDefaultIndexedTimeline(sorted)
            }
        }
    }

    /**
     * Picture subtitles (PGS) usually index both the moment a line appears and the moment it is
     * cleared. Pair those into one line only when the other tracks' line counts support it.
     */
    private fun buildPgsIndexedTimeline(
        sorted: List<PendingIndexedCue>,
        peerDialogueCueCount: Int?,
    ): IndexedSubtitleTimeline {
        if (sorted.size < 2) return IndexedSubtitleTimeline(emptyList(), emptySet())
        val pairedCount = sorted.size / 2
        val explicitDurationCount = sorted.count { (it.explicitDurationMs ?: 0L) > 0L }
        val shouldPair = if (peerDialogueCueCount != null) {
            val rawDelta = kotlin.math.abs(sorted.size - peerDialogueCueCount)
            val pairedDelta = kotlin.math.abs(pairedCount - peerDialogueCueCount)
            pairedDelta.toLong() * 3L <= rawDelta.toLong() * 2L
        } else {
            explicitDurationCount.toLong() * 4L < sorted.size.toLong() * 3L
        }
        if (!shouldPair) return buildDefaultIndexedTimeline(sorted)

        val cues = ArrayList<SubtitleSyncCue>(pairedCount)
        var index = 0
        while (index + 1 < sorted.size) {
            val display = sorted[index]
            val clear = sorted[index + 1]
            if (clear.startTimeMs > display.startTimeMs) {
                val explicitEndMs = display.explicitDurationMs
                    ?.takeIf { it > 0L && display.startTimeMs <= Long.MAX_VALUE - it }
                    ?.let { display.startTimeMs + it }
                val endTimeMs = explicitEndMs?.coerceAtMost(clear.startTimeMs) ?: clear.startTimeMs
                if (endTimeMs > display.startTimeMs) {
                    cues += SubtitleSyncCue(startTimeMs = display.startTimeMs, endTimeMs = endTimeMs, text = "")
                }
            }
            index += 2
        }
        return IndexedSubtitleTimeline(cues, emptySet())
    }

    private fun buildDefaultIndexedTimeline(sorted: List<PendingIndexedCue>): IndexedSubtitleTimeline {
        val estimatedEndStartsMs = HashSet<Long>()
        val cues = sorted.mapIndexed { index, cue ->
            val durationMs = cue.explicitDurationMs ?: run {
                estimatedEndStartsMs += cue.startTimeMs
                val nextStartMs = sorted.getOrNull(index + 1)?.startTimeMs
                if (nextStartMs != null && nextStartMs > cue.startTimeMs) {
                    (nextStartMs - cue.startTimeMs)
                        .coerceAtMost(MAX_MKV_INTER_CUE_ESTIMATED_DURATION_MS)
                        .coerceAtLeast(1L)
                } else {
                    LAST_MKV_CUE_ESTIMATED_DURATION_MS
                }
            }
            SubtitleSyncCue(startTimeMs = cue.startTimeMs, endTimeMs = cue.startTimeMs + durationMs, text = "")
        }
        return IndexedSubtitleTimeline(cues, estimatedEndStartsMs)
    }

    private fun fallbackTrackLabel(track: MatroskaSubtitleTrack): String {
        val language = track.languageIetf?.takeIf { it.isNotBlank() }
            ?: track.language?.takeIf { it.isNotBlank() }
            ?: "Subtitle"
        val suffix = when {
            track.commentary -> " [Commentary]"
            track.forced -> " [Forced]"
            track.hearingImpaired -> " [SDH]"
            else -> " [Full]"
        }
        return language + suffix
    }

    private fun ticksToMs(ticks: Long, timestampScaleNs: Long): Long? {
        if (ticks < 0L || timestampScaleNs <= 0L) return null
        if (ticks > Long.MAX_VALUE / timestampScaleNs) {
            return ((ticks.toDouble() * timestampScaleNs.toDouble()) / 1_000_000.0)
                .takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble() }
                ?.toLong()
        }
        return ticks * timestampScaleNs / 1_000_000L
    }

    private fun extractElementFromInitialProbe(
        initialBytes: ByteArray,
        absolutePosition: Long,
        expectedId: Long,
        maxElementBytes: Int,
    ): ByteArray? {
        if (absolutePosition < 0L || absolutePosition >= initialBytes.size) return null
        val start = absolutePosition.toInt()
        val header = readElement(initialBytes, start, initialBytes.size) ?: return null
        val size = header.size ?: return null
        if (header.id != expectedId) return null
        val totalSize = header.headerSize.toLong() + size
        if (totalSize <= 0L || totalSize > maxElementBytes.toLong()) return null
        val end = start.toLong() + totalSize
        if (end > initialBytes.size.toLong()) return null
        return initialBytes.copyOfRange(start, end.toInt())
    }

    private suspend fun fetchElementAt(
        source: ByteRangeSource,
        absolutePosition: Long,
        expectedId: Long,
        maxElementBytes: Int,
    ): ByteArray? {
        if (absolutePosition < 0L) return null
        val headerProbe = source.read(
            start = absolutePosition,
            length = HEADER_PROBE_BYTES,
            requirePartialContent = absolutePosition > 0L,
            requireExactLength = false,
        ) ?: return null
        val header = readElement(headerProbe.bytes, 0, headerProbe.bytes.size) ?: return null
        val size = header.size ?: return null
        if (header.id != expectedId) return null
        val totalSize = header.headerSize.toLong() + size
        if (totalSize <= 0L || totalSize > maxElementBytes.toLong()) return null
        if (totalSize <= headerProbe.bytes.size) return headerProbe.bytes.copyOf(totalSize.toInt())
        return source.read(
            start = absolutePosition,
            length = totalSize.toInt(),
            requirePartialContent = absolutePosition > 0L,
            requireExactLength = true,
        )?.bytes
    }

    private fun findElementIdOffsets(bytes: ByteArray, id: Long): List<Int> {
        val idBytes = elementIdBytes(id)
        if (bytes.size < idBytes.size) return emptyList()
        val result = mutableListOf<Int>()
        outer@ for (index in 0..bytes.size - idBytes.size) {
            for (offset in idBytes.indices) {
                if (bytes[index + offset] != idBytes[offset]) continue@outer
            }
            val parsed = readElement(bytes, index, bytes.size)
            if (parsed?.id == id && parsed.size != null) result += index
        }
        return result
    }

    private fun elementIdBytes(id: Long): ByteArray {
        var length = 1
        while (length < 8 && id >= (1L shl (length * 8))) length++
        return ByteArray(length) { index -> ((id shr ((length - index - 1) * 8)) and 0xFF).toByte() }
    }

    private inline fun forEachChild(
        bytes: ByteArray,
        start: Int,
        endExclusive: Int,
        action: (EbmlElement) -> Unit,
    ) {
        var position = start
        var count = 0
        while (position < endExclusive && count++ < 1_000_000) {
            val child = readElement(bytes, position, endExclusive) ?: break
            val end = child.endWithin(endExclusive) ?: break
            action(child)
            if (end <= position) break
            position = end
        }
    }

    internal fun readElement(bytes: ByteArray, offset: Int, limit: Int): EbmlElement? {
        if (offset < 0 || offset >= limit || limit > bytes.size) return null
        val idLength = vintLength(bytes[offset].toInt() and 0xFF) ?: return null
        if (idLength > 4 || offset + idLength >= limit) return null
        var id = 0L
        for (index in 0 until idLength) {
            id = (id shl 8) or (bytes[offset + index].toLong() and 0xFFL)
        }
        val sizeOffset = offset + idLength
        val sizeLength = vintLength(bytes[sizeOffset].toInt() and 0xFF) ?: return null
        if (sizeLength > 8 || sizeOffset + sizeLength > limit) return null
        val markerMask = 1 shl (8 - sizeLength)
        var sizeValue = (bytes[sizeOffset].toInt() and (markerMask - 1)).toLong()
        for (index in 1 until sizeLength) {
            sizeValue = (sizeValue shl 8) or (bytes[sizeOffset + index].toLong() and 0xFFL)
        }
        val unknownValue = (1L shl (7 * sizeLength)) - 1L
        val dataStart = sizeOffset + sizeLength
        return EbmlElement(
            id = id,
            size = if (sizeValue == unknownValue) null else sizeValue,
            dataStart = dataStart,
            headerSize = dataStart - offset,
        )
    }

    private fun vintLength(firstByte: Int): Int? {
        if (firstByte == 0) return null
        var mask = 0x80
        var length = 1
        while ((firstByte and mask) == 0) {
            mask = mask ushr 1
            length++
            if (length > 8) return null
        }
        return length
    }

    private fun readUnsigned(bytes: ByteArray, element: EbmlElement): Long? {
        val size = element.size ?: return null
        if (size !in 1L..8L) return null
        val end = element.dataStart + size.toInt()
        if (end > bytes.size) return null
        var value = 0L
        for (index in element.dataStart until end) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFFL)
        }
        return value
    }

    private fun readBinaryId(bytes: ByteArray, element: EbmlElement): Long? {
        val size = element.size ?: return null
        if (size !in 1L..4L) return null
        return readUnsigned(bytes, element)
    }

    private fun readUtf8(bytes: ByteArray, element: EbmlElement): String? {
        val size = element.size ?: return null
        if (size < 0L || size > Int.MAX_VALUE) return null
        val end = element.dataStart + size.toInt()
        if (end > bytes.size) return null
        return bytes.copyOfRange(element.dataStart, end).decodeToString().trimEnd('\u0000')
    }

    internal class EbmlElement(
        val id: Long,
        val size: Long?,
        val dataStart: Int,
        val headerSize: Int,
    ) {
        fun endWithin(limit: Int): Int? {
            val contentSize = size ?: return null
            if (contentSize < 0L || contentSize > Int.MAX_VALUE) return null
            val end = dataStart.toLong() + contentSize
            if (end > limit.toLong()) return null
            return end.toInt()
        }
    }

    private class MatroskaSubtitleTrack(
        val number: Int,
        val name: String?,
        val language: String?,
        val languageIetf: String?,
        val codecId: String?,
        val forced: Boolean,
        val hearingImpaired: Boolean,
        val visualImpaired: Boolean,
        val textDescriptions: Boolean,
        val commentary: Boolean,
    ) {
        val isPgs: Boolean
            get() = codecId.equals(MATROSKA_PGS_CODEC_ID, ignoreCase = true)
    }

    private class CueTrackPosition(val trackNumber: Int, val durationTicks: Long?)

    private class PendingIndexedCue(val startTimeMs: Long, val explicitDurationMs: Long?)

    private class IndexedSubtitleTimeline(val cues: List<SubtitleSyncCue>, val estimatedEndStartsMs: Set<Long>)
}
