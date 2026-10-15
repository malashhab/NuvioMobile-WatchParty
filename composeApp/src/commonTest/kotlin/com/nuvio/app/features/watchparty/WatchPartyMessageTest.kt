package com.nuvio.app.features.watchparty

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyMessageTest {
    @Test
    fun stateSurvivesTheTripOverTheWire() {
        val message: WatchPartyMessage = WatchPartyMessage.State(
            senderId = "host",
            epoch = "epoch",
            seq = 3,
            playback = WatchPartyPlayback(content = episodeOne, isPlaying = true, positionMs = 42_000L),
        )

        val json = Json.encodeToString(WatchPartyMessage.serializer(), message)

        assertTrue("\"type\":\"state\"" in json, json)
        assertEquals(message, Json.decodeFromString(WatchPartyMessage.serializer(), json))
    }

    @Test
    fun stateRequestSurvivesTheTripOverTheWire() {
        val message: WatchPartyMessage = WatchPartyMessage.StateRequest(senderId = "guest")

        val json = Json.encodeToString(WatchPartyMessage.serializer(), message)

        assertEquals(message, Json.decodeFromString(WatchPartyMessage.serializer(), json))
    }

    @Test
    fun wireFormatNeverContainsStreamDetails() {
        val json = Json.encodeToString(
            WatchPartyMessage.serializer(),
            WatchPartyMessage.State(
                senderId = "host",
                epoch = "epoch",
                seq = 1,
                playback = WatchPartyPlayback(
                    content = episodeOne,
                    isPlaying = false,
                    positionMs = 0L,
                    source = WatchPartySource(
                        addonId = "torrentio",
                        addonName = "Torrentio",
                        name = "Torrentio 1080p",
                        description = "Show.S01E01.1080p.WEB.mkv",
                        infoHash = "abc123",
                        fileIdx = 1,
                        filename = "Show.S01E01.1080p.WEB.mkv",
                        videoSize = 1_000L,
                        bingeGroup = "torrentio|1080p",
                    ),
                ),
            ),
        )

        listOf("url", "header", "token").forEach { word ->
            assertFalse(word in json.lowercase(), "unexpected \"$word\" in $json")
        }
    }

    @Test
    fun aBacklogKeepsOnlyTheLatestStateAndWhatStillMatters() {
        fun wire(message: WatchPartyMessage) =
            Json.encodeToJsonElement(WatchPartyMessage.serializer(), message).jsonObject
        fun state(seq: Long) = WatchPartyMessage.State(
            senderId = "host",
            epoch = "epoch",
            seq = seq,
            playback = WatchPartyPlayback(content = episodeOne, isPlaying = true, positionMs = seq * 1_000L),
        )
        val chat = WatchPartyMessage.Chat(senderId = "host", id = "1", name = "Host", text = "hi")

        val backlog = listOf(
            wire(state(1)),
            wire(WatchPartyMessage.Ping(senderId = "guest", sentAtMs = 1L)),
            wire(chat),
            wire(WatchPartyMessage.KeepAlive(senderId = "host")),
            wire(state(2)),
            wire(WatchPartyMessage.Pong(senderId = "host", to = "guest", sentAtMs = 1L)),
        )

        assertEquals(listOf(wire(chat), wire(state(2))), collapseBacklog(backlog))
    }

    @Test
    fun onlyMembersInTheVideoCountAsWatching() {
        val status = WatchPartyStatus(
            members = listOf(
                WatchPartyMember(memberId = "host", name = "Host", watching = true),
                WatchPartyMember(memberId = "away", name = "Away", watching = false),
                // Older app versions don't say; they only join rooms from the player.
                WatchPartyMember(memberId = "old", name = "Old"),
            ),
        )

        assertEquals(2, status.watchingCount)
    }

    @Test
    fun membersFromOlderVersionsStillDecode() {
        val member = decodeWatchPartyMember(
            Json.parseToJsonElement("""{"memberId":"m1","name":"Sam"}""").jsonObject,
        )

        assertEquals(WatchPartyMember(memberId = "m1", name = "Sam", watching = null), member)
    }
}
