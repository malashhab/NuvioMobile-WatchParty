package com.nuvio.app.features.watchparty

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val CONNECT_TIMEOUT_MS = 15_000L

enum class WatchPartyConnection {
    Connecting,
    Connected,
    Disconnected,
}

data class WatchPartyStatus(
    val code: String? = null,
    val isHost: Boolean = false,
    val connection: WatchPartyConnection = WatchPartyConnection.Disconnected,
    val connectFailed: Boolean = false,
)

/**
 * Creates, joins and leaves rooms. Uses its own Supabase project (WatchPartyConfig), separate
 * from the app's account server, so it never touches account data.
 */
object WatchPartyRoomController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _status = MutableStateFlow(WatchPartyStatus())
    val status: StateFlow<WatchPartyStatus> = _status.asStateFlow()

    val isConfigured: Boolean
        get() = WatchPartyConfig.SUPABASE_URL.isNotBlank() && WatchPartyConfig.SUPABASE_KEY.isNotBlank()

    private val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = WatchPartyConfig.SUPABASE_URL,
            supabaseKey = WatchPartyConfig.SUPABASE_KEY,
        ) {
            install(Realtime)
        }
    }

    private var roomJob: Job? = null
    private var channel: RealtimeChannel? = null
    private var transport: SupabaseWatchPartyTransport? = null

    fun createRoom() {
        open(code = WatchPartyRoomCode.generate(), isHost = true)
    }

    /** Returns false when [input] is not a valid room code. */
    fun joinRoom(input: String): Boolean {
        val code = WatchPartyRoomCode.normalize(input) ?: return false
        open(code = code, isHost = false)
        return true
    }

    fun leave() {
        WatchPartySessionRepository.leave()
        roomJob?.cancel()
        roomJob = null
        transport?.close()
        transport = null
        channel?.let { closing ->
            scope.launch {
                try {
                    client.realtime.removeChannel(closing)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The channel is gone either way.
                }
            }
        }
        channel = null
        _status.value = WatchPartyStatus()
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun open(code: String, isHost: Boolean) {
        leave()
        if (!isConfigured) return

        val roomChannel = client.channel("watch-party-$code")
        val connection = MutableStateFlow(WatchPartyConnection.Connecting)
        val job = SupervisorJob()
        val roomScope = CoroutineScope(scope.coroutineContext + job)
        val roomTransport = SupabaseWatchPartyTransport(roomChannel, roomScope)

        channel = roomChannel
        transport = roomTransport
        roomJob = job
        _status.value = WatchPartyStatus(code = code, isHost = isHost, connection = WatchPartyConnection.Connecting)

        roomTransport.start()
        roomScope.launch {
            roomChannel.status.collect { channelStatus ->
                val next = when (channelStatus) {
                    RealtimeChannel.Status.SUBSCRIBED -> WatchPartyConnection.Connected
                    RealtimeChannel.Status.SUBSCRIBING -> WatchPartyConnection.Connecting
                    else -> WatchPartyConnection.Disconnected
                }
                connection.value = next
                _status.update {
                    it.copy(
                        connection = next,
                        connectFailed = it.connectFailed && next != WatchPartyConnection.Connected,
                    )
                }
            }
        }
        roomScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (connection.value != WatchPartyConnection.Connected) {
                _status.update { it.copy(connectFailed = true) }
            }
        }
        roomScope.launch {
            try {
                roomChannel.subscribe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.update { it.copy(connectFailed = true) }
            }
        }

        WatchPartySessionRepository.join(
            ActiveWatchParty(
                room = WatchPartyRoom(code = code, hostMemberId = hostMemberId(code)),
                // Phase 2 shortcut: the host's id is derived from the code, so anyone with the code could
                // pose as host. Verified hosts come with authenticated private channels later.
                localMemberId = if (isHost) hostMemberId(code) else "guest-${Uuid.random()}",
                transport = roomTransport,
                connection = connection.asStateFlow(),
            ),
        )
    }

    private fun hostMemberId(code: String): String = "host-$code"
}
