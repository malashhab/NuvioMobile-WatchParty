package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.reinterpret
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetCount
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode
import platform.CoreGraphics.CGImageRelease
import kotlinx.cinterop.usePinned

private val gifHttpClient = HttpClient(Darwin)
private val gifDecodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private const val DefaultGifFrameDelayCentiseconds = 10

// Cards are at most a few hundred points wide, so frames are kept at card size instead of the GIF's
// own (often much larger) size. Long animations keep an evenly spread subset of their frames.
private const val MaxGifFramePixels = 420
private const val MaxGifFrames = 48

// Decoded animations are kept within a memory budget; the downloaded files, which are much smaller,
// are kept longer so scrolling back only needs a quick re-decode, not a new download.
private const val MaxDecodedGifBytes = 80L * 1024 * 1024
private const val MaxCachedGifFiles = 32

// A few at a time, so a row full of animated cards doesn't flood the processor.
private val gifDecodePermits = Semaphore(2)

private class DecodedGif(val image: UIImage, val bytes: Long)

private val gifImageCache = LinkedHashMap<String, DecodedGif>()
private val gifFileCache = LinkedHashMap<String, ByteArray>()
private val gifImageInFlight = mutableMapOf<String, Deferred<Pair<ByteArray, DecodedGif>?>>()

private data class GifFrame(
    val image: UIImage,
    val delayCentiseconds: Int,
)

private data class ExpandedGifFrames(
    val images: List<UIImage>,
    val tickCentiseconds: Int,
)

private class GifImageViewHolder {
    var imageView: UIImageView? = null

    fun clear() {
        imageView?.stopAnimating()
        imageView?.image = null
        imageView = null
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    if (!animateIfPossible) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    var gifImage by remember(imageUrl) { mutableStateOf(cachedGifImage(imageUrl)) }

    LaunchedEffect(imageUrl) {
        gifImage = loadGifImage(imageUrl)
    }

    val imageViewHolder = remember(imageUrl) { GifImageViewHolder() }
    DisposableEffect(imageUrl) {
        onDispose {
            imageViewHolder.clear()
        }
    }

    Box(modifier = modifier) {
        // The still picture shows right away (and stays if the animation can't be read).
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            contentScale = contentScale,
        )
        val animated = gifImage ?: return@Box
        UIKitView(
            modifier = Modifier.matchParentSize(),
            factory = {
                UIImageView().apply {
                    contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
                    clipsToBounds = true
                    userInteractionEnabled = false
                    tag = imageUrl.hashCode().toLong()
                    imageViewHolder.imageView = this
                    updateGifImage(animated)
                }
            },
            update = { imageView ->
                imageViewHolder.imageView = imageView
                if (imageView.tag != imageUrl.hashCode().toLong()) {
                    imageView.tag = imageUrl.hashCode().toLong()
                }
                imageView.updateGifImage(animated)
            },
        )
    }
}

private fun UIImageView.updateGifImage(image: UIImage?) {
    if (this.image != image) {
        stopAnimating()
        this.image = image
    }
    if (image != null) {
        startAnimating()
    }
}

private fun cachedGifImage(imageUrl: String): UIImage? {
    val entry = gifImageCache.remove(imageUrl) ?: return null
    gifImageCache[imageUrl] = entry
    return entry.image
}

private fun storeGifImage(imageUrl: String, entry: DecodedGif) {
    gifImageCache.remove(imageUrl)
    gifImageCache[imageUrl] = entry
    var total = gifImageCache.values.sumOf { it.bytes }
    while (total > MaxDecodedGifBytes && gifImageCache.size > 1) {
        val eldestKey = gifImageCache.keys.first()
        total -= gifImageCache.remove(eldestKey)?.bytes ?: 0L
    }
}

private fun cachedGifFile(imageUrl: String): ByteArray? {
    val bytes = gifFileCache.remove(imageUrl) ?: return null
    gifFileCache[imageUrl] = bytes
    return bytes
}

private fun storeGifFile(imageUrl: String, bytes: ByteArray) {
    gifFileCache.remove(imageUrl)
    gifFileCache[imageUrl] = bytes
    while (gifFileCache.size > MaxCachedGifFiles) {
        gifFileCache.remove(gifFileCache.keys.first())
    }
}

