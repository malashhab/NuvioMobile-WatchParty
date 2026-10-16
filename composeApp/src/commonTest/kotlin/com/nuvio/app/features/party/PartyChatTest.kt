package com.nuvio.app.features.party

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartyChatTest {
    private var now = 0L
    private var nextId = 0
    private val names = mutableMapOf("sam" to "Sam", "alex" to "Alex")
    private val chats = mutableListOf<PartyChat>()

    private val samChat = chat("sam")
    private val alexChat = chat("alex")

    /** Every message a chat sends reaches all the others, like the party channel. */
    private fun chat(memberId: String): PartyChat {
        lateinit var self: PartyChat
        self = PartyChat(
            localMemberId = memberId,
            nameOf = { names[it] },
            send = { payload -> chats.filter { it !== self }.forEach { it.onMessage(payload) } },
            nowMs = { now },
            newId = { "msg-${nextId++}" },
        )
        chats += self
        return self
    }

    private fun PartyChat.texts() = state.value.messages.map { "${it.name}: ${it.text}" }

    @Test
    fun messageReachesOthersAndShowsAsOwnForTheSender() {
        assertEquals(PartyChatSendResult.Sent, samChat.send("hi!"))

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
        assertEquals(PartyChatSendResult.Empty, samChat.send("   \n\t "))
        assertTrue(alexChat.state.value.messages.isEmpty())
    }

    @Test
    fun textIsCleanedAndCapped() {
        samChat.send("  hello\n\nthere\u0007  ")
        samChat.send("x".repeat(600))

        val texts = alexChat.state.value.messages.map { it.text }
        assertEquals("hello there", texts[0])
        assertEquals(PartyChat.MAX_TEXT_LENGTH, texts[1].length)
    }

    @Test
    fun arabicAndEmojiPassThrough() {
        samChat.send("مرحبا 👋")
        assertEquals(listOf("Sam: مرحبا 👋"), alexChat.texts())
    }

    @Test
    fun sendingTooFastIsRefusedUntilTheWindowPasses() {
        repeat(5) { assertEquals(PartyChatSendResult.Sent, samChat.send("msg $it")) }
        assertEquals(PartyChatSendResult.TooFast, samChat.send("one too many"))

        now = 5_000L
        assertEquals(PartyChatSendResult.Sent, samChat.send("ok again"))
    }

    @Test
    fun floodingSenderIsCutOffByReceivers() {
        repeat(8) { i -> alexChat.onMessage(raw(id = "flood-$i", memberId = "sam", text = "spam")) }
        assertEquals(5, alexChat.state.value.messages.size)
    }

    @Test
    fun repeatedMessagesShowOnce() {
        val message = raw(id = "same", memberId = "sam", text = "hello")
        alexChat.onMessage(message)
        alexChat.onMessage(message)
        assertEquals(1, alexChat.state.value.messages.size)
    }

    @Test
    fun memberNamesBeatTheNameInTheMessage() {
        alexChat.onMessage(raw(id = "a", memberId = "sam", text = "hi", name = "Alex"))
        assertEquals(listOf("Sam: hi"), alexChat.texts())

        // Someone not (yet) in the member list keeps the name they sent.
        alexChat.onMessage(raw(id = "b", memberId = "new", text = "hey", name = "Robin"))
        assertEquals("Robin: hey", alexChat.texts().last())
    }

    @Test
    fun ownEchoAndBrokenMessagesAreIgnored() {
        samChat.onMessage(raw(id = "echo", memberId = "sam", text = "mine"))
        samChat.onMessage(buildJsonObject { put("text", "no sender") })
        assertTrue(samChat.state.value.messages.isEmpty())
    }

    private fun raw(id: String, memberId: String, text: String, name: String? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("member_id", memberId)
        put("text", text)
        if (name != null) put("name", name)
    }
}
