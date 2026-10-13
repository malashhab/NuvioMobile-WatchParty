package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.watchparty.WatchPartyConnection
import com.nuvio.app.features.watchparty.WatchPartyContent
import com.nuvio.app.features.watchparty.WatchPartyCoordinator
import com.nuvio.app.features.watchparty.WatchPartyDialog
import com.nuvio.app.features.watchparty.WatchPartyPlayback
import com.nuvio.app.features.watchparty.WatchPartyPlayerPort
import com.nuvio.app.features.watchparty.WatchPartyRoomController
import com.nuvio.app.features.watchparty.WatchPartySessionRepository
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val WATCH_PARTY_HEARTBEAT_MS = 5_000L

// Players report the new position shortly after a seek, not at the moment it is requested.
private const val WATCH_PARTY_SEEK_REPORT_DELAY_MS = 700L

internal class PlayerWatchPartyState(
    val coordinator: WatchPartyCoordinator,
) {
    var seekReportJob: Job? = null
}

internal val PlayerScreenRuntime.isWatchPartyGuest: Boolean
    get() = watchParty?.coordinator?.isHost == false

/** Null hides the toolbar button, e.g. in builds without a Watch Party server configured. */
internal fun PlayerScreenRuntime.watchPartyClickHandler(): (() -> Unit)? =
    if (WatchPartyRoomController.isConfigured) {
        { showWatchPartyDialog = true }
    } else {
        null
    }

internal fun PlayerScreenRuntime.notifyWatchPartySeek() {
    val party = watchParty ?: return
    party.seekReportJob?.cancel()
    party.seekReportJob = scope.launch {
        delay(WATCH_PARTY_SEEK_REPORT_DELAY_MS)
        party.coordinator.onLocalPlaybackChanged()
    }
}

@Composable
internal fun PlayerScreenRuntime.BindWatchPartyEffects() {
    val activeParty by WatchPartySessionRepository.active.collectAsStateWithLifecycle()

    if (showWatchPartyDialog) {
        WatchPartyDialog(onDismiss = { showWatchPartyDialog = false })
    }

    DisposableEffect(activeParty) {
        val session = activeParty?.let { party ->
            val startMark = TimeSource.Monotonic.markNow()
            PlayerWatchPartyState(
                WatchPartyCoordinator(
                    room = party.room,
                    localMemberId = party.localMemberId,
                    transport = party.transport,
                    player = PlayerWatchPartyPort(this@BindWatchPartyEffects),
                    nowMs = { startMark.elapsedNow().inWholeMilliseconds },
                ),
            )
        }
        watchParty = session
        session?.coordinator?.start()
        onDispose {
            session?.coordinator?.stop()
            session?.seekReportJob?.cancel()
            if (watchParty === session) watchParty = null
        }
    }

    val coordinator = watchParty?.coordinator ?: return
    val connection = activeParty?.connection ?: return

    // The connection drops and comes back on its own; catch up each time it does.
    LaunchedEffect(coordinator, connection) {
        connection.collect { state ->
            if (state == WatchPartyConnection.Connected) coordinator.onReconnected()
        }
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

    // Every member plays from their own sources, so the guest picks a source for the host's episode.
    override fun openContent(content: WatchPartyContent, positionMs: Long) {
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
        runtime.episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = episode)
        runtime.showEpisodesPanel = true
        runtime.showSourcesPanel = false
    }
}
