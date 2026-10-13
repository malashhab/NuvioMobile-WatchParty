package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchparty.WatchPartyConnection
import com.nuvio.app.features.watchparty.WatchPartyContent
import com.nuvio.app.features.watchparty.WatchPartyCoordinator
import com.nuvio.app.features.watchparty.WatchPartyDialog
import com.nuvio.app.features.watchparty.WatchPartyPlayback
import com.nuvio.app.features.watchparty.WatchPartyPlayerPort
import com.nuvio.app.features.watchparty.WatchPartyRoomController
import com.nuvio.app.features.watchparty.WatchPartySessionRepository
import com.nuvio.app.features.watchparty.WatchPartySource
import com.nuvio.app.features.watchparty.WatchPartySourceMatch
import com.nuvio.app.features.watchparty.WatchPartySourceMatcher
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_default_controller
import nuvio.composeapp.generated.resources.watch_party_finding_source
import nuvio.composeapp.generated.resources.watch_party_follower_hint
import nuvio.composeapp.generated.resources.watch_party_source_missing
import org.jetbrains.compose.resources.getString

private const val WATCH_PARTY_HEARTBEAT_MS = 5_000L

// Players report the new position shortly after a seek, not at the moment it is requested.
private const val WATCH_PARTY_SEEK_REPORT_DELAY_MS = 700L

private const val WATCH_PARTY_SOURCE_SEARCH_TIMEOUT_MS = 30_000L
private const val WATCH_PARTY_SOURCE_MISSING_TOAST_MS = 5_000L

internal class PlayerWatchPartyState(
    val coordinator: WatchPartyCoordinator,
) {
    var seekReportJob: Job? = null
    var sourceSearchJob: Job? = null
}

/** In a party but not in control: playback follows the controller. */
internal val PlayerScreenRuntime.isWatchPartyFollower: Boolean
    get() = watchParty?.coordinator?.isController == false

/** Null hides the toolbar button, e.g. in builds without a Watch Party server configured. */
internal fun PlayerScreenRuntime.watchPartyClickHandler(): (() -> Unit)? =
    if (WatchPartyRoomController.isConfigured) {
        { showWatchPartyDialog = true }
    } else {
        null
    }

/** Followers can't play or pause; tell them who can, and how to ask. Returns true when blocked. */
internal fun PlayerScreenRuntime.blockWatchPartyFollowerAction(): Boolean {
    if (!isWatchPartyFollower) return false
    showWatchPartyFollowerHint()
    return true
}

internal fun PlayerScreenRuntime.notifyWatchPartySeek() {
    val party = watchParty ?: return
    // A follower's seek is undone by the next sync; explain why.
    if (isWatchPartyFollower) showWatchPartyFollowerHint()
    party.seekReportJob?.cancel()
    party.seekReportJob = scope.launch {
        delay(WATCH_PARTY_SEEK_REPORT_DELAY_MS)
        party.coordinator.onLocalPlaybackChanged()
    }
}

private fun PlayerScreenRuntime.showWatchPartyFollowerHint() {
    val status = WatchPartyRoomController.status.value
    scope.launch {
        val controllerName = status.nameOf(status.controllerId) ?: getString(Res.string.watch_party_default_controller)
        NuvioToastController.show(getString(Res.string.watch_party_follower_hint, controllerName))
    }
}

@Composable
internal fun PlayerScreenRuntime.BindWatchPartyEffects() {
    val activeParty by WatchPartySessionRepository.active.collectAsStateWithLifecycle()
    val pendingRequestCount = WatchPartyRoomController.status.collectAsStateWithLifecycle().value.pendingRequests.size

    if (showWatchPartyDialog) {
        WatchPartyDialog(onDismiss = { showWatchPartyDialog = false })
    }

    // Open the panel when someone asks for control, so the owner can answer right away.
    var lastPendingRequestCount by remember { mutableStateOf(0) }
    LaunchedEffect(pendingRequestCount) {
        if (pendingRequestCount > lastPendingRequestCount) showWatchPartyDialog = true
        lastPendingRequestCount = pendingRequestCount
    }

    DisposableEffect(activeParty) {
        val session = activeParty?.let { party ->
            val startMark = TimeSource.Monotonic.markNow()
            PlayerWatchPartyState(
                WatchPartyCoordinator(
                    localMemberId = party.localMemberId,
                    transport = party.transport,
                    player = PlayerWatchPartyPort(this@BindWatchPartyEffects),
                    nowMs = { startMark.elapsedNow().inWholeMilliseconds },
                    controllerId = { party.control.controllerId.value },
                ),
            )
        }
        watchParty = session
        session?.coordinator?.start()
        onDispose {
            session?.coordinator?.stop()
            session?.seekReportJob?.cancel()
            session?.sourceSearchJob?.cancel()
            if (watchParty === session) watchParty = null
        }
    }

    val coordinator = watchParty?.coordinator ?: return
    val party = activeParty ?: return

    // The connection drops and comes back on its own; catch up each time it does.
    LaunchedEffect(coordinator, party) {
        party.connection.collect { state ->
            if (state == WatchPartyConnection.Connected) coordinator.onReconnected()
        }
    }

    LaunchedEffect(coordinator, party) {
        party.control.controllerId.collect { coordinator.onControllerChanged() }
    }

    LaunchedEffect(
        coordinator,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        activeVideoId,
        activeSeasonNumber,
        activeEpisodeNumber,
    ) {
        coordinator.onLocalPlaybackChanged()
    }

    LaunchedEffect(coordinator) {
        while (true) {
            delay(WATCH_PARTY_HEARTBEAT_MS)
            coordinator.onHeartbeat()
        }
    }
}