// Runs on the main thread (from the card), so the caches are only ever touched there.
@OptIn(ExperimentalForeignApi::class)
private suspend fun loadGifImage(imageUrl: String): UIImage? {
    cachedGifImage(imageUrl)?.let { return it }

    val known = cachedGifFile(imageUrl)
    val request = gifImageInFlight[imageUrl] ?: gifDecodeScope.async {
        runCatching {
            val bytes = known ?: gifHttpClient.get(imageUrl).body<ByteArray>()
            if (bytes.isEmpty()) return@runCatching null
            val decoded = gifDecodePermits.withPermit {
                UIImage.gifImageWithBytes(bytes = bytes, frameDurations = parseGifFrameDurations(bytes))
            } ?: return@runCatching null
            bytes to decoded
        }.getOrNull()
    }.also { gifImageInFlight[imageUrl] = it }

    val result = try {
        request.await()
    } finally {
        if (gifImageInFlight[imageUrl] === request) {
            gifImageInFlight.remove(imageUrl)
        }
    }

    val (bytes, entry) = result ?: return null
    storeGifFile(imageUrl, bytes)
    storeGifImage(imageUrl, entry)
    return entry.image
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData() =
    usePinned { pinned ->
        CFDataCreate(
            allocator = null,
            bytes = pinned.addressOf(0).reinterpret(),
            length = size.toLong(),
        )
    }

/** Draws [image] no bigger than [MaxGifFramePixels] on its long side; small frames stay as they are. */
@OptIn(ExperimentalForeignApi::class)
private fun shrinkFrame(imageRef: CGImageRef): Pair<UIImage, Long> {
    val width = CGImageGetWidth(imageRef).toDouble()
    val height = CGImageGetHeight(imageRef).toDouble()
    val longSide = maxOf(width, height)
    val original = UIImage.imageWithCGImage(imageRef)
    if (longSide <= MaxGifFramePixels || longSide <= 0.0) {
        return original to (width * height * 4).toLong()
    }
    val scale = MaxGifFramePixels / longSide
    val targetWidth = (width * scale).coerceAtLeast(1.0)
    val targetHeight = (height * scale).coerceAtLeast(1.0)
    val format = UIGraphicsImageRendererFormat().apply { this.scale = 1.0 }
    val renderer = UIGraphicsImageRenderer(size = CGSizeMake(targetWidth, targetHeight), format = format)
    val shrunk = renderer.imageWithActions { _ ->
        original.drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
    }
    return shrunk to (targetWidth * targetHeight * 4).toLong()
}

@OptIn(ExperimentalForeignApi::class)
private fun UIImage.Companion.gifImageWithBytes(
    bytes: ByteArray,
    frameDurations: List<Int>,
): DecodedGif? {
    return runCatching {
        val data = bytes.toCFData() ?: return null
        try {
            val source = CGImageSourceCreateWithData(data, null) ?: return null
            try {
                val count = CGImageSourceGetCount(source).toInt()
                if (count <= 0) return null
                // An evenly spread subset of frames for long animations; each kept frame lasts as
                // long as the frames it stands in for, so the animation keeps its speed.
                val keep = if (count <= MaxGifFrames) (0 until count).toList() else
                    (0 until MaxGifFrames).map { (it.toLong() * count / MaxGifFrames).toInt() }.distinct()
                val frames = mutableListOf<GifFrame>()
                var frameBytes = 0L

                keep.forEachIndexed { position, index ->
                    val nextIndex = keep.getOrNull(position + 1) ?: count
                    val delay = (index until nextIndex).sumOf { frame ->
                        frameDurations.getOrNull(frame)?.coerceAtLeast(1) ?: DefaultGifFrameDelayCentiseconds
                    }
                    autoreleasepool {
                        val imageRef: CGImageRef = CGImageSourceCreateImageAtIndex(source, index.toULong(), null)
                            ?: return@autoreleasepool
                        try {
                            val (image, cost) = shrinkFrame(imageRef)
                            frames.add(GifFrame(image = image, delayCentiseconds = delay))
                            frameBytes += cost
                        } finally {
                            CGImageRelease(imageRef)
                        }
                    }
                }

                if (frames.isEmpty()) return null

                val expanded = expandedGifFrames(frames)
                val durationSeconds = (expanded.images.size * expanded.tickCentiseconds) / 100.0
                UIImage.animatedImageWithImages(expanded.images, durationSeconds)
                    ?.let { DecodedGif(image = it, bytes = frameBytes) }
            } finally {
                CFRelease(source)
            }
        } finally {
            CFRelease(data)
        }
    }.getOrNull()
}

private fun expandedGifFrames(frames: List<GifFrame>): ExpandedGifFrames {
    val normalizedDelays = frames.map { it.delayCentiseconds.coerceAtLeast(1) }
    val tickCentiseconds = normalizedDelays.reduce(::greatestCommonDivisor)
    val expandedSize = normalizedDelays.sumOf { it / tickCentiseconds }
    val expandedFrames = ArrayList<UIImage>(expandedSize)

    frames.forEach { frame ->
        val repeatCount = (frame.delayCentiseconds.coerceAtLeast(1) / tickCentiseconds).coerceAtLeast(1)
        repeat(repeatCount) {
            expandedFrames.add(frame.image)
        }
    }

    return ExpandedGifFrames(
        images = expandedFrames,
        tickCentiseconds = tickCentiseconds,
    )
}

private fun greatestCommonDivisor(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val temp = x % y
        x = y
        y = temp
    }
    return x.coerceAtLeast(1)
}

