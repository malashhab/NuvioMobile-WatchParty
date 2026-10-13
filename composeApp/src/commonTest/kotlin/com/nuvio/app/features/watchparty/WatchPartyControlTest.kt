package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchPartyControlTest {
    private val room = WatchPartyRoom(code = "ABCD2345", ownerMemberId = "owner")
    private val hub = InMemoryWatchPartyHub()
    private val ownerConnection = hub.connect()
    private val samConnection = hub.connect()
    private val alexConnection = hub.connect()

    private val owner = control("owner", ownerConnection)
    private val sam = control("sam", samConnection)
    private val alex = control("alex", alexConnection)

    private val samMember = WatchPartyMember(memberId = "sam", name = "Sam")

    private fun control(memberId: String, connection: InMemoryWatchPartyHub.Connection): WatchPartyControl {
        val control = WatchPartyControl(room = room, localMemberId = memberId, send = connection::send, epoch = "$memberId-epoch")
        connection.setListener { control.onMessage(it) }
        return control
    }

    @Test
    fun ownerStartsInControl() {
        assertEquals("owner", owner.controllerId.value)
        assertEquals("owner", sam.controllerId.value)
        assertTrue(owner.isController)
    }

    @Test
    fun requestReachesOwnerAndGrantMovesControlForEveryone() {
        sam.requestControl("Sam")
        assertEquals(ControlRequestState.Waiting, sam.requestState.value)
        assertEquals(listOf(samMember), owner.pendingRequests.value)

        owner.grant("sam")

        assertEquals("sam", owner.controllerId.value)
        assertEquals("sam", sam.controllerId.value)
        assertEquals("sam", alex.controllerId.value)
        assertTrue(sam.isController)
        assertEquals(ControlRequestState.None, sam.requestState.value)
        assertTrue(owner.pendingRequests.value.isEmpty())
    }

    @Test
    fun denyTellsOnlyTheRequester() {
        sam.requestControl("Sam")
        alex.requestControl("Alex")

        owner.deny("sam")

        assertEquals(ControlRequestState.Denied, sam.requestState.value)
        assertEquals(ControlRequestState.Waiting, alex.requestState.value)
        assertEquals("owner", sam.controllerId.value)
    }

    @Test
    fun repeatedRequestsAppearOnce() {
        sam.requestControl("Sam")
        sam.requestControl("Sam")

        assertEquals(listOf(samMember), owner.pendingRequests.value)
    }

    @Test
    fun controllerCanGiveControlBack() {
        sam.requestControl("Sam")
        owner.grant("sam")

        sam.returnControl()

        assertEquals("owner", owner.controllerId.value)
        assertEquals("owner", sam.controllerId.value)
    }

    @Test
    fun ownerCanTakeControlBackAnyTime() {
        sam.requestControl("Sam")
        owner.grant("sam")

        owner.takeBack()

        assertEquals("owner", alex.controllerId.value)
        assertTrue(owner.isController)
    }

    @Test
    fun onlyTheOwnerCanMoveControl() {
        samConnection.send(WatchPartyMessage.ControlChanged(senderId = "sam", epoch = "x", seq = 1, controllerId = "sam"))

        assertEquals("owner", alex.controllerId.value)
    }

    @Test
    fun staleAnnouncementsAreIgnored() {
        ownerConnection.send(WatchPartyMessage.ControlChanged(senderId = "owner", epoch = "e", seq = 2, controllerId = "sam"))
        ownerConnection.send(WatchPartyMessage.ControlChanged(senderId = "owner", epoch = "e", seq = 1, controllerId = "alex"))

        assertEquals("sam", alex.controllerId.value)
    }

    @Test
    fun controlReturnsToOwnerWhenControllerLeaves() {
        sam.requestControl("Sam")
        owner.grant("sam")

        owner.onMembersChanged(listOf(WatchPartyMember("owner", "Owner"), WatchPartyMember("alex", "Alex")))

        assertEquals("owner", alex.controllerId.value)
    }

    @Test
    fun requestsFromMembersWhoLeftAreDropped() {
        sam.requestControl("Sam")

        owner.onMembersChanged(listOf(WatchPartyMember("owner", "Owner")))

        assertTrue(owner.pendingRequests.value.isEmpty())
    }

    @Test
    fun newcomerLearnsWhoControls() {
        sam.requestControl("Sam")
        owner.grant("sam")
        val lateConnection = hub.connect()
        val late = control("late", lateConnection)

        lateConnection.send(WatchPartyMessage.StateRequest(senderId = "late"))

        assertEquals("sam", late.controllerId.value)
    }
}