private class PlayerWatchPartyPort(
    private val runtime: PlayerScreenRuntime,
) : WatchPartyPlayerPort {
    override fun currentContent(): WatchPartyContent =
        WatchPartyContent(
            parentMetaId = runtime.parentMetaId,
            parentMetaType = runtime.parentMetaType,
            videoId = runtime.activeVideoId,
            seasonNumber = runtime.activeSeasonNumber,
            episodeNumber = runtime.activeEpisodeNumber,
        )

    override fun currentPlayback(): WatchPartyPlayback? {
        val snapshot = runtime.playbackSnapshot
        if (runtime.playerController == null) return null
        if (runtime.playerControllerSourceUrl != runtime.activeSourceUrl) return null
        if (snapshot.isLoading) return null
        return WatchPartyPlayback(
            content = currentContent(),
            isPlaying = snapshot.isPlaying,
            positionMs = snapshot.positionMs,
            source = runtime.currentWatchPartySource(),
        )
    }

    override fun play() {
        runtime.shouldPlay = true
        runtime.playerController?.play()
    }

    override fun pause() {
        runtime.shouldPlay = false
        runtime.playerController?.pause()
    }

    override fun seekTo(positionMs: Long) {
        runtime.playerController?.seekTo(positionMs.coerceAtLeast(0L))
    }

    // Every member plays from their own sources: find the controller's source among ours, or let the user pick.
    override fun openContent(content: WatchPartyContent, positionMs: Long, source: WatchPartySource?) {
        // Switching to a different title from inside the player is not supported yet.
        if (content.parentMetaId != runtime.parentMetaId) return
        val episode = runtime.playerMetaVideos.firstOrNull { it.id == content.videoId }
            ?: runtime.playerMetaVideos.firstOrNull {
                it.season == content.seasonNumber && it.episode == content.episodeNumber
            }
        if (episode == null) {
            runtime.openEpisodesPanel()
            return
        }
        PlayerStreamsRepository.loadEpisodeStreams(
            type = runtime.contentType ?: runtime.parentMetaType,
            videoId = episode.id,
            season = episode.season,
            episode = episode.episode,
        )
        if (source == null) {
            runtime.showEpisodeStreamsPanel(episode)
        } else {
            runtime.followControllerSource(episode, source)
        }
    }
}

private fun PlayerScreenRuntime.followControllerSource(episode: MetaVideo, source: WatchPartySource) {
    val party = watchParty ?: return
    party.sourceSearchJob?.cancel()
    party.sourceSearchJob = scope.launch {
        NuvioToastController.show(getString(Res.string.watch_party_finding_source))
        var best: WatchPartySourceMatch<StreamItem>? = null
        // Take the exact file as soon as it shows up; otherwise wait for every addon so a weaker match
        // from a fast addon doesn't beat a stronger one from a slow addon.
        withTimeoutOrNull(WATCH_PARTY_SOURCE_SEARCH_TIMEOUT_MS) {
            PlayerStreamsRepository.episodeStreamsState.first { state ->
                best = WatchPartySourceMatcher.bestMatch(
                    wanted = source,
                    candidates = state.groups.flatMap { it.streams },
                    fingerprintOf = { it.toWatchPartySource() },
                )
                best?.isExactFile == true || !state.isAnyLoading
            }
        }
        val match = best
        if (match != null) {
            switchToEpisodeStream(match.item, episode)
        } else {
            NuvioToastController.show(
                getString(Res.string.watch_party_source_missing, source.displayLabel),
                WATCH_PARTY_SOURCE_MISSING_TOAST_MS,
            )
            showEpisodeStreamsPanel(episode)
        }
    }
}

private fun PlayerScreenRuntime.showEpisodeStreamsPanel(episode: MetaVideo) {
    episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = episode)
    showEpisodesPanel = true
    showSourcesPanel = false
}

private fun PlayerScreenRuntime.currentWatchPartySource(): WatchPartySource {
    // The identity key is current for the active stream, unlike the separate torrent fields, which can
    // be left over from an earlier torrent.
    val torrentKey = activeSourceIdentityKey?.takeIf { it.startsWith("torrent:") }?.split(':')
    return WatchPartySource.shareable(
        addonId = activeProviderAddonId,
        addonName = activeProviderName,
        name = activeStreamTitle,
        description = activeStreamSubtitle,
        infoHash = torrentKey?.getOrNull(1),
        fileIdx = torrentKey?.getOrNull(2)?.toIntOrNull()?.takeIf { it >= 0 },
        bingeGroup = currentStreamBingeGroup,
    )
}

// Built the same way as the controller's fingerprint, so addon ids compare like for like.
private fun StreamItem.toWatchPartySource(): WatchPartySource =
    WatchPartySource.shareable(
        addonId = addonId,
        addonName = addonName,
        name = streamLabel,
        description = streamSubtitle,
        infoHash = p2pInfoHash,
        fileIdx = p2pFileIdx,
        filename = behaviorHints.filename,
        videoSize = behaviorHints.videoSize,
        bingeGroup = behaviorHints.bingeGroup,
    )
