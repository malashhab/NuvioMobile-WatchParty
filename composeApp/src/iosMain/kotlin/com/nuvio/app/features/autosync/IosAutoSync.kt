package com.nuvio.app.features.autosync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.autosync.bubble.AutoSyncBubbleKind
import com.nuvio.app.features.autosync.bubble.AutoSyncBubbleToasts
import com.nuvio.app.features.autosync.portable.PortableAutoSyncResult
import com.nuvio.app.features.autosync.portable.PortableSubtitleAutoSync
import com.nuvio.app.features.player.NuvioPlayerBridge
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_analyzing
import nuvio.composeapp.generated.resources.autosync_toast_failed
import nuvio.composeapp.generated.resources.autosync_toast_failed_audio_fallback
import nuvio.composeapp.generated.resources.player_audio_sync_synced
import nuvio.composeapp.generated.resources.player_audio_sync_synced_rate
import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import kotlin.math.abs
import nuvio.composeapp.generated.resources.autosync_toast_failed_no_reference
import nuvio.composeapp.generated.resources.autosync_toast_in_sync
import nuvio.composeapp.generated.resources.autosync_toast_synced
import nuvio.composeapp.generated.resources.autosync_toast_synced_replaced
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
    secondaryPreferredSubtitleLanguage: String?,
): PlayerEngineController {
    val scope = rememberCoroutineScope()
    val latestExternalSubtitles = rememberUpdatedState(externalSubtitles)
    val latestPreferredLanguage = rememberUpdatedState(preferredSubtitleLanguage)
    val latestSecondaryLanguage = rememberUpdatedState(secondaryPreferredSubtitleLanguage)
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
            getSecondaryLanguage = { latestSecondaryLanguage.value },
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

    override val autoSyncRetryState: StateFlow<AutoSyncRetryUiState>
        get() = coordinator.retryState

    override fun retryWithAnotherReference() = coordinator.retryWithAnotherReference()

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
}

