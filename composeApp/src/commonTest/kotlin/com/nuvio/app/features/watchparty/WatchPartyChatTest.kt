package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartyChatTest {
    private val hub = InMemoryWatchPartyHub()
    private var now = 0L
    private var nextId = 0

    private val samChat = chat("sam", "Sam")
    private val alexChat = chat("alex", "Alex")

    private fun chat(memberId: String, name: String): WatchPartyChat {
        val connection = hub.connect()
        val chat = WatchPartyChat(
            localMemberId = memberId,
            localName = { name },
            send = connection::send,
            nowMs = { now },
            newId = { "msg-${nextId++}" },
        )
        connection.setListener { message -> if (message is WatchPartyMessage.Chat) chat.onMessage(message) }
        return chat
    }

    private fun WatchPartyChat.texts() = state.value.messages.map { "${it.name}: ${it.text}" }

    @Test
    fun messageReachesOthersAndShowsAsOwnForTheSender() {
        assertEquals(ChatSendResult.Sent, samChat.send("hi!"))

        assertEquals(listOf("Sam: hi!"), alexChat.texts())
        assertTrue(samChat.state.value.messages.single().isOwn)
        assertTrue(!alexChat.state.value.messages.single().isOwn)
    }

    @Test
    fun unreadCountsOnlyWhileClosedAndClearsWhenOpened() {
        samChat.send("one")
        samChat.send("two")
        assertEquals(2, alexChat.state.value.unreadCount)
        assertEquals(0, samChat.state.value.unreadCount)

        alexChat.setOpen(true)
        assertEquals(0, alexChat.state.value.unreadCount)

        samChat.send("three")
        assertEquals(0, alexChat.state.value.unreadCount)
    }

    @Test
    fun blankMessagesAreNotSent() {
        assertEquals(ChatSendResult.Empty, samChat.send("   \n\t "))

        assertTrue(alexChat.state.value.messages.isEmpty())
    }

    @Test
    fun textIsCleanedAndCapped() {
        samChat.send("  hello\n\nthere\u0007  ")
        samChat.send("x".repeat(600))

        val texts = alexChat.state.value.messages.map { it.text }
        assertEquals("hello there", texts[0])
        assertEquals(WatchPartyChat.MAX_TEXT_LENGTH, texts[1].length)
    }

    @Test
    fun sendingTooFastIsRefusedUntilTheWindowPasses() {
        repeat(5) { assertEquals(ChatSendResult.Sent, samChat.send("msg $it")) }
        assertEquals(ChatSendResult.TooFast, samChat.send("one too many"))

        now = 5_000L
        assertEquals(ChatSendResult.Sent, samChat.send("ok again"))
    }

    @Test
    fun floodingSenderIsDroppedOnTheReceivingSide() {
        val flooder = hub.connect()
        repeat(8) {
            flooder.send(WatchPartyMessage.Chat(senderId = "flooder", id = "f$it", name = "F", text = "spam $it"))
        }

        assertEquals(5, alexChat.state.value.messages.size)
    }

    @Test
    fun duplicatesAreShownOnce() {
        val other = hub.connect()
        val message = WatchPartyMessage.Chat(senderId = "x", id = "same", name = "X", text = "hello")
        other.send(message)
        other.send(message)

        assertEquals(1, alexChat.state.value.messages.size)
    }

    @Test
    fun historyKeepsOnlyTheLatestMessages() {
        val other = hub.connect()
        repeat(WatchPartyChat.MAX_HISTORY + 20) {
            now += 2_000L
            other.send(WatchPartyMessage.Chat(senderId = "x", id = "m$it", name = "X", text = "line $it"))
        }

        val messages = alexChat.state.value.messages
        assertEquals(WatchPartyChat.MAX_HISTORY, messages.size)
        assertEquals("line ${WatchPartyChat.MAX_HISTORY + 19}", messages.last().text)
    }

    @Test
    fun blankNamesFallBackToGuest() {
        hub.connect().send(WatchPartyMessage.Chat(senderId = "x", id = "n", name = "  ", text = "hey"))

        assertEquals("Guest", alexChat.state.value.messages.single().name)
    }

    @Test
    fun cleanTextRejectsControlOnlyInput() {
        assertNull(WatchPartyChat.cleanText("\u0000\u0007"))
    }
}
