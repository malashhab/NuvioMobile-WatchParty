package com.nuvio.app.features.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartyLeaveVideoTest {
    private val movie = PartyMedia(type = "movie", metaId = "tt1", metaType = "movie")
    private val episode = PartyMedia(type = "series", metaId = "tt2", metaType = "series", season = 1, episode = 2)

    private fun party(
        presence: List<PartyPresence>,
        media: PartyMedia = movie,
        connected: Boolean = true,
    ) = ActiveParty(
        id = "p",
        code = "CODE",
        memberId = "me",
        hostMemberId = "me",
        members = emptyList(),
        presence = presence.associateBy { it.memberId },
        media = media,
        maxMembers = 3,
        connected = connected,
    )

    private fun watching(id: String, status: PartyMemberStatus = PartyMemberStatus.Ready, media: PartyMedia = movie) =
        PartyPresence(memberId = id, status = status, mediaKey = media.key)

    @Test
    fun countsOthersWithTheVideoOpen() {
        val party = party(
            listOf(
                watching("me"),
                watching("sam"),
                watching("alex", PartyMemberStatus.Behind),
                watching("kim", PartyMemberStatus.Buffering),
                watching("lee", PartyMemberStatus.Browsing),
                watching("max", media = episode),
            ),
        )
        assertEquals(setOf("sam", "alex", "kim"), PartySync.watchingMemberIds(party))
    }

    @Test
    fun closingThePlayerOrLeavingThePartyCounts() {
        val before = setOf("sam", "alex")
        val after = party(listOf(watching("sam", PartyMemberStatus.Browsing)))
        assertEquals(setOf("sam", "alex"), PartySync.membersWhoLeftVideo(before, movie.key, after))
    }

    @Test
    fun movingToTheNextVideoTogetherIsNotLeaving() {
        val after = party(listOf(watching("sam", media = movie)), media = episode)
        assertTrue(PartySync.membersWhoLeftVideo(setOf("sam"), movie.key, after).isEmpty())
    }

    @Test
    fun reconnectingIsNotLeaving() {
        val after = party(emptyList(), connected = false)
        assertTrue(PartySync.membersWhoLeftVideo(setOf("sam"), movie.key, after).isEmpty())
    }

    @Test
    fun joiningIsNotLeaving() {
        val after = party(listOf(watching("sam"), watching("new", PartyMemberStatus.Loading)))
        assertTrue(PartySync.membersWhoLeftVideo(setOf("sam"), movie.key, after).isEmpty())
    }
}