private fun parseGifFrameDurations(bytes: ByteArray): List<Int> {
    if (bytes.size < 13 || !bytes.hasGifHeader()) return emptyList()

    var index = 6
    if (index + 7 > bytes.size) return emptyList()

    val logicalScreenPacked = bytes[index + 4].unsignedInt()
    index += 7

    if (logicalScreenPacked and 0x80 != 0) {
        val globalColorTableSize = 3 * (1 shl ((logicalScreenPacked and 0x07) + 1))
        index += globalColorTableSize
    }

    val frameDurations = mutableListOf<Int>()
    var pendingDelayCentiseconds: Int? = null

    while (index < bytes.size) {
        when (bytes[index].unsignedInt()) {
            0x21 -> {
                if (index + 1 >= bytes.size) break
                val extensionLabel = bytes[index + 1].unsignedInt()
                if (extensionLabel == 0xF9) {
                    if (index + 7 >= bytes.size) break
                    val delayHundredths = bytes.readUnsignedShort(index + 4)
                    pendingDelayCentiseconds = if (delayHundredths <= 0) {
                        DefaultGifFrameDelayCentiseconds
                    } else {
                        delayHundredths
                    }
                    index += 8
                } else {
                    index += 2
                    index = bytes.skipGifSubBlocks(index)
                }
            }

            0x2C -> {
                if (index + 9 >= bytes.size) break
                val imageDescriptorPacked = bytes[index + 9].unsignedInt()
                index += 10

                if (imageDescriptorPacked and 0x80 != 0) {
                    val localColorTableSize = 3 * (1 shl ((imageDescriptorPacked and 0x07) + 1))
                    index += localColorTableSize
                }

                if (index >= bytes.size) break
                index += 1
                index = bytes.skipGifSubBlocks(index)

                frameDurations += pendingDelayCentiseconds ?: DefaultGifFrameDelayCentiseconds
                pendingDelayCentiseconds = null
            }

            0x3B -> break
            else -> break
        }
    }

    return frameDurations
}

private fun ByteArray.hasGifHeader(): Boolean =
    size >= 6 &&
        this[0] == 'G'.code.toByte() &&
        this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() &&
        this[3] == '8'.code.toByte() &&
        (this[4] == '7'.code.toByte() || this[4] == '9'.code.toByte()) &&
        this[5] == 'a'.code.toByte()

private fun ByteArray.skipGifSubBlocks(startIndex: Int): Int {
    var index = startIndex
    while (index < size) {
        val blockSize = this[index].unsignedInt()
        index += 1
        if (blockSize == 0) {
            return index
        }
        index += blockSize
    }
    return index
}

private fun ByteArray.readUnsignedShort(startIndex: Int): Int {
    if (startIndex + 1 >= size) return 0
    return this[startIndex].unsignedInt() or (this[startIndex + 1].unsignedInt() shl 8)
}

private fun Byte.unsignedInt(): Int = toInt() and 0xFF
