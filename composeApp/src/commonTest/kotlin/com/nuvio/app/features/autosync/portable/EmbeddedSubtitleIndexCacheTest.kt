package com.nuvio.app.features.autosync.portable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class EmbeddedSubtitleIndexCacheTest {
    private val found = EmbeddedSubtitleIndexResult.Found(emptyList())

    @Test
    fun aVideosIndexIsReadOnceAndReused() = runTest {
        EmbeddedSubtitleIndexCache.clear()
        var reads = 0
        val loader: suspend () -> EmbeddedSubtitleIndexResult = { reads++; found }

        repeat(3) { EmbeddedSubtitleIndexCache.load("https://example.com/a.mkv", emptyMap(), loader) }

        assertEquals(1, reads)
    }

    @Test
    fun aCancelledRequestDoesNotRestartTheDownload() = runTest {
        EmbeddedSubtitleIndexCache.clear()
        var reads = 0
        val release = CompletableDeferred<Unit>()
        val loader: suspend () -> EmbeddedSubtitleIndexResult = { reads++; release.await(); found }

        // The viewer picks a subtitle, then another one before the index has arrived.
        val first = async { EmbeddedSubtitleIndexCache.load("https://example.com/b.mkv", emptyMap(), loader) }
        yield()
        first.cancel()
        val second = async { EmbeddedSubtitleIndexCache.load("https://example.com/b.mkv", emptyMap(), loader) }
        release.complete(Unit)

        assertEquals(found, second.await())
        assertEquals(1, reads)
    }
}
