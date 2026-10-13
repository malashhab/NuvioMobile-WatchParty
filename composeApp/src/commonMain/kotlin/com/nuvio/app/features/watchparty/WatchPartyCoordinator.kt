package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import kotlin.math.abs
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Keeps one member's player in step with the room. The controller is the single source of truth:
 * it broadcasts its state, and everyone else follows it. Followers never broadcast playback, so a
 * command applied on a follower can never echo back into the room.
 *
 * Everyone starts together: when the controller plays, seeks or changes episode, or anyone's video
 * stalls, the controller holds the room at one position until every member reports ready.
 */
@OptIn(ExperimentalUuidApi::class)
class WatchPartyCoordinator(
    private val localMemberId: String,
    private val transport: WatchPartyTransport,
    private val player: WatchPartyPlayerPort,
    private val nowMs: () -> Long,
    /** Who controls playback right now; see [WatchPartyControl]. */
    private val controllerId: () -> String,
    private val listener: Listener = object : Listener {},
    private val driftToleranceMs: Long = DEFAULT_DRIFT_TOLERANCE_MS,
    private val settleWindowMs: Long = DEFAULT_SETTLE_WINDOW_MS,
    private val epoch: String = Uuid.random().toString(),
) {
    /** Room events worth telling the user about. */
    interface Listener {
        fun onControllerContactChanged(lost: Boolean) {}

        /** Controller: still waiting for these members. */
        fun onWaitingForMembers(memberIds: List<String>) {}

        /** Controller: gave up waiting for these members. */
        fun onContinuedWithout(memberIds: List<String>) {}

        /** Follower: we're ready, but the room is still waiting for others. */
        fun onWaitingForRoom() {}
    }

    val isController: Boolean
        get() = localMemberId == controllerId()

    private var started = false

    // Controller side.
    private var nextSeq = 1L
    private val readyCheck = WatchPartyReadyCheck()
    private val followerSeenAtMs = mutableMapOf<String, Long>()
    private val followerStalledSinceMs = mutableMapOf<String, Long>()

    // Members we stopped waiting for; not waited for again until their video recovers.
    private val givenUpOn = mutableSetOf<String>()

    // Whether followers were last told we're playing.
    private var sentPlaying = false

    // Our own play and pause commands echo back as player events a moment later; don't treat those as
    // new actions.
    private var controllerSettleUntilMs = Long.MIN_VALUE

    // Follower side.
    private var controllerEpoch: String? = null
    private var lastControllerSeq = 0L
    private var targetPlayback: WatchPartyPlayback? = null
    private var targetReceivedAtMs = 0L
    private var settlingUntilMs = Long.MIN_VALUE
    private var requestedContent: WatchPartyContent? = null
    private var lastContactAtMs = 0L
    private var contactLost = false
    private var reportedStall: Boolean? = null
    private var targetHoldId: String? = null
    private var acknowledgedHoldId: String? = null
    private var heldSinceMs: Long? = null
    private var announcedHoldId: String? = null

    // One-way network delay to the controller, estimated from ping round trips. The controller's
    // position is already this old when it arrives, so we aim this much further ahead.
    private val roundTripSamplesMs = ArrayDeque<Long>()
    private var oneWayLatencyMs = 0L

    // Small gaps are closed by playing slightly faster or slower instead of seeking: -1 slower, 0 normal, 1 faster.
    private var speedNudge = 0
    private var appliedSpeed: Float? = null

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
        lastContactAtMs = nowMs()
        transport.setListener(::onMessage)
        if (isController) broadcastState() else requestState()
    }

    fun stop() {
        if (!started) return
        started = false
        transport.setListener(null)
        // Don't leave the player running slightly fast or slow after leaving the room.
        if (speedNudge != 0) player.setSpeed(targetPlayback?.speed ?: 1f)
        speedNudge = 0
    }

    /** Call on every local play, pause, seek or episode change, and when the player finishes loading. */
    fun onLocalPlaybackChanged() {
        if (!started) return
        if (isController) {
            onControllerPlaybackChanged()
            return
        }
        measureCorrectionCost()
        reportStallIfChanged()
        if (nowMs() >= settlingUntilMs) enforceTargetPlayback()
        acknowledgeHoldIfReady()
    }

    /** The controller pressed play. Returns false when not in control. */
    fun requestPlay(): Boolean {
        if (!started || !isController) return false
        if (readyCheck.isHolding) {
            // Pressing play while waiting means "don't wait".
            readyCheck.cancel()
            releaseHold()
        } else {
            startHold()
        }
        return true
    }

    /** The controller pressed pause. Returns false when not in control. */
    fun requestPause(): Boolean {
        if (!started || !isController) return false
        readyCheck.cancel()
        player.pause()
        controllerSettleUntilMs = nowMs() + settleWindowMs
        sendConstructedState(isPlaying = false, holdId = null)
        return true
    }

    /** Call after the controller seeks; playing rooms line up at the new position before continuing. */
    fun onLocalSeek() {
        if (!started || !isController) return
        if (sentPlaying || readyCheck.isHolding) startHold(restart = true) else broadcastState()
    }

    /** Call about every 5 seconds, so lost messages and drift heal on their own. */
    fun onHeartbeat() {
        if (!started) return
        if (!isController) {
            // Measures the network delay, and lets the controller know we're still in the player.
            transport.send(WatchPartyMessage.Ping(senderId = localMemberId, sentAtMs = nowMs()))
            return
        }
        val holdId = readyCheck.holdId
        when {
            holdId != null -> sendConstructedState(isPlaying = false, holdId = holdId)
            nowMs() < controllerSettleUntilMs -> Unit
            player.currentPlayback() != null -> broadcastState()
            // Loading, so there is no position to share; still let followers know we're here.
            else -> transport.send(WatchPartyMessage.KeepAlive(senderId = localMemberId))
        }
    }

    /** Call about once a second, for timers that need finer steps than the heartbeat. */
    fun onTick() {
        if (!started) return
        if (isController) {
            holdForStalledFollowers()
            evaluateHold()
        } else {
            checkControllerContact()
            // Re-checks the gap every second, so speed nudges start and stop on time.
            if (nowMs() >= settlingUntilMs) enforceTargetPlayback()
            acknowledgeHoldIfReady()
            announceRoomWaitIfLong()
        }
    }

    /** Call when the connection comes back after dropping. */
    fun onReconnected() {
        if (!started) return
        if (isController) broadcastState() else requestState()
    }

    /** Call when control moves to another member. */
    fun onControllerChanged() {
        if (!started) return
        // A nudged speed must not become the room's speed if we take control.
        if (speedNudge != 0) player.setSpeed(targetPlayback?.speed ?: 1f)
        speedNudge = 0
        appliedSpeed = null
        roundTripSamplesMs.clear()
        oneWayLatencyMs = 0L
        readyCheck.cancel()
        followerSeenAtMs.clear()
        followerStalledSinceMs.clear()
        givenUpOn.clear()
        sentPlaying = false
        reportedStall = null
        targetHoldId = null
        acknowledgedHoldId = null
        heldSinceMs = null
        lastContactAtMs = nowMs()
        if (isController) broadcastState() else requestState()
    }

    private fun onMessage(message: WatchPartyMessage) {
        if (message.senderId == localMemberId) return
        // Any message from a follower shows they're still in the player.
        if (isController) followerSeenAtMs[message.senderId] = nowMs()
        when (message) {
            is WatchPartyMessage.StateRequest -> if (isController) {
                val holdId = readyCheck.holdId
                if (holdId != null) sendConstructedState(isPlaying = false, holdId = holdId) else broadcastState()
            }
            is WatchPartyMessage.State -> onControllerState(message)
            is WatchPartyMessage.KeepAlive -> if (!isController && message.senderId == controllerId()) onControllerContact()
            is WatchPartyMessage.Ping -> if (isController) {
                transport.send(
                    WatchPartyMessage.Pong(senderId = localMemberId, to = message.senderId, sentAtMs = message.sentAtMs),
                )
            }
            is WatchPartyMessage.Pong -> if (
                !isController && message.to == localMemberId && message.senderId == controllerId()
            ) {
                recordRoundTrip(nowMs() - message.sentAtMs)
                onControllerContact()
            }
            is WatchPartyMessage.LoadingChanged -> if (isController) {
                if (message.isLoading) {
                    followerStalledSinceMs.getOrPut(message.senderId) { nowMs() }
                } else {
                    followerStalledSinceMs.remove(message.senderId)
                    givenUpOn.remove(message.senderId)
                }
            }
            is WatchPartyMessage.Ready -> if (isController) {
                followerStalledSinceMs.remove(message.senderId)
                givenUpOn.remove(message.senderId)
                readyCheck.onReady(message.senderId, message.holdId)
                evaluateHold()
            }
            else -> Unit
        }
    }

    // Controller side.

    private fun onControllerPlaybackChanged() {
        if (readyCheck.isHolding) {
            evaluateHold()
            return
        }
        val playback = player.currentPlayback()
        if (playback == null) {
            // Our own video stalled mid-play, or is opening a new episode: hold everyone until we're back.
            if (sentPlaying) startHold()
            return
        }
        if (nowMs() < controllerSettleUntilMs) return
        // Started some other way than our play button (e.g. a notification): line everyone up first.
        if (playback.isPlaying && !sentPlaying) startHold() else broadcastState()
    }

    private fun startHold(restart: Boolean = false) {
        if (restart) readyCheck.cancel()
        val holdId = readyCheck.start(idPrefix = epoch, nowMs = nowMs(), members = activeFollowers())
        log.d { "Holding the room until everyone is ready ($holdId)" }
        player.pause()
        controllerSettleUntilMs = nowMs() + settleWindowMs
        sendConstructedState(isPlaying = false, holdId = holdId)
        evaluateHold()
    }

    private fun evaluateHold() {
        val decision = readyCheck.evaluate(
            nowMs = nowMs(),
            controllerReady = player.currentPlayback() != null,
            activeMembers = activeFollowers(),
        )
        when (decision) {
            WatchPartyReadyCheck.Decision.None -> Unit
            is WatchPartyReadyCheck.Decision.Announce -> listener.onWaitingForMembers(decision.memberIds)
            WatchPartyReadyCheck.Decision.Release -> releaseHold()
            is WatchPartyReadyCheck.Decision.ReleaseWithout -> {
                log.d { "Continuing without ${decision.memberIds}" }
                givenUpOn += decision.memberIds
                listener.onContinuedWithout(decision.memberIds)
                releaseHold()
            }
        }
    }

    private fun releaseHold() {
        log.d { "Everyone is ready; playing" }
        player.play()
        controllerSettleUntilMs = nowMs() + settleWindowMs
        // Built from intent, not from the player, which reports the change a moment later.
        sendConstructedState(isPlaying = true, holdId = null)
    }

    private fun holdForStalledFollowers() {
        if (readyCheck.isHolding || !sentPlaying) return
        val now = nowMs()
        val active = activeFollowers()
        // Ignore brief hiccups; pause everyone only for a real stall.
        val stalled = followerStalledSinceMs.any { (memberId, sinceMs) ->
            memberId in active && now - sinceMs >= STALL_GRACE_MS
        }
        if (stalled) startHold()
    }

    private fun activeFollowers(): Set<String> {
        val now = nowMs()
        return followerSeenAtMs.filterValues { now - it < FOLLOWER_ACTIVE_MS }.keys - givenUpOn
    }

    private fun broadcastState() {
        // While the controller is still loading, followers wait for the first real state.
        val playback = player.currentPlayback() ?: return
        send(playback, holdId = null)
    }

    private fun sendConstructedState(isPlaying: Boolean, holdId: String?) {
        val content = player.currentContent() ?: return
        val positionMs = player.currentPositionMs() ?: return
        send(
            WatchPartyPlayback(
                content = content,
                isPlaying = isPlaying,
                positionMs = positionMs,
                source = player.currentSource(),
            ),
            holdId = holdId,
        )
    }

    private fun send(playback: WatchPartyPlayback, holdId: String?) {
        sentPlaying = playback.isPlaying
        transport.send(
            WatchPartyMessage.State(
                senderId = localMemberId,
                epoch = epoch,
                seq = nextSeq++,
                playback = playback,
                holdId = holdId,
            ),
        )
    }

    // Follower side.

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
        if (message.holdId != targetHoldId) {
            targetHoldId = message.holdId
            heldSinceMs = if (message.holdId != null) nowMs() else null
        }
        onControllerContact()
        enforceTargetPlayback()
        reportStallIfChanged()
        acknowledgeHoldIfReady()
    }

    private fun acknowledgeHoldIfReady() {
        val holdId = targetHoldId ?: return
        if (holdId == acknowledgedHoldId) return
        val target = targetPlayback ?: return
        if (player.currentContent() != target.content) return
        val local = player.currentPlayback() ?: return
        if (abs(local.positionMs - target.positionMs) > driftToleranceMs) return
        acknowledgedHoldId = holdId
        transport.send(WatchPartyMessage.Ready(senderId = localMemberId, holdId = holdId))
    }

    private fun announceRoomWaitIfLong() {
        val holdId = targetHoldId ?: return
        val since = heldSinceMs ?: return
        if (holdId != acknowledgedHoldId || holdId == announcedHoldId) return
        if (nowMs() - since < ROOM_WAIT_ANNOUNCE_MS) return
        announcedHoldId = holdId
        listener.onWaitingForRoom()
    }

    // The fastest recent round trip is the best guess; slower ones were held up somewhere along the way.
    private fun recordRoundTrip(roundTripMs: Long) {
        if (roundTripMs < 0) return
        roundTripSamplesMs.addLast(roundTripMs)
        if (roundTripSamplesMs.size > ROUND_TRIP_SAMPLES) roundTripSamplesMs.removeFirst()
        oneWayLatencyMs = (roundTripSamplesMs.min() / 2).coerceAtMost(MAX_ONE_WAY_LATENCY_MS)
    }

    private fun onControllerContact() {
        lastContactAtMs = nowMs()
        if (contactLost) {
            contactLost = false
            listener.onControllerContactChanged(lost = false)
        }
    }

    private fun checkControllerContact() {
        val target = targetPlayback ?: return
        if (contactLost || nowMs() - lastContactAtMs < CONTACT_TIMEOUT_MS) return
        contactLost = true
        log.d { "No word from the controller; pausing until they're back" }
        // Freeze where the controller should be by now; their next update lines us up again.
        if (target.isPlaying) {
            targetPlayback = target.copy(isPlaying = false, positionMs = expectedTargetPositionMs(target))
            targetReceivedAtMs = nowMs()
            targetJumped = true
            enforceTargetPlayback()
        }
        listener.onControllerContactChanged(lost = true)
    }

    /** Tell the controller when our video stalls or recovers, so it can hold the room for us. */
    private fun reportStallIfChanged() {
        val target = targetPlayback ?: return
        val isStalled = player.currentContent() != target.content || player.currentPlayback() == null
        if (isStalled == reportedStall) return
        reportedStall = isStalled
        transport.send(WatchPartyMessage.LoadingChanged(senderId = localMemberId, isLoading = isStalled))
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
        var seeked = false
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
            seeked = true
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
        adjustSpeed(target, canNudge = target.isPlaying && local.isPlaying && !seeked, driftMs = driftMs)
    }

    private fun adjustSpeed(target: WatchPartyPlayback, canNudge: Boolean, driftMs: Long) {
        speedNudge = when {
            !canNudge -> 0
            abs(driftMs) <= NUDGE_STOP_MS -> 0
            // Behind plays faster, ahead plays slower.
            abs(driftMs) >= NUDGE_START_MS -> if (driftMs < 0) 1 else -1
            // In between: keep nudging until we're close, rather than stopping at the edge.
            else -> speedNudge
        }
        val speed = target.speed * (1f + speedNudge * NUDGE_FRACTION)
        if (speed == appliedSpeed) return
        appliedSpeed = speed
        player.setSpeed(speed)
    }

    // Where the controller is now: its last report, moved on by the time since it was sent.
    private fun expectedTargetPositionMs(target: WatchPartyPlayback): Long =
        if (target.isPlaying) {
            val elapsedMs = (nowMs() - targetReceivedAtMs).coerceAtLeast(0L) + oneWayLatencyMs
            target.positionMs + (elapsedMs * target.speed).toLong()
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

        // Three missed heartbeats.
        private const val CONTACT_TIMEOUT_MS = 15_000L

        // Followers send a keep-alive every heartbeat; three missed means they left the player.
        private const val FOLLOWER_ACTIVE_MS = 15_000L
        private const val STALL_GRACE_MS = 1_000L
        private const val ROOM_WAIT_ANNOUNCE_MS = 2_000L

        private const val ROUND_TRIP_SAMPLES = 5
        private const val MAX_ONE_WAY_LATENCY_MS = 1_000L

        // Start nudging past 150 ms apart, stop once within 40 ms; 5% faster or slower is barely noticeable.
        private const val NUDGE_START_MS = 150L
        private const val NUDGE_STOP_MS = 40L
        private const val NUDGE_FRACTION = 0.05f
        private val log = Logger.withTag("WatchPartySync")
    }
}
