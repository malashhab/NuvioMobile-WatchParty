package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyCoordinatorTest {
    private val hub = InMemoryWatchPartyHub()
    private var now = 0L
    private var controller = "host"

    private val hostPlayer = FakeWatchPartyPlayer(content = episodeOne, isPlaying = true, positionMs = 60_000L)
    private val guestPlayer = FakeWatchPartyPlayer(content = episodeOne, isPlaying = false, positionMs = 0L)
    private val hostConnection = hub.connect()
    private val guestConnection = hub.connect()

    private val hostEvents = RecordingListener()
    private val guestEvents = RecordingListener()

    private val host = coordinator("host", hostConnection, hostPlayer, hostEvents, epoch = "host-epoch")
    private val guest = coordinator("guest", guestConnection, guestPlayer, guestEvents)

    private class RecordingListener : WatchPartyCoordinator.Listener {
        val events = mutableListOf<String>()

        override fun onControllerContactChanged(lost: Boolean) {
            events += if (lost) "contact-lost" else "contact-back"
        }

        override fun onWaitingForMembers(memberIds: List<String>) {
            events += "waiting:$memberIds"
        }

        override fun onContinuedWithout(memberIds: List<String>) {
            events += "continued-without:$memberIds"
        }

        override fun onWaitingForRoom() {
            events += "waiting-for-room"
        }
    }

    private fun coordinator(
        memberId: String,
        transport: WatchPartyTransport,
        player: WatchPartyPlayerPort,
        listener: WatchPartyCoordinator.Listener,
        epoch: String = "$memberId-epoch",
    ) = WatchPartyCoordinator(
        localMemberId = memberId,
        transport = transport,
        player = player,
        nowMs = { now },
        controllerId = { controller },
        listener = listener,
        epoch = epoch,
    )

    private fun hostState(seq: Long, isPlaying: Boolean, positionMs: Long, epoch: String = "raw-epoch") =
        WatchPartyMessage.State(
            senderId = "host",
            epoch = epoch,
            seq = seq,
            playback = WatchPartyPlayback(content = episodeOne, isPlaying = isPlaying, positionMs = positionMs),
        )

    @Test
    fun guestJoiningCatchesUpToHost() {
        host.start()
        guest.start()

        assertEquals(listOf("seek:60000", "play"), guestPlayer.actions)
    }

    @Test
    fun hostPauseIsMirroredOnGuest() {
        host.start()
        guest.start()
        guestPlayer.actions.clear()

        hostPlayer.isPlaying = false
        host.onLocalPlaybackChanged()

        assertEquals(listOf("pause"), guestPlayer.actions)
    }

    @Test
    fun smallDriftIsIgnored() {
        guestPlayer.isPlaying = true
        guestPlayer.positionMs = 61_500L
        host.start()
        guest.start()

        assertTrue(guestPlayer.actions.isEmpty())
    }

    @Test
    fun largeDriftIsCorrectedUsingTimeSinceHostUpdate() {
        host.start()
        guest.start()
        guestPlayer.actions.clear()

        // Ten seconds later the guest fell behind after buffering.
        now = 10_000L
        guestPlayer.positionMs = 65_000L
        guest.onLocalPlaybackChanged()

        assertEquals(listOf("seek:70000"), guestPlayer.actions)
    }

    @Test
    fun hostEpisodeChangeOpensSameEpisodeOnGuestThenLinesUpWhenReady() {
        host.start()
        guest.start()
        guestPlayer.actions.clear()

        hostPlayer.content = episodeTwo
        hostPlayer.positionMs = 0L
        host.onLocalPlaybackChanged()
        assertEquals(listOf("open:${episodeTwo.videoId}@0"), guestPlayer.actions)

        // The guest needed three seconds to find a source and load it.
        now = 3_000L
        guestPlayer.isReady = true
        guest.onLocalPlaybackChanged()

        assertEquals(
            listOf("open:${episodeTwo.videoId}@0", "seek:3000", "play"),
            guestPlayer.actions,
        )
    }

    @Test
    fun heartbeatsWhileGuestPicksASourceDoNotReopenTheEpisode() {
        host.start()
        guest.start()
        guestPlayer.actions.clear()
        guestPlayer.switchesContentOnOpen = false

        hostPlayer.content = episodeTwo
        host.onLocalPlaybackChanged()
        now = 5_000L
        host.onHeartbeat()
        now = 10_000L
        host.onHeartbeat()

        assertEquals(1, guestPlayer.actions.count { it.startsWith("open:") })
    }

    @Test
    fun guestCannotControlTheRoom() {
        host.start()
        guest.start()
        guestPlayer.actions.clear()

        now = 5_000L
        guestPlayer.positionMs = 65_000L
        guestPlayer.isPlaying = false
        guest.onLocalPlaybackChanged()

        assertEquals(listOf("play"), guestPlayer.actions)
        assertTrue(hostPlayer.actions.isEmpty())
        assertTrue(hub.sentMessages.none { it.senderId == "guest" && it is WatchPartyMessage.State })
    }

    @Test
    fun playerEventsRightAfterApplyingHostStateAreIgnored() {
        host.start()
        guest.start()
        guestPlayer.actions.clear()

        // Still inside the settle window after the join seek, so the guest's player is left alone.
        now = 1_000L
        guestPlayer.isPlaying = false
        guest.onLocalPlaybackChanged()
        assertTrue(guestPlayer.actions.isEmpty())

        // The next heartbeat repairs it.
        host.onHeartbeat()
        assertEquals(listOf("play"), guestPlayer.actions)
    }

    @Test
    fun outOfOrderAndDuplicateStatesAreIgnored() {
        val rawHost = hub.connect()
        guest.start()

        rawHost.send(hostState(seq = 5, isPlaying = true, positionMs = 10_000L))
        rawHost.send(hostState(seq = 4, isPlaying = false, positionMs = 0L))
        rawHost.send(hostState(seq = 5, isPlaying = false, positionMs = 0L))
        assertEquals(listOf("seek:10000", "play"), guestPlayer.actions)

        now = 5_000L
        guestPlayer.positionMs = 15_000L
        rawHost.send(hostState(seq = 6, isPlaying = false, positionMs = 15_000L))
        assertEquals(listOf("seek:10000", "play", "pause"), guestPlayer.actions)
    }

    @Test
    fun restartedHostWithNewEpochIsAccepted() {
        val rawHost = hub.connect()
        guest.start()
        rawHost.send(hostState(seq = 10, isPlaying = true, positionMs = 10_000L, epoch = "before-restart"))
        guestPlayer.actions.clear()

        now = 5_000L
        guestPlayer.positionMs = 15_000L
        rawHost.send(hostState(seq = 1, isPlaying = false, positionMs = 15_000L, epoch = "after-restart"))

        assertEquals(listOf("pause"), guestPlayer.actions)
    }

    @Test
    fun statesFromNonHostMembersAreIgnored() {
        val intruder = hub.connect()
        guest.start()

        intruder.send(hostState(seq = 1, isPlaying = true, positionMs = 90_000L).copy(senderId = "intruder"))

        assertTrue(guestPlayer.actions.isEmpty())
    }

    @Test
    fun reconnectingGuestGetsLatestState() {
        host.start()
        guest.start()
        guestPlayer.actions.clear()

        guestConnection.disconnect()
        now = 5_000L
        hostPlayer.isPlaying = false
        hostPlayer.positionMs = 120_000L
        host.onLocalPlaybackChanged()
        assertTrue(guestPlayer.actions.isEmpty())

        guestConnection.reconnect()
        guest.onReconnected()

        assertEquals(listOf("seek:120000", "pause"), guestPlayer.actions)
    }

    @Test
    fun hostWaitsUntilItsPlayerIsReadyBeforeBroadcasting() {
        hostPlayer.isReady = false
        host.start()
        guest.start()
        assertTrue(hub.sentMessages.none { it is WatchPartyMessage.State })

        hostPlayer.isReady = true
        host.onLocalPlaybackChanged()

        assertEquals(listOf("seek:60000", "play"), guestPlayer.actions)
    }

    @Test
    fun stoppedCoordinatorNoLongerReacts() {
        host.start()
        guest.start()
        guest.stop()
        guestPlayer.actions.clear()

        hostPlayer.isPlaying = false
        host.onLocalPlaybackChanged()

        assertTrue(guestPlayer.actions.isEmpty())
        assertFalse(guest.isController)
    }

    @Test
    fun slowSeekingFollowerDoesNotChaseAndAimsAheadNextTime() {
        host.start()
        guest.start()
        assertEquals(listOf("seek:60000", "play"), guestPlayer.actions)

        // The guest's source needs 3 seconds to resume after that seek.
        guestPlayer.isReady = false
        guest.onLocalPlaybackChanged()
        now = 3_000L
        guestPlayer.isReady = true
        guest.onLocalPlaybackChanged()

        // Three seconds behind, but seeking again right away would just repeat the wait.
        assertEquals(listOf("seek:60000", "play"), guestPlayer.actions)

        // After the cooldown, the next heartbeat corrects once, aiming 3 seconds ahead.
        now = 7_000L
        hostPlayer.positionMs = 67_000L
        guestPlayer.positionMs = 64_000L
        host.onHeartbeat()

        assertEquals(listOf("seek:60000", "play", "seek:70000"), guestPlayer.actions)
    }

    @Test
    fun deliberateControllerSeekIsAppliedEvenDuringCooldown() {
        host.start()
        guest.start()
        guestPlayer.actions.clear()

        now = 2_000L
        hostPlayer.positionMs = 200_000L
        host.onLocalPlaybackChanged()

        assertEquals(listOf("seek:200000"), guestPlayer.actions)
    }

    @Test
    fun followerPausesWhenControllerGoesSilentAndResumesWhenBack() {
        host.start()
        guest.start()
        guestPlayer.actions.clear()

        now = 10_000L
        guestPlayer.positionMs = 70_000L
        guest.onTick()
        assertTrue(guestEvents.events.isEmpty())

        now = 16_000L
        guestPlayer.positionMs = 76_000L
        guest.onTick()
        assertEquals(listOf("pause"), guestPlayer.actions)
        assertEquals(listOf("contact-lost"), guestEvents.events)

        now = 17_000L
        hostPlayer.positionMs = 77_000L
        host.onHeartbeat()

        assertEquals(listOf("pause", "play"), guestPlayer.actions)
        assertEquals(listOf("contact-lost", "contact-back"), guestEvents.events)
    }

    @Test
    fun keepAliveWhileControllerLoadsIsNotMistakenForSilence() {
        host.start()
        guest.start()
        hostPlayer.isReady = false

        listOf(5_000L, 10_000L, 15_000L).forEach {
            now = it
            host.onHeartbeat()
        }
        now = 16_000L
        guest.onTick()

        assertTrue(guestEvents.events.isEmpty())
    }

    @Test
    fun controllerSeekHoldsTheRoomUntilEveryoneIsReadyThenAllPlay() {
        host.start()
        guest.start()
        hostPlayer.actions.clear()
        guestPlayer.actions.clear()
        guestPlayer.loadsAfterSeek = true

        now = 2_000L
        hostPlayer.positionMs = 300_000L
        host.onLocalSeek()
        assertEquals(listOf("pause"), hostPlayer.actions)
        assertEquals(listOf("seek:300000", "pause"), guestPlayer.actions)

        now = 4_500L
        host.onTick()
        assertEquals(listOf("waiting:[guest]"), hostEvents.events)

        now = 5_000L
        guestPlayer.isReady = true
        guest.onLocalPlaybackChanged()

        assertEquals(listOf("pause", "play"), hostPlayer.actions)
        assertEquals(listOf("seek:300000", "pause", "play"), guestPlayer.actions)
    }

    @Test
    fun followerStallHoldsEveryoneUntilItRecovers() {
        host.start()
        guest.start()
        hostPlayer.actions.clear()
        guestPlayer.actions.clear()

        now = 2_000L
        guestPlayer.isReady = false
        guest.onLocalPlaybackChanged()
        now = 2_500L
        host.onTick()
        assertTrue(hostPlayer.actions.isEmpty())

        now = 3_100L
        host.onTick()
        assertEquals(listOf("pause"), hostPlayer.actions)

        now = 4_000L
        guestPlayer.isReady = true
        guest.onLocalPlaybackChanged()

        assertEquals(listOf("pause", "play"), hostPlayer.actions)
        assertEquals(listOf("pause", "play"), guestPlayer.actions)
    }

    @Test
    fun briefHiccupDoesNotHoldTheRoom() {
        host.start()
        guest.start()
        hostPlayer.actions.clear()

        now = 2_000L
        guestPlayer.isReady = false
        guest.onLocalPlaybackChanged()
        now = 2_500L
        guestPlayer.isReady = true
        guest.onLocalPlaybackChanged()
        now = 3_500L
        host.onTick()

        assertTrue(hostPlayer.actions.isEmpty())
    }

    @Test
    fun controllerStallHoldsFollowersUntilTheControllerRecovers() {
        host.start()
        guest.start()
        hostPlayer.actions.clear()
        guestPlayer.actions.clear()

        now = 2_000L
        hostPlayer.isReady = false
        host.onLocalPlaybackChanged()
        assertEquals(listOf("pause"), guestPlayer.actions)

        // The follower is ready and waiting; after a moment it says so.
        now = 4_500L
        guest.onTick()
        assertEquals(listOf("waiting-for-room"), guestEvents.events)

        now = 5_000L
        hostPlayer.isReady = true
        host.onLocalPlaybackChanged()

        assertEquals(listOf("pause", "play"), hostPlayer.actions)
        assertEquals(listOf("pause", "play"), guestPlayer.actions)
    }

    @Test
    fun roomContinuesWithoutAMemberWhoNeverGetsReadyAndDoesNotWaitAgain() {
        host.start()
        guest.start()
        hostPlayer.actions.clear()
        guestPlayer.loadsAfterSeek = true

        now = 2_000L
        hostPlayer.positionMs = 300_000L
        host.onLocalSeek()
        // Still in the player, so still waited for.
        now = 12_000L
        guest.onHeartbeat()
        now = 22_000L
        guest.onHeartbeat()

        now = 32_000L
        host.onTick()
        assertEquals(listOf("pause", "play"), hostPlayer.actions)
        assertEquals(listOf("continued-without:[guest]"), hostEvents.events)

        now = 34_000L
        host.onTick()
        assertEquals(listOf("pause", "play"), hostPlayer.actions)
    }

    @Test
    fun controllerPressingPlayDuringAHoldStopsWaiting() {
        host.start()
        guest.start()
        hostPlayer.actions.clear()
        guestPlayer.loadsAfterSeek = true

        now = 2_000L
        hostPlayer.positionMs = 300_000L
        host.onLocalSeek()
        now = 3_000L
        assertTrue(host.requestPlay())

        assertEquals(listOf("pause", "play"), hostPlayer.actions)
    }

    @Test
    fun memberWhoLeftThePlayerIsNotWaitedFor() {
        host.start()
        guest.start()
        guest.stop()
        hostPlayer.actions.clear()

        now = 20_000L
        hostPlayer.positionMs = 300_000L
        host.onLocalSeek()

        assertEquals(listOf("pause", "play"), hostPlayer.actions)
    }

    @Test
    fun smallGapIsClosedBySpeedNotBySeeking() {
        host.start()
        guest.start()
        guestPlayer.actions.clear()

        // Half a second ahead: play 5% slower instead of jumping.
        now = 2_000L
        guestPlayer.positionMs = 62_500L
        guest.onTick()
        assertTrue(guestPlayer.actions.isEmpty())
        assertEquals(0.95f, guestPlayer.playbackSpeed)

        // Back in step: normal speed again.
        now = 3_000L
        guestPlayer.positionMs = 63_020L
        guest.onTick()
        assertEquals(1f, guestPlayer.playbackSpeed)
        assertTrue(guestPlayer.actions.isEmpty())
    }

    @Test
    fun behindFollowerSpeedsUpAndTinyGapsAreLeftAlone() {
        host.start()
        guest.start()

        now = 2_000L
        guestPlayer.positionMs = 61_700L
        guest.onTick()
        assertEquals(1.05f, guestPlayer.playbackSpeed)

        // Once nudging, keep going until really close, not just under the start threshold.
        now = 3_000L
        guestPlayer.positionMs = 62_900L
        guest.onTick()
        assertEquals(1.05f, guestPlayer.playbackSpeed)

        now = 4_000L
        guestPlayer.positionMs = 63_970L
        guest.onTick()
        assertEquals(1f, guestPlayer.playbackSpeed)
    }

    @Test
    fun followerMatchesTheControllersPlaybackSpeed() {
        hostPlayer.playbackSpeed = 1.25f
        host.start()
        guest.start()

        assertEquals(1.25f, guestPlayer.playbackSpeed)

        // 4 seconds at 1.25x moves the controller 5 seconds on.
        now = 4_000L
        guestPlayer.positionMs = 65_000L
        guest.onTick()
        assertEquals(1.25f, guestPlayer.playbackSpeed)
        assertTrue(guestPlayer.actions.none { it.startsWith("seek:") && it != "seek:60000" })
    }

    @Test
    fun networkDelayIsMeasuredAndAimedFor() {
        host.start()
        guest.start()

        // A 600 ms round trip means the controller's updates are about 300 ms old on arrival.
        now = 1_000L
        hostConnection.send(WatchPartyMessage.Pong(senderId = "host", to = "guest", sentAtMs = 400L))

        now = 2_000L
        guestPlayer.positionMs = 62_000L
        guest.onTick()

        assertEquals(1.05f, guestPlayer.playbackSpeed)
    }

    @Test
    fun pingsAreAnsweredInsideTheControllersNextUpdate() {
        host.start()
        guest.start()
        hub.sentMessages.clear()

        now = 5_000L
        guest.onHeartbeat()
        now = 6_000L
        hostPlayer.positionMs = 66_000L
        host.onHeartbeat()

        assertTrue(hub.sentMessages.none { it is WatchPartyMessage.Pong })
        val state = hub.sentMessages.filterIsInstance<WatchPartyMessage.State>().single()
        assertEquals(listOf(PingEcho(to = "guest", sentAtMs = 5_000L, heldMs = 1_000L)), state.pongs)

        // Answered once only.
        now = 11_000L
        host.onHeartbeat()
        assertTrue(hub.sentMessages.filterIsInstance<WatchPartyMessage.State>().last().pongs.isEmpty())
    }

    @Test
    fun timeAnAnswerWaitedAtTheControllerIsNotCountedAsNetworkDelay() {
        host.start()
        guest.start()

        // 1000 ms since the ping, 400 of it waiting for the controller's next update: a 600 ms round trip.
        now = 1_000L
        hostConnection.send(
            WatchPartyMessage.KeepAlive(
                senderId = "host",
                pongs = listOf(PingEcho(to = "guest", sentAtMs = 0L, heldMs = 400L)),
            ),
        )

        now = 2_000L
        guestPlayer.positionMs = 62_000L
        guest.onTick()

        assertEquals(1.05f, guestPlayer.playbackSpeed)
    }

    private fun guestPingsBetween(fromMs: Long, toMs: Long): Int {
        var pings = 0
        var at = fromMs
        while (at <= toMs) {
            now = at
            hostPlayer.positionMs = 60_000L + at
            guestPlayer.positionMs = 60_000L + at
            host.onHeartbeat()
            hub.sentMessages.clear()
            guest.onHeartbeat()
            pings += hub.sentMessages.count { it is WatchPartyMessage.Ping }
            at += 5_000L
        }
        return pings
    }

    @Test
    fun followersPingOftenWhileSettlingInThenRarely() {
        host.start()
        guest.start()

        assertEquals(11, guestPingsBetween(5_000L, 55_000L))
        assertEquals(2, guestPingsBetween(60_000L, 120_000L))
    }

    @Test
    fun driftingMakesFollowersPingOftenAgain() {
        host.start()
        guest.start()
        guestPingsBetween(5_000L, 120_000L)

        guestPlayer.positionMs += 500L
        guest.onTick()
        assertEquals(0.95f, guestPlayer.playbackSpeed)

        assertEquals(3, guestPingsBetween(125_000L, 135_000L))
    }

    @Test
    fun aFollowerClosingThePlayerIsNotWaitedFor() {
        host.start()
        guest.start()
        hostPlayer.actions.clear()
        guestPlayer.loadsAfterSeek = true

        now = 2_000L
        hostPlayer.positionMs = 300_000L
        host.onLocalSeek()
        assertEquals(listOf("pause"), hostPlayer.actions)

        now = 3_000L
        guest.stop()

        assertEquals(listOf("pause", "play"), hostPlayer.actions)
    }

    @Test
    fun theSourceIsSentAfterItChangesAndThenLeftOut() {
        val torrentio = WatchPartySource(addonName = "Torrentio", name = "1080p")
        hostPlayer.source = torrentio
        host.start()
        guest.start()
        hub.sentMessages.clear()

        (1..5).forEach {
            now = it * 5_000L
            host.onHeartbeat()
        }
        val sources = hub.sentMessages.filterIsInstance<WatchPartyMessage.State>().map { it.playback.source }
        assertEquals(listOf(torrentio, torrentio, null, null, null), sources)

        hub.sentMessages.clear()
        val other = torrentio.copy(name = "4K")
        hostPlayer.source = other
        (6..9).forEach {
            now = it * 5_000L
            host.onHeartbeat()
        }
        val changed = hub.sentMessages.filterIsInstance<WatchPartyMessage.State>().map { it.playback.source }
        assertEquals(listOf(other, other, other, null), changed)
    }

    @Test
    fun followersRememberTheSourceWhenUpdatesLeaveItOut() {
        val torrentio = WatchPartySource(addonName = "Torrentio", name = "1080p")
        hostPlayer.source = torrentio
        host.start()
        guest.start()
        (1..5).forEach {
            now = it * 5_000L
            hostPlayer.positionMs = 60_000L + now
            host.onHeartbeat()
        }

        // The follower wandered to another episode; it returns with the controller's source.
        guestPlayer.switchesContentOnOpen = false
        guestPlayer.content = episodeTwo
        guest.onTick()

        assertEquals(torrentio, guestPlayer.openedSources.last())
    }

    @Test
    fun joiningAsksForTheStateOnce() {
        host.start()
        hub.sentMessages.clear()

        guest.start()
        // The player then reports the connection as up, which counts as reconnecting.
        guest.onReconnected()

        assertEquals(1, hub.sentMessages.count { it is WatchPartyMessage.StateRequest })
    }

    @Test
    fun aSeekThatStartedLoadingHoldsTheRoomOnlyOnce() {
        host.start()
        guest.start()
        hub.sentMessages.clear()

        // The seek makes the controller's video load first, which holds the room; then the seek is reported.
        now = 2_000L
        hostPlayer.positionMs = 300_000L
        hostPlayer.isReady = false
        host.onLocalPlaybackChanged()
        now = 2_700L
        host.onLocalSeek()

        val holds = hub.sentMessages.filterIsInstance<WatchPartyMessage.State>().mapNotNull { it.holdId }.toSet()
        assertEquals(1, holds.size)
    }

    @Test
    fun leavingTheRoomResetsANudgedSpeed() {
        host.start()
        guest.start()
        now = 2_000L
        guestPlayer.positionMs = 62_500L
        guest.onTick()

        guest.stop()

        assertEquals(1f, guestPlayer.playbackSpeed)
    }

    @Test
    fun takingControlResetsANudgedSpeed() {
        host.start()
        guest.start()
        now = 2_000L
        guestPlayer.positionMs = 62_500L
        guest.onTick()
        assertEquals(0.95f, guestPlayer.playbackSpeed)

        controller = "guest"
        guest.onControllerChanged()

        assertEquals(1f, guestPlayer.playbackSpeed)
    }

    @Test
    fun followersCannotStartOrStopTheRoom() {
        host.start()
        guest.start()

        assertFalse(guest.requestPlay())
        assertFalse(guest.requestPause())
    }

    @Test
    fun guestGivenControlDrivesTheHost() {
        host.start()
        guest.start()
        hostPlayer.actions.clear()
        guestPlayer.actions.clear()

        controller = "guest"
        host.onControllerChanged()
        guest.onControllerChanged()

        now = 5_000L
        guestPlayer.positionMs = 300_000L
        guestPlayer.isPlaying = false
        guest.onLocalPlaybackChanged()

        assertEquals(listOf("seek:300000", "pause"), hostPlayer.actions)
        assertTrue(guestPlayer.actions.isEmpty())
    }

    @Test
    fun formerControllerIsIgnoredAfterControlMoves() {
        host.start()
        guest.start()
        controller = "guest"
        host.onControllerChanged()
        guest.onControllerChanged()
        hostPlayer.actions.clear()
        guestPlayer.actions.clear()

        // A late message from the old controller must not move anyone.
        hostConnection.send(hostState(seq = 99, isPlaying = false, positionMs = 1_000L, epoch = "host-epoch"))

        assertTrue(guestPlayer.actions.isEmpty())
    }
}
