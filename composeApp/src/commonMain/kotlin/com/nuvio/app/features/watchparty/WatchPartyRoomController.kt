package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.track
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val CONNECT_TIMEOUT_MS = 15_000L
private const val DEFAULT_MEMBER_NAME = "Guest"

enum class WatchPartyConnection {
    Connecting,
    Connected,
    Disconnected,
}

data class WatchPartyStatus(
    val code: String? = null,
    val localMemberId: String? = null,
    val ownerMemberId: String? = null,
    val isOwner: Boolean = false,
    val controllerId: String? = null,
    val members: List<WatchPartyMember> = emptyList(),
    val pendingRequests: List<WatchPartyMember> = emptyList(),
    val requestState: ControlRequestState = ControlRequestState.None,
    val connection: WatchPartyConnection = WatchPartyConnection.Disconnected,
    val connectFailed: Boolean = false,
) {
    val isController: Boolean
        get() = localMemberId != null && localMemberId == controllerId

    fun nameOf(memberId: String?): String? = members.firstOrNull { it.memberId == memberId }?.name
}

/**
 * Creates, joins and leaves rooms. Uses its own Supabase project (WatchPartyConfig), separate
 * from the app's account server, so it never touches account data.
 */
object WatchPartyRoomController {
    private val log = Logger.withTag("WatchParty")

    // A network surprise must never crash the app; log it and keep the room going.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, error ->
            log.w(error) { "Watch Party task failed" }
        },
    )

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
    private var control: WatchPartyControl? = null

    private val _chat = MutableStateFlow<WatchPartyChat?>(null)

    /** The current room's chat, or null outside a room. */
    val chat: StateFlow<WatchPartyChat?> = _chat.asStateFlow()

    fun createRoom() {
        open(code = WatchPartyRoomCode.generate(), isOwner = true)
    }

    /** Returns false when [input] is not a valid room code. */
    fun joinRoom(input: String): Boolean {
        val code = WatchPartyRoomCode.normalize(input) ?: return false
        open(code = code, isOwner = false)
        return true
    }

    fun requestControl() {
        control?.requestControl(localMemberName())
    }

    fun returnControl() {
        control?.returnControl()
    }

    fun grantControl(memberId: String) {
        control?.grant(memberId)
    }

    fun denyControl(memberId: String) {
        control?.deny(memberId)
    }

    fun takeBackControl() {
        control?.takeBack()
    }

    fun leave() {
        WatchPartySessionRepository.leave()
        roomJob?.cancel()
        roomJob = null
        transport?.close()
        transport = null
        control = null
        _chat.value = null
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
    private fun open(code: String, isOwner: Boolean) {
        leave()
        if (!isConfigured) return

        val room = WatchPartyRoom(code = code, ownerMemberId = ownerMemberId(code))
        // Phase 2 shortcut: the owner's id is derived from the code, so anyone with the code could
        // pose as owner. Verified owners come with authenticated private channels later.
        val localMemberId = if (isOwner) room.ownerMemberId else "guest-${Uuid.random()}"
        val localMember = WatchPartyMember(memberId = localMemberId, name = localMemberName())

        val roomChannel = client.channel("watch-party-$code") {
            presence { key = localMemberId }
        }
        val connection = MutableStateFlow(WatchPartyConnection.Connecting)
        val job = SupervisorJob()
        val roomScope = CoroutineScope(scope.coroutineContext + job)
        val networkTransport = SupabaseWatchPartyTransport(roomChannel, roomScope)
        val roomControl = WatchPartyControl(room = room, localMemberId = localMemberId, send = networkTransport::send)
        val playbackTransport = PlaybackTransport(networkTransport)
        // Control messages are handled here, app-wide; playback messages go on to the player.
        val clock = TimeSource.Monotonic.markNow()
        val roomChat = WatchPartyChat(
            localMemberId = localMemberId,
            localName = ::localMemberName,
            send = networkTransport::send,
            nowMs = { clock.elapsedNow().inWholeMilliseconds },
        )
        networkTransport.setListener { message ->
            when {
                message is WatchPartyMessage.Chat -> roomChat.onMessage(message)
                roomControl.onMessage(message) -> Unit
                else -> playbackTransport.deliver(message)
            }
        }

        channel = roomChannel
        transport = networkTransport
        control = roomControl
        _chat.value = roomChat
        roomJob = job
        _status.value = WatchPartyStatus(
            code = code,
            localMemberId = localMemberId,
            ownerMemberId = room.ownerMemberId,
            isOwner = isOwner,
            controllerId = room.ownerMemberId,
            connection = WatchPartyConnection.Connecting,
        )

        networkTransport.start()
        // Listening before subscribing switches presence on for this channel.
        roomScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val membersByKey = mutableMapOf<String, WatchPartyMember>()
            roomChannel.presenceChangeFlow().collect { change ->
                change.leaves.keys.forEach(membersByKey::remove)
                change.joins.forEach { (key, presence) ->
                    decodeWatchPartyMember(presence.state)?.let { membersByKey[key] = it }
                }
                val members = membersByKey.values.toList()
                val ownerFirst = members.sortedWith(
                    compareBy<WatchPartyMember>({ it.memberId != room.ownerMemberId }, { it.name }),
                )
                _status.update { it.copy(members = ownerFirst) }
                roomControl.onMembersChanged(members)
            }
        }
        roomScope.launch {
            combine(
                roomControl.controllerId,
                roomControl.pendingRequests,
                roomControl.requestState,
            ) { controllerId, pending, requestState -> Triple(controllerId, pending, requestState) }
                .collect { (controllerId, pending, requestState) ->
                    _status.update {
                        it.copy(controllerId = controllerId, pendingRequests = pending, requestState = requestState)
                    }
                }
        }
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
                // Presence is not restored after a reconnect, so announce ourselves on every join.
                if (next == WatchPartyConnection.Connected) {
                    try {
                        roomChannel.track(localMember)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The member list misses us until the next reconnect; sync still works.
                    }
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
                room = room,
                localMemberId = localMemberId,
                transport = playbackTransport,
                connection = connection.asStateFlow(),
                control = roomControl,
            ),
        )
    }

    private fun ownerMemberId(code: String): String = "owner-$code"

    private fun localMemberName(): String =
        ProfileRepository.state.value.activeProfile?.name?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MEMBER_NAME
}

/** The player's view of the room: sends go to the network, and only playback messages come back. */
private class PlaybackTransport(
    private val network: WatchPartyTransport,
) : WatchPartyTransport {
    private var listener: ((WatchPartyMessage) -> Unit)? = null

    override fun send(message: WatchPartyMessage) = network.send(message)

    override fun setListener(listener: ((WatchPartyMessage) -> Unit)?) {
        this.listener = listener
    }

    fun deliver(message: WatchPartyMessage) {
        listener?.invoke(message)
    }
}
