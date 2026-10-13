package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import kotlin.math.abs
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Keeps one member's player in step with the room. The controller is the single source of truth:
 * it broadcasts its state, and everyone else follows it. Followers never broadcast playback, so a
 * command applied on a follower can never echo back into the room.
 */
@OptIn(ExperimentalUuidApi::class)
class WatchPartyCoordinator(
    private val localMemberId: String,
    private val transport: WatchPartyTransport,
    private val player: WatchPartyPlayerPort,
    private val nowMs: () -> Long,
    /** Who controls playback right now; see [WatchPartyControl]. */
    private val controllerId: () -> String,
    private val driftToleranceMs: Long = DEFAULT_DRIFT_TOLERANCE_MS,
    private val settleWindowMs: Long = DEFAULT_SETTLE_WINDOW_MS,
    private val epoch: String = Uuid.random().toString(),
) {
    val isController: Boolean
        get() = localMemberId == controllerId()

    private var started = false

    // Controller side.
    private var nextSeq = 1L

    // Follower side.
    private var controllerEpoch: String? = null
    private var lastControllerSeq = 0L
    private var targetPlayback: WatchPartyPlayback? = null
    private var targetReceivedAtMs = 0L
    private var settlingUntilMs = Long.MIN_VALUE
    private var requestedContent: WatchPartyContent? = null

    // True when the controller deliberately changed something (seek, play/pause, episode), as opposed
    // to a routine heartbeat. Deliberate changes skip the correction cooldown.
    private var targetJumped = false

    // Slow sources keep loading after a seek, so landing exactly on the controller's position leaves us
    // behind again, and we would chase forever. Measure how long a seek takes and aim that far ahead.
    private var seekLeadMs = 0L
    private var correctionStartedAtMs: Long? = null
    private var sawLoadingSinceCorrection = false
    private var lastCorrectionAtMs: Long? = null

    fun start() {
        if (started) return
        started = true
        transport.setListener(::onMessage)
        if (isController) broadcastState() else requestState()
    }

    fun stop() {
        if (!started) return
        started = false
        transport.setListener(null)
    }

    /** Call on every local play, pause, seek or episode change, and when the player finishes loading. */
    fun onLocalPlaybackChanged() {
        if (!started) return
        if (isController) {
            broadcastState()
            return
        }
        measureCorrectionCost()
        if (nowMs() >= settlingUntilMs) enforceTargetPlayback()
    }

    /** Call about every 5 seconds. The controller re-sends its state so lost messages and drift heal on their own. */
    fun onHeartbeat() {
        if (!started) return
        if (isController) broadcastState()
    }

    /** Call when the connection comes back after dropping. */
    fun onReconnected() {
        if (!started) return
        if (isController) broadcastState() else requestState()
    }

    /** Call when control moves to another member. */
    fun onControllerChanged() {
        if (!started) return
        if (isController) broadcastState() else requestState()
    }

    private fun onMessage(message: WatchPartyMessage) {
        if (message.senderId == localMemberId) return
        when (message) {
            is WatchPartyMessage.StateRequest -> if (isController) broadcastState()
            is WatchPartyMessage.State -> onControllerState(message)
            else -> Unit
        }
    }

    private fun broadcastState() {
        // While the controller is still loading, followers wait for the first real state.
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

    private fun onControllerState(message: WatchPartyMessage.State) {
        if (isController || message.senderId != controllerId()) return
        // A new epoch means a different controller, or the same one after a restart.
        if (message.epoch == controllerEpoch && message.seq <= lastControllerSeq) return
        controllerEpoch = message.epoch
        lastControllerSeq = message.seq
        val previous = targetPlayback
        val next = message.playback
        targetJumped = targetJumped ||
            previous == null ||
            previous.content != next.content ||
            previous.isPlaying != next.isPlaying ||
            abs(next.positionMs - expectedTargetPositionMs(previous)) > driftToleranceMs
        targetPlayback = next
        targetReceivedAtMs = nowMs()
        enforceTargetPlayback()
    }

    private fun measureCorrectionCost() {
        val startedAtMs = correctionStartedAtMs ?: return
        val local = player.currentPlayback()
        if (local == null) {
            sawLoadingSinceCorrection = true
            return
        }
        if (targetPlayback?.isPlaying == true && !local.isPlaying) return
        // A seek that never showed loading was effectively instant.
        val costMs = if (sawLoadingSinceCorrection) (nowMs() - startedAtMs).coerceIn(0L, MAX_SEEK_LEAD_MS) else 0L
        seekLeadMs = if (seekLeadMs == 0L) costMs else (seekLeadMs + costMs) / 2
        correctionStartedAtMs = null
    }

    private fun enforceTargetPlayback() {
        val target = targetPlayback ?: return
        val expectedPositionMs = expectedTargetPositionMs(target)

        if (player.currentContent() != target.content) {
            // Ask once per content change; the follower may still be picking a source when the next heartbeat arrives.
            if (requestedContent != target.content) {
                requestedContent = target.content
                log.d { "Opening ${target.content.videoId} to follow the controller" }
                player.openContent(target.content, expectedPositionMs, target.source)
                startSettling()
            }
            return
        }
        requestedContent = null
        // Still loading. The player reports again once ready, and we line up then.
        val local = player.currentPlayback() ?: return

        val now = nowMs()
        var acted = false
        val driftMs = local.positionMs - expectedPositionMs
        val coolingDown = lastCorrectionAtMs?.let { now - it < CORRECTION_COOLDOWN_MS } == true
        if (abs(driftMs) > driftToleranceMs && (targetJumped || !coolingDown)) {
            val leadMs = if (target.isPlaying) seekLeadMs else 0L
            log.d { "Correcting drift of ${driftMs}ms, seeking to ${expectedPositionMs + leadMs} (lead ${leadMs}ms)" }
            player.seekTo(expectedPositionMs + leadMs)
            lastCorrectionAtMs = now
            correctionStartedAtMs = now
            sawLoadingSinceCorrection = false
            acted = true
        }
        targetJumped = false
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
    private fun expectedTargetPositionMs(target: WatchPartyPlayback): Long =
        if (target.isPlaying) {
            target.positionMs + (nowMs() - targetReceivedAtMs).coerceAtLeast(0L)
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
        private const val CORRECTION_COOLDOWN_MS = 6_000L
        private const val MAX_SEEK_LEAD_MS = 10_000L
        private val log = Logger.withTag("WatchPartySync")
    }
}
