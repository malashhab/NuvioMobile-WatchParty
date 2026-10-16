package com.nuvio.app.features.autosync.portable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * Reads each video's subtitle index once. Picking another subtitle, retrying or trying alternatives
 * all reuse it, instead of downloading up to 24 MB again while the video itself is streaming. Two
 * requests for the same video at once share one download. Reading happens off the main thread.
 */
internal object EmbeddedSubtitleIndexCache {
    private const val INDEX_TIMEOUT_MS = 15_000L
    private const val MAX_ENTRIES = 3

    // A video without a readable index is asked again after a while, in case the network failed.
    private const val RETRY_UNAVAILABLE_AFTER_MS = 120_000L

    private class Entry(val result: CompletableDeferred<EmbeddedSubtitleIndexResult>, val createdAt: TimeSource.Monotonic.ValueTimeMark) {
        var finished: EmbeddedSubtitleIndexResult? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun load(
        sourceUrl: String,
        headers: Map<String, String>,
        loader: suspend () -> EmbeddedSubtitleIndexResult = {
            withTimeoutOrNull(INDEX_TIMEOUT_MS) {
                // MKV first; anything else may be an MP4 (each format gets its own request budget).
                MatroskaSubtitleIndex.load(HttpByteRangeSource(sourceUrl, headers))
                    .takeUnless { it == EmbeddedSubtitleIndexResult.Unavailable }
                    ?: Mp4SubtitleIndex.load(HttpByteRangeSource(sourceUrl, headers))
            } ?: EmbeddedSubtitleIndexResult.Unavailable
        },
    ): EmbeddedSubtitleIndexResult {
        val key = sourceUrl + "#" + headers.hashCode()
        var owner = false
        val entry = lock.withLock {
            entries[key]?.takeUnless { it.isStale() }?.let { return@withLock it }
            Entry(CompletableDeferred(), TimeSource.Monotonic.markNow()).also {
                entries.remove(key)
                entries[key] = it
                while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
                owner = true
            }
        }
        // The download runs on its own: picking another subtitle meanwhile waits for it, not restarts it.
        if (owner) {
            scope.launch {
                try {
                    val result = loader()
                    entry.finished = result
                    entry.result.complete(result)
                } catch (e: Throwable) {
                    // Let the next request try again rather than remember a failed read.
                    lock.withLock { if (entries[key] === entry) entries.remove(key) }
                    entry.result.completeExceptionally(e)
                }
            }
        }
        return entry.result.await()
    }

    private fun Entry.isStale(): Boolean =
        finished == EmbeddedSubtitleIndexResult.Unavailable &&
            createdAt.elapsedNow().inWholeMilliseconds > RETRY_UNAVAILABLE_AFTER_MS

    internal suspend fun clear() = lock.withLock { entries.clear() }
}
