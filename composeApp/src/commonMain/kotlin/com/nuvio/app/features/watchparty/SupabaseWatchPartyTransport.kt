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
import kotlinx.serialization.json.jsonObject

private const val BROADCAST_EVENT = "watch_party"

private val wireJson = Json { ignoreUnknownKeys = true }

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
                channel.status.first { it == RealtimeChannel.Status.SUBSCRIBED }
                try {
                    channel.broadcast(BROADCAST_EVENT, payload)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Dropped; the host's next heartbeat carries the same state.
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
