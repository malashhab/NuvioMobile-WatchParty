package com.nuvio.app.features.autosync.portable

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.autosync.AutoSyncSubtitleCandidate
import com.nuvio.app.features.autosync.AutoSyncTimelineRetimeResult
import com.nuvio.app.features.autosync.AutoSyncTimelineRetimer
import com.nuvio.app.features.autosync.maxAlignmentShiftMs
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleLanguageMatching
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How to show a subtitle in time: each line at `speed × its own time + delay`. Most fixes are a
 * plain delay; a speed other than 1 corrects subtitles made for a different frame rate.
 */
internal data class SubtitleTimingFix(
    val subtitleUrl: String,
    val delayMs: Int,
    val speed: Double,
)

internal sealed interface PortableAutoSyncResult {
    data class Fixed(
        val fix: SubtitleTimingFix,
        /** The built-in track it was matched against, so "try another reference" can skip it. */
        val referenceKey: String,
        /** More built-in tracks that could still be tried. */
        val moreReferences: Boolean,
    ) : PortableAutoSyncResult
    /** The video has no readable built-in subtitles to compare against. */
    data object NoReference : PortableAutoSyncResult
    /** The subtitle couldn't be downloaded or read. */
    data object SubtitleUnavailable : PortableAutoSyncResult
    /** Nothing matched the built-in subtitles closely enough to trust. */
    data object NoMatch : PortableAutoSyncResult
}

/**
 * Subtitle auto-sync for players that can shift and stretch subtitle timing themselves (the iPhone
 * player). It compares the chosen subtitle with the subtitles built into the video, using the same
 * matching as Android, and answers with a delay and speed instead of rewritten subtitle lines.
 * When the chosen subtitle doesn't fit, other subtitles in the same language are tried.
 */
internal object PortableSubtitleAutoSync {
    private const val INDEX_TIMEOUT_MS = 15_000L
    private const val SUBTITLE_TIMEOUT_MS = 15_000L
    private const val MAX_SUBTITLE_BYTES = 4 * 1024 * 1024
    private const val MAX_REFERENCES_TRIED = 3
    private const val MAX_ALTERNATIVES_TRIED = 8
    private const val MIN_TARGET_CUES = 8

    // A stretch this small (under 0.03%) is less than 2 seconds over a long film; treat it as none.
    private const val SPEED_EPSILON = 0.0003
    private const val MAX_DELAY_MS = 60_000

    suspend fun run(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        selectedUrl: String,
        selectedLanguage: String?,
        subtitleHeaders: (String) -> Map<String, String>,
        alternatives: () -> List<AutoSyncSubtitleCandidate>,
        toleranceMs: Int,
        /** Built-in tracks already rejected by the viewer ("try another reference"). */
        excludedReferenceKeys: Set<String> = emptySet(),
        loadIndex: suspend () -> EmbeddedSubtitleIndexResult = {
            withTimeoutOrNull(INDEX_TIMEOUT_MS) {
                MatroskaSubtitleIndex.load(HttpByteRangeSource(sourceUrl, sourceHeaders))
            } ?: EmbeddedSubtitleIndexResult.Unavailable
        },
        loadSubtitle: suspend (url: String, headers: Map<String, String>) -> String? = ::downloadSubtitle,
    ): PortableAutoSyncResult = coroutineScope {
        val indexDeferred = async { loadIndex() }
        val selectedDeferred = async { loadCues(selectedUrl, subtitleHeaders(selectedUrl), loadSubtitle) }

        val references = when (val index = indexDeferred.await()) {
            is EmbeddedSubtitleIndexResult.Found ->
                rankReferences(index.tracks.filter { it.key !in excludedReferenceKeys }, selectedLanguage, limit = Int.MAX_VALUE)
            else -> emptyList()
        }
        val fixed = { url: String, match: ReferenceMatch ->
            toFix(url, match.result, toleranceMs)?.let { fix ->
                PortableAutoSyncResult.Fixed(
                    fix = fix,
                    referenceKey = match.reference.key,
                    moreReferences = references.size > 1,
                )
            }
        }
        if (references.isEmpty()) {
            selectedDeferred.cancel()
            return@coroutineScope PortableAutoSyncResult.NoReference
        }

        val selectedCues = selectedDeferred.await()
        if (selectedCues != null) {
            val result = match(references, selectedCues)?.let { fixed(selectedUrl, it) }
            if (result != null) return@coroutineScope result
        }

        val language = selectedLanguage?.takeIf { it.isNotBlank() }
        val others = alternatives()
            .asSequence()
            .filter { it.url.isNotBlank() && it.url != selectedUrl }
            .filter { language == null || SubtitleLanguageMatching.matchesLanguageCode(it.language, language) }
            .distinctBy { it.url }
            .take(MAX_ALTERNATIVES_TRIED)
            .toList()
        for (candidate in others) {
            val cues = loadCues(candidate.url, subtitleHeaders(candidate.url), loadSubtitle) ?: continue
            val result = match(references, cues)?.let { fixed(candidate.url, it) } ?: continue
            return@coroutineScope result
        }
        if (selectedCues == null && others.isEmpty()) {
            PortableAutoSyncResult.SubtitleUnavailable
        } else {
            PortableAutoSyncResult.NoMatch
        }
    }

