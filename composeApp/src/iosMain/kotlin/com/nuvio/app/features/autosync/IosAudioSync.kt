package com.nuvio.app.features.autosync

import com.nuvio.app.features.autosync.portable.PortableSubtitleAutoSync
import com.nuvio.app.features.player.AudioSyncSettings
import com.nuvio.app.features.player.NuvioPlayerBridge
import com.nuvio.app.features.player.audiosync.AudioSyncFix
import com.nuvio.app.features.player.audiosync.PortableAudioSync
import com.nuvio.app.features.player.audiosync.SileroVadWeights
import com.nuvio.app.features.player.audiosync.readPcmWav
import com.nuvio.app.features.player.encodePlaybackHeadersForBridge
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDefaults
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/**
 * Sync to audio on iPhone, for when AutoSync finds no built-in subtitle to compare with. A hidden
 * mpv decodes a few stretches of the film's audio (Swift, AudioSpotExtractor) and
 * [PortableAudioSync] matches the speech in them against the subtitle lines.
 */
internal object IosAudioSync {
    private const val WEIGHTS_FILE = "files/silero_vad_v5_16k.bin"
    private const val DURATION_WAIT_MS = 20_000L
    private const val MAX_SPOT_FILE_BYTES = 16L * 1024 * 1024

    private var weights: SileroVadWeights? = null

    /** On, and on Wi-Fi (or mobile data when allowed): it reads a few minutes of the stream. */
    fun allowedNow(): Boolean {
        if (!AudioSyncSettings.fallbackEnabled.value) return false
        return when (IosNetworkPath.onWifi) {
            true -> true
            null -> false
            false -> AudioSyncSettings.samplingOnMobileData.value
        }
    }

    suspend fun run(
        bridge: NuvioPlayerBridge,
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        subtitleUrl: String,
        subtitleHeaders: Map<String, String>,
    ): AudioSyncFix? {
        val cues = PortableSubtitleAutoSync.loadDialogueCues(subtitleUrl, subtitleHeaders) ?: return null
        val durationMs = withTimeoutOrNull(DURATION_WAIT_MS) {
            while (bridge.getDurationMs() <= 0L) delay(500L)
            bridge.getDurationMs()
        } ?: return null
        val weights = loadWeights() ?: return null
        val headersJson = encodePlaybackHeadersForBridge(sourceHeaders)
        return PortableAudioSync.run(cues, durationMs, weights) { startMs, lengthMs ->
            withContext(Dispatchers.IO) {
                val path = NSTemporaryDirectory().trimEnd('/') + "/nuvio_audio_spot_$startMs.wav"
                try {
                    if (!bridge.extractAudioSpot(sourceUrl, headersJson, startMs, lengthMs, path)) {
                        null
                    } else {
                        readFile(path)?.let(::readPcmWav)
                    }
                } finally {
                    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
                }
            }
        }
    }

    private suspend fun loadWeights(): SileroVadWeights? {
        weights?.let { return it }
        return withContext(Dispatchers.Default) {
            runCatching { SileroVadWeights.read(Res.readBytes(WEIGHTS_FILE)) }.getOrNull()
        }?.also { weights = it }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readFile(path: String): ByteArray? {
        val file = fopen(path, "rb") ?: return null
        return try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            if (size <= 0 || size > MAX_SPOT_FILE_BYTES) return null
            fseek(file, 0, SEEK_SET)
            val bytes = ByteArray(size.toInt())
            val read = bytes.usePinned { fread(it.addressOf(0), 1.convert(), size.convert(), file) }
            if (read.toLong() != size.toLong()) null else bytes
        } finally {
            fclose(file)
        }
    }
}

/** Saves the sync-to-audio switches on iPhone, under the names Android uses. */
internal object AudioSyncPreferencesIos {
    private const val prefix = "nuvio_audio_sync_settings."

    fun install() {
        val defaults = NSUserDefaults.standardUserDefaults
        AudioSyncSettings.installPersistence(
            load = { key -> if (defaults.objectForKey(prefix + key) != null) defaults.boolForKey(prefix + key) else null },
            save = { key, value -> defaults.setBool(value, forKey = prefix + key) },
        )
    }
}