internal class IosAutoSyncCoordinator(
    private val scope: CoroutineScope,
    private val base: PlayerEngineController,
    private val bridge: NuvioPlayerBridge,
    private val sourceUrl: String,
    private val sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val getPreferredLanguage: () -> String?,
    private val getSecondaryLanguage: () -> String? = { null },
) {
    private var job: Job? = null
    private var operationToken = 0L
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null
    private var speedApplied = false

    // "Try another reference": the subtitle we synced, the built-in track it matched, and the ones rejected.
    private val _retryState = MutableStateFlow(AutoSyncRetryUiState())
    val retryState: StateFlow<AutoSyncRetryUiState> = _retryState.asStateFlow()
    private var syncedUrl: String? = null
    private var referenceKey: String? = null
    private var rejectedReferenceKeys: Set<String> = emptySet()

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
        _retryState.value = AutoSyncRetryUiState()
        syncedUrl = null
        referenceKey = null
        rejectedReferenceKeys = emptySet()
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
        // Picking the subtitle that is already synced keeps its timing instead of starting over.
        if (url == syncedUrl) return
        reset()
        // Show the subtitle right away; the sync adjusts its timing once it has an answer.
        base.setSubtitleUri(url)
        AutoSyncPreferencesRepository.ensureLoaded()
        val enabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value
        val isHttp = sourceUrl.startsWith("https://", ignoreCase = true) ||
            sourceUrl.startsWith("http://", ignoreCase = true)
        if (decideAutoSyncStart(enabled) == AutoSyncStartAction.ATTACH_ORIGINAL || !isHttp) return

        val language = candidates.firstOrNull { it.url == url }?.language?.takeIf { it.isNotBlank() }
            ?: getPreferredLanguage()
        sync(url, language, candidateScope, excluded = emptySet(), retrying = false)
    }

    /** Matches the synced subtitle again, skipping every built-in track already tried. */
    fun retryWithAnotherReference() {
        val url = syncedUrl ?: return
        val tried = referenceKey ?: return
        if (_retryState.value.busy || _retryState.value.exhausted) return
        rejectedReferenceKeys = rejectedReferenceKeys + tried
        val language = candidates.firstOrNull { it.url == url }?.language?.takeIf { it.isNotBlank() }
            ?: getPreferredLanguage()
        _retryState.value = _retryState.value.copy(busy = true, status = AutoSyncRetryStatus.TRYING)
        sync(url, language, AutoSyncCandidateScope.SELECTED_ONLY, excluded = rejectedReferenceKeys, retrying = true)
    }

    private fun sync(
        url: String,
        language: String?,
        candidateScope: AutoSyncCandidateScope,
        excluded: Set<String>,
        retrying: Boolean,
    ) {
        cancel()
        val token = operationToken
        job = scope.launch {
            // Let the video get going first, so reading the subtitle index doesn't compete with it.
            withTimeoutOrNull(START_WAIT_MS) {
                while (bridge.getIsLoading()) delay(START_POLL_MS)
            }
            if (!retrying) showMessage(token, AutoSyncBubbleKind.Working, Res.string.autosync_toast_analyzing)
            suspend fun attempt(selectedUrl: String, selectedLanguage: String?): PortableAutoSyncResult? = try {
                PortableSubtitleAutoSync.run(
                    sourceUrl = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    selectedUrl = selectedUrl,
                    selectedLanguage = selectedLanguage,
                    subtitleHeaders = getSubtitleHeaders,
                    alternatives = { candidateScope.alternativeCandidates(candidates) },
                    toleranceMs = AutoSyncPreferencesRepository.syncToleranceMs.value,
                    excludedReferenceKeys = excluded,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            var result = attempt(url, language)
            // No match in the first language: try the second subtitle language, as Android does.
            // Only at startup, never over a subtitle the user picked, and not without a reference.
            if (
                result !is PortableAutoSyncResult.Fixed && result != PortableAutoSyncResult.NoReference &&
                !retrying && candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH && token == operationToken
            ) {
                val seed = secondaryLanguageSearchSeed(
                    candidates = candidates,
                    selectedUrl = url,
                    searchedLanguage = language,
                    secondaryLanguage = getSecondaryLanguage(),
                )
                if (seed != null) {
                    val second = attempt(seed.url, seed.language)
                    if (second is PortableAutoSyncResult.Fixed) result = second
                }
            }
            if (token != operationToken) return@launch
            val outcome = result
            when {
                outcome is PortableAutoSyncResult.Fixed -> {
                    val fix = outcome.fix
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
                    syncedUrl = fix.subtitleUrl
                    referenceKey = outcome.referenceKey
                    _retryState.value = AutoSyncRetryUiState(
                        available = true,
                        exhausted = !outcome.moreReferences,
                        status = if (retrying) AutoSyncRetryStatus.UPDATED else AutoSyncRetryStatus.IDLE,
                    )
                    if (!retrying) {
                        showMessage(
                            token,
                            AutoSyncBubbleKind.Success,
                            when {
                                fix.subtitleUrl != url -> Res.string.autosync_toast_synced_replaced
                                fix.delayMs == 0 && fix.speed == 1.0 -> Res.string.autosync_toast_in_sync
                                else -> Res.string.autosync_toast_synced
                            },
                        )
                    }
                }
                // Retrying keeps the current timing when no other built-in track fits.
                retrying -> _retryState.value = AutoSyncRetryUiState(
                    available = true,
                    exhausted = outcome != null,
                    status = if (outcome == null) AutoSyncRetryStatus.FAILED else AutoSyncRetryStatus.EXHAUSTED,
                )
                // Nothing built in to compare with, or no match: listen to the audio instead, as Android does.
                // Not when the subtitle itself couldn't be read.
                (outcome == PortableAutoSyncResult.NoReference || outcome == PortableAutoSyncResult.NoMatch) &&
                    IosAudioSync.allowedNow() -> syncToAudio(token, url)
                outcome == PortableAutoSyncResult.NoReference ->
                    showFailure(token, Res.string.autosync_toast_failed_no_reference)
                else -> showFailure(token, Res.string.autosync_toast_failed)
            }
        }
    }

    private companion object {
        const val START_WAIT_MS = 20_000L
        const val START_POLL_MS = 500L
    }

    private suspend fun syncToAudio(token: Long, url: String) {
        showMessage(token, AutoSyncBubbleKind.Working, Res.string.autosync_toast_failed_audio_fallback)
        val fix = try {
            IosAudioSync.run(bridge, sourceUrl, sourceHeaders, url, getSubtitleHeaders(url))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        if (token != operationToken) return
        if (fix == null) {
            showFailure(token, Res.string.autosync_toast_failed)
            return
        }
        val delayMs = fix.delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
        bridge.setSubtitleSpeed(fix.speed)
        speedApplied = fix.speed != 1.0
        base.setSubtitleDelayMs(delayMs)
        appliedListener?.invoke(url, delayMs)
        AutoSyncSyncedSubtitle.mark(url)
        syncedUrl = url
        val offset = (if (delayMs < 0) "-" else "+") + ((abs(delayMs) + 50) / 100).let { "${it / 10}.${it % 10}s" }
        val text = getString(
            if (fix.speed != 1.0) Res.string.player_audio_sync_synced_rate else Res.string.player_audio_sync_synced,
            offset,
        )
        // This one took a while, so it shows even without the bubble.
        if (!AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Success, text)) NuvioToastController.show(text)
    }

    private suspend fun showFailure(token: Long, resource: StringResource) =
        showMessage(token, AutoSyncBubbleKind.Failure, resource)

    /** In the AutoSync bubble when it is on; otherwise only failures show, as a plain toast like before. */
    private suspend fun showMessage(token: Long, kind: AutoSyncBubbleKind, resource: StringResource) {
        val text = getString(resource)
        if (token != operationToken) return
        if (!AutoSyncBubbleToasts.post(kind, text) && kind == AutoSyncBubbleKind.Failure) {
            NuvioToastController.show(text)
        }
    }
}

/** Saves the auto-sync settings on iPhone, under the same keys Android uses. */
internal object AutoSyncPreferencesIos {
    private const val preferredSubtitleAutoSyncOnStartKey = "preferred_subtitle_auto_sync_on_start"
    private const val aggressiveModeKey = "auto_sync_aggressive_mode"
    private const val syncToleranceMsKey = "auto_sync_tolerance_ms"
    private const val bubbleEnabledKey = "nuvio_autosync_bubble_settings.enabled"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        AudioSyncPreferencesIos.install()
        AutoSyncBubbleToasts.installPersistence(
            load = { if (defaults.objectForKey(bubbleEnabledKey) != null) defaults.boolForKey(bubbleEnabledKey) else true },
            save = { defaults.setBool(it, forKey = bubbleEnabledKey) },
        )
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
