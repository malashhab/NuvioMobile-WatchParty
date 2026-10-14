package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchPartyInviteTest {
    private val server = WatchPartyServer(url = "https://abcd1234.supabase.co", key = "sb_publishable_Ab_C-9")

    @Test
    fun inviteLinkRoundTripsWithItsServer() {
        val link = buildWatchPartyInviteUrl("K7QMP4XD", server)

        assertEquals(WatchPartyInvite("K7QMP4XD", server), parseWatchPartyInvite(link))
    }

    @Test
    fun wholePastedMessageIsUnderstood() {
        val message = "Join my Nuvio WP Watch Party! Copy this whole message... Room K7QM-P4XD: " +
            buildWatchPartyInviteUrl("K7QMP4XD", server)

        assertEquals(WatchPartyInvite("K7QMP4XD", server), parseWatchPartyInvite(message))
    }

    @Test
    fun bareCodeHasNoServer() {
        assertEquals(WatchPartyInvite("K7QMP4XD"), parseWatchPartyInvite(" k7qm-p4xd "))
    }

    @Test
    fun linkWithoutServerStillWorksForSameServerRooms() {
        assertEquals(WatchPartyInvite("K7QMP4XD"), parseWatchPartyInvite("nuvio://watchparty/K7QMP4XD"))
    }

    @Test
    fun inviteNamingASecretKeyIsRejected() {
        val unsafe = buildWatchPartyInviteUrl("K7QMP4XD", server.copy(key = "sb_secret_oops"))

        assertNull(parseWatchPartyInvite(unsafe))
    }

    @Test
    fun inviteWithAnInsecureServerIsRejected() {
        val unsafe = buildWatchPartyInviteUrl("K7QMP4XD", server.copy(url = "http://abcd1234.supabase.co"))

        assertNull(parseWatchPartyInvite(unsafe))
    }

    @Test
    fun garbageIsNotAnInvite() {
        assertNull(parseWatchPartyInvite("hello there"))
        assertNull(parseWatchPartyInvite("nuvio://watchparty/NOTVALID0"))
    }
}
