package com.nuvio.app.features.watchparty

import kotlin.math.abs
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Keeps one member's player in step with the room. The host is the single source of truth:
 * the host broadcasts its state, and guests follow it. Guests never broadcast playback, so a
 * command applied on a guest can never echo back into the room.
 */
@OptIn(ExperimentalUuidApi::class)
class WatchPartyCoordinator(
    private val room: WatchPartyRoom,
    private val localMemberId: String,
    private val transport: WatchPartyTransport,
    private val player: WatchPartyPlayerPort,
    private val nowMs: () -> Long,
    private val driftToleranceMs: Long = DEFAULT_DRIFT_TOLERANCE_MS,
    private val settleWindowMs: Long = DEFAULT_SETTLE_WINDOW_MS,
    private val epoch: String = Uuid.random().toString(),
) {
    val isHost: Boolean
        get() = localMemberId == room.hostMemberId

    private var started = false

    // Host side.
    private var nextSeq = 1L

    // Guest side.
    private var hostEpoch: String? = null
    private var lastHostSeq = 0L
    private var hostPlayback: WatchPartyPlayback? = null
    private var hostPlaybackReceivedAtMs = 0L
    private var settlingUntilMs = Long.MIN_VALUE
    private var requestedContent: WatchPartyContent? = null

    fun start() {
        if (started) return
        started = true
        transport.setListener(::onMessage)
        if (isHost) broadcastState() else requestState()
    }

    fun stop() {
        if (!started) return
        started = false
        transport.setListener(null)
    }

    /** Call on every local play, pause, seek or episode change, and when the player finishes loading. */
    fun onLocalPlaybackChanged() {
        if (!started) return
        if (isHost) {
            broadcastState()
        } else if (nowMs() >= settlingUntilMs) {
            enforceHostPlayback()
        }
    }

    /** Call about every 5 seconds. The host re-sends its state so lost messages and drift heal on their own. */
    fun onHeartbeat() {
        if (!started) return
        if (isHost) broadcastState()
    }

    /** Call when the connection comes back after dropping. */
    fun onReconnected() {
        if (!started) return
        if (isHost) broadcastState() else requestState()
    }

    private fun onMessage(message: WatchPartyMessage) {
        if (message.senderId == localMemberId) return
        when (message) {
            is WatchPartyMessage.StateRequest -> if (isHost) broadcastState()
            is WatchPartyMessage.State -> onHostState(message)
        }
    }

    private fun broadcastState() {
        // While the host is still loading, guests wait for the first real state.
        val playback = player.currentPlayback() ?: return
        transport.send(
            WatchPartyMessage.State(
                senderId = localMemberId,
                epoch = epoch,
                seq = nextSeq++,
                playback = playback,
            ),
        )
    }

    private fun requestState() {
        transport.send(WatchPartyMessage.StateRequest(senderId = localMemberId))
    }

    private fun onHostState(message: WatchPartyMessage.State) {
        if (isHost || message.senderId != room.hostMemberId) return
        // A new epoch means the host restarted and its sequence numbers began again.
        if (message.epoch == hostEpoch && message.seq <= lastHostSeq) return
        hostEpoch = message.epoch
        lastHostSeq = message.seq
        hostPlayback = message.playback
        hostPlaybackReceivedAtMs = nowMs()
        enforceHostPlayback()
    }

    private fun enforceHostPlayback() {
        val target = hostPlayback ?: return
        val expectedPositionMs = expectedHostPositionMs(target)

        if (player.currentContent() != target.content) {
            // Ask once per content change; the guest may still be picking a source when the next heartbeat arrives.
            if (requestedContent != target.content) {
                requestedContent = target.content
                player.openContent(target.content, expectedPositionMs)
                startSettling()
            }
            return
        }
        requestedContent = null
        // Still loading. The player reports again once ready, and we line up then.
        val local = player.currentPlayback() ?: return

        var acted = false
        if (abs(local.positionMs - expectedPositionMs) > driftToleranceMs) {
            player.seekTo(expectedPositionMs)
            acted = true
        }
        if (target.isPlaying && !local.isPlaying) {
            player.play()
            acted = true
        } else if (!target.isPlaying && local.isPlaying) {
            player.pause()
            acted = true
        }
        if (acted) startSettling()
    }

    // Network delay is ignored for now; it is usually well under the drift tolerance.
    private fun expectedHostPositionMs(target: WatchPartyPlayback): Long =
        if (target.isPlaying) {
            target.positionMs + (nowMs() - hostPlaybackReceivedAtMs).coerceAtLeast(0L)
        } else {
            target.positionMs
        }

    // The player reports changes while it applies our own command; ignore those briefly.
    private fun startSettling() {
        settlingUntilMs = nowMs() + settleWindowMs
    }

    companion object {
        const val DEFAULT_DRIFT_TOLERANCE_MS = 2_000L
        const val DEFAULT_SETTLE_WINDOW_MS = 1_500L
    }
}
