package com.nuvio.app.features.autosync.portable

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable

/**
 * Reads parts of a video over HTTP with Range requests, within a total byte and request budget.
 * A response is read only up to the requested length, so a server that ignores Range and starts
 * sending the whole file costs no more than the bytes asked for.
 */
internal class HttpByteRangeSource(
    private val url: String,
    headers: Map<String, String>,
    private val maxTotalBytes: Long = MAX_TOTAL_DOWNLOAD_BYTES,
    private val maxRequests: Int = MAX_RANGE_REQUESTS,
) : ByteRangeSource {
    private val requestHeaders = headers.filterKeys { name ->
        ignoredHeaders.none { it.equals(name, ignoreCase = true) }
    }

    var requests = 0
        private set
    var bytesDownloaded = 0L
        private set

    override suspend fun read(
        start: Long,
        length: Int,
        requirePartialContent: Boolean,
        requireExactLength: Boolean,
    ): ByteRange? {
        if (length <= 0 || start < 0L || requests >= maxRequests) return null
        if (length.toLong() > maxTotalBytes - bytesDownloaded) return null
        requests++
        return client.prepareGet(url) {
            requestHeaders.forEach { (name, value) -> header(name, value) }
            header("Range", "bytes=$start-${start + length - 1}")
            header("Accept-Encoding", "identity")
        }.execute { response ->
            val status = response.status.value
            if (status !in 200..299) return@execute null
            // Anything but 206 means the server ignored Range and is sending from the beginning.
            if ((requirePartialContent || start > 0L) && status != 206) return@execute null
            val contentRange = parseContentRange(response.headers["Content-Range"])
            if (status == 206 && contentRange?.start != start) return@execute null

            val channel = response.bodyAsChannel()
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = channel.readAvailable(bytes, offset, length - offset)
                if (read < 0) break
                offset += read
            }
            bytesDownloaded += offset
            if (offset == 0 || (requireExactLength && offset != length)) return@execute null
            val totalLength = contentRange?.total
                ?: if (status == 200) response.headers["Content-Length"]?.toLongOrNull() else null
            ByteRange(if (offset == length) bytes else bytes.copyOf(offset), totalLength)
        }
    }

    private class ContentRange(val start: Long?, val total: Long?)

    private fun parseContentRange(value: String?): ContentRange? {
        val trimmed = value?.trim()?.takeIf { it.startsWith("bytes ", ignoreCase = true) } ?: return null
        val rangeAndTotal = trimmed.substringAfter(' ').split('/', limit = 2)
        if (rangeAndTotal.size != 2) return null
        val bounds = rangeAndTotal[0].split('-', limit = 2)
        val start = bounds.getOrNull(0)?.toLongOrNull()
        val end = bounds.getOrNull(1)?.toLongOrNull()
        if (start != null && end != null && end < start) return null
        return ContentRange(start = start, total = rangeAndTotal[1].takeIf { it != "*" }?.toLongOrNull())
    }

    companion object {
        const val MAX_TOTAL_DOWNLOAD_BYTES = 24L * 1024L * 1024L
        const val MAX_RANGE_REQUESTS = 16

        private val ignoredHeaders = listOf("Range", "Accept-Encoding", "Content-Length", "Host")

        // Debrid links usually redirect to a download server, so redirects are followed here.
        private val client by lazy {
            HttpClient {
                followRedirects = true
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = 6_000
                    requestTimeoutMillis = 10_000
                }
            }
        }
    }
}
