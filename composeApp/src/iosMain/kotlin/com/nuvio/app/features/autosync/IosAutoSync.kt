package com.nuvio.app.features.autosync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.autosync.portable.PortableAutoSyncResult
import com.nuvio.app.features.autosync.portable.PortableSubtitleAutoSync
import com.nuvio.app.features.player.NuvioPlayerBridge
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_failed
import nuvio.composeapp.generated.resources.autosync_toast_failed_no_reference
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSUserDefaults

/** Wraps the iPhone player controller with subtitle auto-sync, like Android's ExoPlayer wrapper. */
@Composable
internal fun rememberIosAutoSyncController(
    base: PlayerEngineController,
    bridge: NuvioPlayerBridge,
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    externalSubtitles: List<StreamSubtitle>,
    preferredSubtitleLanguage: String?,
): PlayerEngineController {
    val scope = rememberCoroutineScope()
    val latestExternalSubtitles = rememberUpdatedState(externalSubtitles)
    val latestPreferredLanguage = rememberUpdatedState(preferredSubtitleLanguage)
    val coordinator = remember(base, bridge, sourceUrl, sourceHeaders) {
        IosAutoSyncCoordinator(
            scope = scope,
            base = base,
            bridge = bridge,
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            getSubtitleHeaders = { url ->
                latestExternalSubtitles.value.firstOrNull { it.url == url }?.headers.orEmpty()
            },
            getPreferredLanguage = { latestPreferredLanguage.value },
        )
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.dispose() }
    }
    return remember(coordinator) { IosAutoSyncPlayerEngineController(base, coordinator) }
}

/**
 * Every call goes to [base] unchanged, except subtitle changes, which first stop a sync still
 * working on the previous subtitle and undo its speed correction.
 */
internal class IosAutoSyncPlayerEngineController(
    private val base: PlayerEngineController,
    private val coordinator: IosAutoSyncCoordinator,
) : PlayerEngineController by base, AutoSyncPlayerController {

    override fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>) =
        coordinator.setCandidates(candidates)

    override fun setAutoSyncAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) =
        coordinator.setAppliedListener(listener)

    override fun setSubtitleUri(url: String) {
        coordinator.reset()
        base.setSubtitleUri(url)
    }

    override fun setSubtitleUriWithAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.STARTUP_SEARCH)

    override fun setSubtitleUriWithSelectedAutoSync(url: String) =
        coordinator.start(url, AutoSyncCandidateScope.SELECTED_ONLY)

    override fun selectSubtitleTrack(index: Int) {
        coordinator.reset()
        base.selectSubtitleTrack(index)
    }

    override fun clearExternalSubtitle() {
        coordinator.reset()
        base.clearExternalSubtitle()
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        coordinator.reset()
        base.clearExternalSubtitleAndSelect(trackIndex)
    }

    // A manual delay replaces the synced one; a speed correction still applies to this subtitle.
    override fun cancelForManualSubtitleDelay() {
        coordinator.cancel()
        AutoSyncSyncedSubtitle.clear()
    }
}

internal class IosAutoSyncCoordinator(
    private val scope: CoroutineScope,
    private val base: PlayerEngineController,
    private val bridge: NuvioPlayerBridge,
    private val sourceUrl: String,
    private val sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val getPreferredLanguage: () -> String?,
) {
    private var job: Job? = null
    private var operationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null
    private var speedApplied = false

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
    }

    fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {
        appliedListener = listener
    }

    fun cancel() {
        operationToken++
        job?.cancel()
        job = null
    }

    /** Another subtitle is about to show: stop syncing and return to normal subtitle speed. */
    fun reset() {
        cancel()
        AutoSyncSyncedSubtitle.clear()
        if (speedApplied) {
            bridge.setSubtitleSpeed(1.0)
            speedApplied = false
        }
    }

    fun dispose() {
        cancel()
        appliedListener = null
    }

    fun start(url: String, candidateScope: AutoSyncCandidateScope) {
        reset()
        // Show the subtitle right away; the sync adjusts its timing once it has an answer.
        base.setSubtitleUri(url)
        AutoSyncPreferencesRepository.ensureLoaded()
        val enabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value
        val isHttp = sourceUrl.startsWith("https://", ignoreCase = true) ||
            sourceUrl.startsWith("http://", ignoreCase = true)
        if (decideAutoSyncStart(enabled) == AutoSyncStartAction.ATTACH_ORIGINAL || !isHttp) return

        val token = operationToken
        val language = candidates.firstOrNull { it.url == url }?.language?.takeIf { it.isNotBlank() }
            ?: getPreferredLanguage()
        job = scope.launch {
            val result = try {
                PortableSubtitleAutoSync.run(
                    sourceUrl = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    selectedUrl = url,
                    selectedLanguage = language,
                    subtitleHeaders = getSubtitleHeaders,
                    alternatives = { candidateScope.alternativeCandidates(candidates) },
                    toleranceMs = AutoSyncPreferencesRepository.syncToleranceMs.value,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                PortableAutoSyncResult.NoMatch
            }
            if (token != operationToken) return@launch
            when (result) {
                is PortableAutoSyncResult.Fixed -> {
                    val fix = result.fix
                    if (fix.subtitleUrl != url) {
                        // A better-fitting subtitle in the same language replaces the chosen one.
                        bridge.clearExternalSubtitle()
                        base.setSubtitleUri(fix.subtitleUrl)
                    }
                    bridge.setSubtitleSpeed(fix.speed)
                    speedApplied = fix.speed != 1.0
                    base.setSubtitleDelayMs(fix.delayMs)
                    appliedListener?.invoke(fix.subtitleUrl, fix.delayMs)
                    AutoSyncSyncedSubtitle.mark(fix.subtitleUrl)
                }
                PortableAutoSyncResult.NoReference -> showFailure(token, Res.string.autosync_toast_failed_no_reference)
                PortableAutoSyncResult.SubtitleUnavailable,
                PortableAutoSyncResult.NoMatch,
                -> showFailure(token, Res.string.autosync_toast_failed)
            }
        }
    }

    private suspend fun showFailure(token: Long, resource: StringResource) {
        val text = getString(resource)
        if (token == operationToken) NuvioToastController.show(text)
    }
}

/** Saves the auto-sync settings on iPhone, under the same keys Android uses. */
internal object AutoSyncPreferencesIos {
    private const val preferredSubtitleAutoSyncOnStartKey = "preferred_subtitle_auto_sync_on_start"
    private const val aggressiveModeKey = "auto_sync_aggressive_mode"
    private const val syncToleranceMsKey = "auto_sync_tolerance_ms"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        AutoSyncPreferencesRepository.installPersistence(
            load = { loadBoolean(preferredSubtitleAutoSyncOnStartKey) },
            save = { defaults.setBool(it, forKey = ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey)) },
            loadAggressiveMode = { loadBoolean(aggressiveModeKey) },
            saveAggressiveMode = { defaults.setBool(it, forKey = ProfileScopedKey.of(aggressiveModeKey)) },
            loadSyncToleranceMs = {
                val key = ProfileScopedKey.of(syncToleranceMsKey)
                if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else null
            },
            saveSyncToleranceMs = {
                defaults.setInteger(it.toLong(), forKey = ProfileScopedKey.of(syncToleranceMsKey))
            },
        )
    }

    private fun loadBoolean(baseKey: String): Boolean? {
        val key = ProfileScopedKey.of(baseKey)
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }
}
