package com.nuvio.app.features.watchparty

import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private const val BROADCAST_EVENT = "watch_party"

private val wireJson = Json { ignoreUnknownKeys = true }

/** Lenient on purpose: the server may add its own fields to member entries. */
internal fun decodeWatchPartyMember(state: JsonObject): WatchPartyMember? =
    try {
        wireJson.decodeFromJsonElement(WatchPartyMember.serializer(), state)
    } catch (e: Exception) {
        null
    }

/**
 * Trims messages queued while disconnected. Pings and keep-alives are stale by then and only the
 * newest playback state counts; everything else (chat, control, ready) is kept in order.
 */
internal fun collapseBacklog(backlog: List<JsonObject>): List<JsonObject> {
    val lastStateIndex = backlog.indexOfLast { it.messageType() == "state" }
    return backlog.filterIndexed { index, payload ->
        when (payload.messageType()) {
            "ping", "pong", "keep_alive" -> false
            "state" -> index == lastStateIndex
            else -> true
        }
    }
}

private fun JsonObject.messageType(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull

/**
 * Sends room messages as Supabase Realtime broadcasts. Broadcasts are relayed live and never
 * stored, so the project needs no database tables.
 */
internal class SupabaseWatchPartyTransport(
    private val channel: RealtimeChannel,
    private val scope: CoroutineScope,
) : WatchPartyTransport {
    private val outgoing = Channel<JsonObject>(Channel.UNLIMITED)
    private var listener: ((WatchPartyMessage) -> Unit)? = null

    /** [scope] must run on the main thread, because listeners drive the player. */
    fun start() {
        scope.launch {
            channel.broadcastFlow<JsonObject>(BROADCAST_EVENT).collect { payload ->
                val message = try {
                    wireJson.decodeFromJsonElement(WatchPartyMessage.serializer(), payload)
                } catch (e: Exception) {
                    // Ignore messages from incompatible or misbehaving clients.
                    return@collect
                }
                listener?.invoke(message)
            }
        }
        scope.launch {
            // One sender keeps messages in order and holds them until the room is joined.
            for (payload in outgoing) {
                val batch = if (channel.status.value == RealtimeChannel.Status.SUBSCRIBED) {
                    listOf(payload)
                } else {
                    channel.status.first { it == RealtimeChannel.Status.SUBSCRIBED }
                    // Whatever piled up while disconnected: send only what still matters.
                    val backlog = mutableListOf(payload)
                    while (true) backlog += outgoing.tryReceive().getOrNull() ?: break
                    collapseBacklog(backlog)
                }
                for (message in batch) {
                    try {
                        channel.broadcast(BROADCAST_EVENT, message)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Dropped; the host's next heartbeat carries the same state.
                    }
                }
            }
        }
    }

    override fun send(message: WatchPartyMessage) {
        outgoing.trySend(wireJson.encodeToJsonElement(WatchPartyMessage.serializer(), message).jsonObject)
    }

    override fun setListener(listener: ((WatchPartyMessage) -> Unit)?) {
        this.listener = listener
    }

    fun close() {
        outgoing.close()
    }
}
