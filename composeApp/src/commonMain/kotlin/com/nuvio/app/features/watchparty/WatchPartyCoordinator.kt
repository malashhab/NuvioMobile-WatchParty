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
    /** True once for a member the owner just removed; see [WatchPartyControl.consumeRemoval]. */
    private val wasRemoved: (memberId: String) -> Boolean = { false },
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

        /** Someone closed the video; [paused] when the room was playing and stopped for them. */
        fun onMemberLeftVideo(memberId: String, paused: Boolean) {}

        /** Controller: a follower pressed pause. */
        fun onPauseRequested(memberId: String) {}

        /** Follower: our pause request went to the controller. */
        fun onPauseRequestSent() {}
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

    // Pings waiting for an answer: follower to (their send time, when we received it). Answered inside
    // our next update rather than as separate messages, which every follower would otherwise receive.
    private val pendingPings = mutableMapOf<String, Pair<Long, Long>>()

    // The source description only matters when it changes, so it's sent a few times after each change
    // (in case one update is lost) and then left out of routine updates.
    private var lastSentSource: WatchPartySource? = null
    private var lastSentContent: WatchPartyContent? = null
    private var sourceRepeatsLeft = 0

    // A seek usually starts loading first, which already holds the room at the new position.
    private var holdStartedAtMs: Long? = null
    private var holdPositionMs: Long? = null

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

    // Ping often while settling in, after reconnecting or when sync drifts; rarely once things are steady.
    private var lastPingAtMs: Long? = null
    private var lastPauseRequestAtMs: Long? = null
    private var fastPingUntilMs = Long.MIN_VALUE
    private var lastStateRequestAtMs: Long? = null

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
        pingFastFor(SETTLE_IN_FAST_PING_MS)
        transport.setListener(::onMessage)
        if (isController) broadcastState() else requestState()
    }

    fun stop() {
        if (!started) return
        if (!isController) transport.send(WatchPartyMessage.Leaving(senderId = localMemberId))
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

    /**
     * A follower pressed pause while the room plays: asks the controller instead. Returns false when
     * that doesn't apply (we're in control, or the room isn't playing), so the caller can explain.
     */
    fun requestPauseFromFollower(): Boolean {
        if (!started || isController || targetPlayback?.isPlaying != true) return false
        val now = nowMs()
        val last = lastPauseRequestAtMs
        // Repeated taps within a few seconds count as one request.
        if (last == null || now - last >= PAUSE_REQUEST_INTERVAL_MS) {
            lastPauseRequestAtMs = now
            transport.send(WatchPartyMessage.PauseRequest(senderId = localMemberId))
            listener.onPauseRequestSent()
        }
        return true
    }

    /** Call after the controller seeks; playing rooms line up at the new position before continuing. */
    fun onLocalSeek() {
        if (!started || !isController) return
        if (isHoldingAtCurrentPosition()) return
        if (sentPlaying || readyCheck.isHolding) startHold(restart = true) else broadcastState()
    }

    /** Call about every 5 seconds, so lost messages and drift heal on their own. */
    fun onHeartbeat() {
        if (!started) return
        if (!isController) {
            pingIfDue()
            return
        }
        val holdId = readyCheck.holdId
        when {
            holdId != null -> sendConstructedState(isPlaying = false, holdId = holdId)
            nowMs() < controllerSettleUntilMs -> Unit
            player.currentPlayback() != null -> broadcastState()
            // Loading, so there is no position to share; still let followers know we're here.
            else -> transport.send(WatchPartyMessage.KeepAlive(senderId = localMemberId, pongs = takePingEchoes()))
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
        pingFastFor(SETTLE_IN_FAST_PING_MS)
        // Updates sent while we were away were dropped, so include the source again.
        if (isController) broadcastState(forceSource = true) else requestState()
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
        pendingPings.clear()
        lastSentSource = null
        lastSentContent = null
        lastContactAtMs = nowMs()
        pingFastFor(SETTLE_IN_FAST_PING_MS)
        if (isController) broadcastState() else requestState(force = true)
    }

    private fun onMessage(message: WatchPartyMessage) {
        if (message.senderId == localMemberId) return
        // Any message from a follower shows they're still in the player.
        if (isController) followerSeenAtMs[message.senderId] = nowMs()
        when (message) {
            is WatchPartyMessage.StateRequest -> if (isController) {
                // A newcomer needs the full picture, source included.
                val holdId = readyCheck.holdId
                if (holdId != null) {
                    sendConstructedState(isPlaying = false, holdId = holdId, forceSource = true)
                } else {
                    broadcastState(forceSource = true)
                }
            }
            is WatchPartyMessage.State -> onControllerState(message)
            is WatchPartyMessage.KeepAlive -> if (!isController && message.senderId == controllerId()) {
                recordPingEchoes(message.pongs)
                onControllerContact()
            }
            is WatchPartyMessage.Ping -> if (isController) {
                pendingPings[message.senderId] = message.sentAtMs to nowMs()
            }
            is WatchPartyMessage.Leaving -> {
                // Someone the owner removed is expected to go; that's no reason to stop the video.
                val removed = wasRemoved(message.senderId)
                var paused = false
                if (isController) {
                    followerSeenAtMs.remove(message.senderId)
                    followerStalledSinceMs.remove(message.senderId)
                    pendingPings.remove(message.senderId)
                    // Stop for everyone when someone walks away mid-video, rather than leave them behind.
                    if (!removed && (sentPlaying || readyCheck.isHolding)) paused = requestPause()
                    if (!paused) evaluateHold()
                }
                if (!removed) listener.onMemberLeftVideo(message.senderId, paused)
            }
            is WatchPartyMessage.PauseRequest -> if (isController && sentPlaying) {
                listener.onPauseRequested(message.senderId)
            }
            // Separate pongs come only from older versions of the app.
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
        val wasHolding = readyCheck.isHolding
        val holdId = readyCheck.start(idPrefix = epoch, nowMs = nowMs(), members = activeFollowers())
        if (!wasHolding) {
            holdStartedAtMs = nowMs()
            holdPositionMs = player.currentPositionMs()
        }
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

    /** True when the loading that a seek causes already started a hold here, so the seek needn't start another. */
    private fun isHoldingAtCurrentPosition(): Boolean {
        val startedAt = holdStartedAtMs ?: return false
        if (!readyCheck.isHolding || nowMs() - startedAt > HOLD_REUSE_MS) return false
        val heldAt = holdPositionMs ?: return false
        val now = player.currentPositionMs() ?: return false
        return abs(now - heldAt) <= driftToleranceMs
    }

    private fun broadcastState(forceSource: Boolean = false) {
        // While the controller is still loading, followers wait for the first real state.
        val playback = player.currentPlayback() ?: return
        send(playback, holdId = null, forceSource = forceSource)
    }

    private fun sendConstructedState(isPlaying: Boolean, holdId: String?, forceSource: Boolean = false) {
        val content = player.currentContent() ?: return
        val positionMs = player.currentPositionMs() ?: return
        send(
            WatchPartyPlayback(
                content = content,
                isPlaying = isPlaying,
                positionMs = positionMs,
                source = player.currentSource(),
                speed = player.currentPlayback()?.speed ?: 1f,
            ),
            holdId = holdId,
            forceSource = forceSource,
        )
    }

    private fun send(playback: WatchPartyPlayback, holdId: String?, forceSource: Boolean = false) {
        sentPlaying = playback.isPlaying
        if (forceSource || playback.source != lastSentSource || playback.content != lastSentContent) {
            sourceRepeatsLeft = SOURCE_REPEATS
        }
        lastSentSource = playback.source
        lastSentContent = playback.content
        val includeSource = sourceRepeatsLeft > 0
        if (includeSource) sourceRepeatsLeft--
        transport.send(
            WatchPartyMessage.State(
                senderId = localMemberId,
                epoch = epoch,
                seq = nextSeq++,
                playback = if (includeSource) playback else playback.copy(source = null),
                holdId = holdId,
                pongs = takePingEchoes(),
            ),
        )
    }

    /** Answers to the pings received since our last update, with how long each waited here. */
    private fun takePingEchoes(): List<PingEcho> {
        if (pendingPings.isEmpty()) return emptyList()
        val now = nowMs()
        val echoes = pendingPings.map { (memberId, times) ->
            val (sentAtMs, receivedAtMs) = times
            PingEcho(to = memberId, sentAtMs = sentAtMs, heldMs = (now - receivedAtMs).coerceAtLeast(0L))
        }
        pendingPings.clear()
        return echoes
    }

    // Follower side.

    /** One request is enough; joining otherwise asks up to three times in a row (start, connect, control). */
    private fun requestState(force: Boolean = false) {
        val now = nowMs()
        val last = lastStateRequestAtMs
        if (!force && last != null && now - last < STATE_REQUEST_DEDUPE_MS) return
        lastStateRequestAtMs = now
        transport.send(WatchPartyMessage.StateRequest(senderId = localMemberId))
    }

    private fun pingIfDue() {
        val now = nowMs()
        val intervalMs = if (now < fastPingUntilMs) FAST_PING_INTERVAL_MS else SLOW_PING_INTERVAL_MS
        val last = lastPingAtMs
        if (last != null && now - last < intervalMs - PING_INTERVAL_SLACK_MS) return
        lastPingAtMs = now
        transport.send(WatchPartyMessage.Ping(senderId = localMemberId, sentAtMs = now))
    }

    private fun pingFastFor(durationMs: Long) {
        fastPingUntilMs = maxOf(fastPingUntilMs, nowMs() + durationMs)
    }

    // Round trip = time since we sent the ping, minus the time it waited at the controller.
    private fun recordPingEchoes(echoes: List<PingEcho>) {
        echoes.firstOrNull { it.to == localMemberId }?.let { echo ->
            recordRoundTrip(nowMs() - echo.sentAtMs - echo.heldMs)
        }
    }

    private fun onControllerState(message: WatchPartyMessage.State) {
        if (isController || message.senderId != controllerId()) return
        // A new epoch means a different controller, or the same one after a restart.
        if (message.epoch == controllerEpoch && message.seq <= lastControllerSeq) return
        controllerEpoch = message.epoch
        lastControllerSeq = message.seq
        recordPingEchoes(message.pongs)
        val previous = targetPlayback
        // Routine updates leave out an unchanged source; keep the one we already know.
        val next = message.playback.let {
            if (it.source == null && previous?.content == it.content) it.copy(source = previous.source) else it
        }
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
            pingFastFor(DRIFT_FAST_PING_MS)
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
        // Drifting suggests the network delay changed; measure it more often for a while.
        if (speedNudge != 0) pingFastFor(DRIFT_FAST_PING_MS)
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

        // Followers ping at least every 30 seconds, so 45 seconds of silence means they dropped off.
        // Closing the player normally sends Leaving, so this only matters when a phone loses its connection.
        private const val FOLLOWER_ACTIVE_MS = 45_000L
        private const val STALL_GRACE_MS = 1_000L
        private const val ROOM_WAIT_ANNOUNCE_MS = 2_000L

        private const val ROUND_TRIP_SAMPLES = 5

        // Followers ping every heartbeat while settling in or drifting, otherwise every 30 seconds.
        private const val FAST_PING_INTERVAL_MS = 5_000L
        private const val SLOW_PING_INTERVAL_MS = 30_000L
        private const val PING_INTERVAL_SLACK_MS = 500L
        private const val SETTLE_IN_FAST_PING_MS = 60_000L
        private const val DRIFT_FAST_PING_MS = 30_000L

        private const val STATE_REQUEST_DEDUPE_MS = 2_000L
        private const val HOLD_REUSE_MS = 2_000L

        // The source goes out with the next few updates after it changes, in case one is lost.
        private const val SOURCE_REPEATS = 3

        private const val PAUSE_REQUEST_INTERVAL_MS = 5_000L
        private const val MAX_ONE_WAY_LATENCY_MS = 1_000L

        // Start nudging past 150 ms apart, stop once within 40 ms; 5% faster or slower is barely noticeable.
        private const val NUDGE_START_MS = 150L
        private const val NUDGE_STOP_MS = 40L
        private const val NUDGE_FRACTION = 0.05f
        private val log = Logger.withTag("WatchPartySync")
    }
}
