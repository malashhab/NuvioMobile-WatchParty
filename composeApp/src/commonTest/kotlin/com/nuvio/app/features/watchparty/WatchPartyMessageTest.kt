package com.nuvio.app.features.watchparty

import kotlinx.serialization.json.Json
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
}
