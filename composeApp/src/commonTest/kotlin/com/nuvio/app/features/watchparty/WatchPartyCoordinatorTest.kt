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

    private val host = coordinator("host", hostConnection, hostPlayer, epoch = "host-epoch")
    private val guest = coordinator("guest", guestConnection, guestPlayer)

    private fun coordinator(
        memberId: String,
        transport: WatchPartyTransport,
        player: WatchPartyPlayerPort,
        epoch: String = "$memberId-epoch",
    ) = WatchPartyCoordinator(
        localMemberId = memberId,
        transport = transport,
        player = player,
        nowMs = { now },
        controllerId = { controller },
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