    /** Built-in tracks worth comparing against, best first: same language, then the fullest. */
    internal fun rankReferences(
        tracks: List<EmbeddedSubtitleReference>,
        language: String?,
        limit: Int = MAX_REFERENCES_TRIED,
    ): List<EmbeddedSubtitleReference> {
        val usable = tracks.filter { !it.forced && !it.commentary && !it.describesVideo }
            .ifEmpty { tracks.filter { !it.commentary } }
        val target = language?.takeIf { it.isNotBlank() }
        return usable
            .sortedWith(
                compareByDescending<EmbeddedSubtitleReference> {
                    target != null && SubtitleLanguageMatching.matchesLanguageCode(it.language, target)
                }.thenByDescending { it.cues.size },
            )
            .take(limit)
    }

    private class ReferenceMatch(val reference: EmbeddedSubtitleReference, val result: AutoSyncTimelineRetimeResult)

    private suspend fun match(
        references: List<EmbeddedSubtitleReference>,
        target: List<SubtitleSyncCue>,
    ): ReferenceMatch? = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        val targetActivity = AutoSyncTimelineRetimer.prepareUnitActivity(target)
        // The best few are enough; more rarely help and each one costs a full comparison.
        for (reference in references.take(MAX_REFERENCES_TRIED)) {
            context.ensureActive()
            val result = AutoSyncTimelineRetimer.retime(
                reference = reference.cues,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                allowAmbiguousDelayOnlyMargin = AutoSyncTimelineRetimer.shouldRelaxDelayOnlyMargin(
                    sdhReference = reference.hearingImpaired,
                    referenceSize = reference.cues.size,
                    targetSize = target.size,
                ),
                referenceEstimatedEndStartsMs = reference.estimatedEndStartsMs,
                preparedTargetActivity = targetActivity,
                cancellationCheck = { context.ensureActive() },
            )
            if (result != null && result.confident) return@withContext ReferenceMatch(reference, result)
        }
        null
    }

    /** Turns the matched line times into one delay and speed, or null when that can't be shown. */
    internal fun toFix(url: String, result: AutoSyncTimelineRetimeResult, toleranceMs: Int): SubtitleTimingFix? {
        if (toleranceMs > 0 && result.maxAlignmentShiftMs() <= toleranceMs) {
            return SubtitleTimingFix(url, delayMs = 0, speed = 1.0)
        }
        val (speed, delayMs) = fitDelayAndSpeed(result) ?: return null
        val delay = delayMs.roundToInt()
        if (abs(delay) > MAX_DELAY_MS) return null
        return SubtitleTimingFix(url, delayMs = delay, speed = speed)
    }

    /**
     * Fits `new = speed × old + delay` through the matched lines (least squares on start times).
     * Android rewrites every line instead; a straight-line fit is what the iPhone player can apply.
     */
    internal fun fitDelayAndSpeed(result: AutoSyncTimelineRetimeResult): Pair<Double, Double>? {
        val cues = result.cues.filter { it.startTimeMs >= 0L }
        if (cues.isEmpty()) return null
        val shifts = cues.map { (it.startTimeMs - it.originalStartTimeMs).toDouble() }.sorted()
        val medianShift = shifts[shifts.size / 2]
        if (cues.size < 2) return 1.0 to medianShift

        val n = cues.size.toDouble()
        val meanX = cues.sumOf { it.originalStartTimeMs.toDouble() } / n
        val meanY = cues.sumOf { it.startTimeMs.toDouble() } / n
        var covariance = 0.0
        var variance = 0.0
        cues.forEach {
            val dx = it.originalStartTimeMs - meanX
            covariance += dx * (it.startTimeMs - meanY)
            variance += dx * dx
        }
        if (variance <= 0.0) return 1.0 to medianShift
        val speed = covariance / variance
        if (!speed.isFinite() || speed !in 0.8..1.25) return null
        if (abs(speed - 1.0) < SPEED_EPSILON) return 1.0 to medianShift
        return speed to (meanY - speed * meanX)
    }

    private suspend fun loadCues(
        url: String,
        headers: Map<String, String>,
        loadSubtitle: suspend (String, Map<String, String>) -> String?,
    ): List<SubtitleSyncCue>? {
        val text = try {
            withTimeoutOrNull(SUBTITLE_TIMEOUT_MS) { loadSubtitle(url, headers) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } ?: return null
        return withContext(Dispatchers.Default) {
            try {
                AutoSyncTimelineRetimer.normalizeExternalTimeline(PlayerSubtitleCueParser.parse(text = text, sourceUrl = url))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptyList()
            }
        }.takeIf { it.size >= MIN_TARGET_CUES }
    }

    private suspend fun downloadSubtitle(url: String, headers: Map<String, String>): String? {
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = headers,
            body = "",
            followRedirects = true,
            maxResponseBodyBytes = MAX_SUBTITLE_BYTES,
        )
        return response.body.takeIf { response.status in 200..299 && it.isNotBlank() }
    }
}
