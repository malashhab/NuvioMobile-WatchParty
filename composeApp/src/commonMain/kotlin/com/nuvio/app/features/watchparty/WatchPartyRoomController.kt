package com.nuvio.app.features.watchparty

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
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
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_party_removed
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val CONNECT_TIMEOUT_MS = 15_000L
private const val SERVER_TEST_TIMEOUT_MS = 10_000L
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
    /** The server this room lives on; invites carry it so guests need no server of their own. */
    val server: WatchPartyServer? = null,
) {
    val isController: Boolean
        get() = localMemberId != null && localMemberId == controllerId

    fun nameOf(memberId: String?): String? = members.firstOrNull { it.memberId == memberId }?.name

    /** Members in the player right now; the rest are in the room but elsewhere in the app. */
    val watchingCount: Int
        get() = members.count { it.watching != false }
}

enum class WatchPartyJoinResult {
    Joined,

    /** A bare room code, but this device has no server of its own; the host's invite is needed. */
    NeedsServer,
}

/**
 * Creates, joins and leaves rooms. Rooms live on a Supabase project of the host's choosing (see
 * [WatchPartyServerSettings]), separate from the app's account server, so they never touch account data.
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

    /** Whether this device can create rooms; without a server it can still join through invites. */
    val canHost: Boolean
        get() = WatchPartyServerSettings.hostingServer() != null

    // One client per server; switching servers replaces it.
    private var client: SupabaseClient? = null
    private var clientServer: WatchPartyServer? = null

    private fun clientFor(server: WatchPartyServer): SupabaseClient {
        client?.takeIf { clientServer == server }?.let { return it }
        client?.let { previous -> scope.launch { closeQuietly(previous) } }
        return createRealtimeClient(server).also {
            client = it
            clientServer = server
        }
    }

    private fun createRealtimeClient(server: WatchPartyServer): SupabaseClient =
        createSupabaseClient(supabaseUrl = server.url, supabaseKey = server.key) {
            install(Realtime)
        }

    private suspend fun closeQuietly(client: SupabaseClient) {
        try {
            client.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Closing is best effort.
        }
    }

    private var roomJob: Job? = null
    private var channel: RealtimeChannel? = null
    private var transport: SupabaseWatchPartyTransport? = null
    private var control: WatchPartyControl? = null

    private val _chat = MutableStateFlow<WatchPartyChat?>(null)

    /** The current room's chat, or null outside a room. */
    val chat: StateFlow<WatchPartyChat?> = _chat.asStateFlow()

    /** Returns false when this device has no server to host on. */
    fun createRoom(): Boolean {
        val server = WatchPartyServerSettings.hostingServer() ?: return false
        open(code = WatchPartyRoomCode.generate(), isOwner = true, server = server)
        return true
    }

    /** Joins on the invite's server, or our own when the invite is just a code. */
    fun join(invite: WatchPartyInvite): WatchPartyJoinResult {
        val server = invite.server ?: WatchPartyServerSettings.hostingServer() ?: return WatchPartyJoinResult.NeedsServer
        open(code = invite.code, isOwner = false, server = server)
        return WatchPartyJoinResult.Joined
    }

    /** True when the server accepts a Realtime connection with this key, checked with a throwaway channel. */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun testServer(server: WatchPartyServer): Boolean {
        val testClient = createRealtimeClient(server)
        return try {
            withTimeoutOrNull(SERVER_TEST_TIMEOUT_MS) {
                testClient.channel("watch-party-test-${Uuid.random()}").subscribe(blockUntilSubscribed = true)
                true
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            withContext(NonCancellable) { closeQuietly(testClient) }
        }
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

    // Whether this device is in the player, shared with the room through presence.
    private var localWatching = false
    private var announcePresence: (() -> Unit)? = null

    /** Call when the player opens (true) or closes (false). */
    fun setWatching(watching: Boolean) {
        if (localWatching == watching) return
        localWatching = watching
        announcePresence?.invoke()
    }

    /** Owner only. The member leaves, but can join again with the invite. */
    fun removeMember(memberId: String) {
        control?.removeMember(memberId)
    }

    fun leave() {
        WatchPartySessionRepository.leave()
        announcePresence = null
        roomJob?.cancel()
        roomJob = null
        transport?.close()
        transport = null
        control = null
        _chat.value = null
        val closingClient = client
        channel?.let { closing ->
            scope.launch {
                try {
                    closingClient?.realtime?.removeChannel(closing)
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
    private fun open(code: String, isOwner: Boolean, server: WatchPartyServer) {
        leave()

        val room = WatchPartyRoom(code = code, ownerMemberId = ownerMemberId(code))
        // Phase 2 shortcut: the owner's id is derived from the code, so anyone with the code could
        // pose as owner. Verified owners come with authenticated private channels later.
        val localMemberId = if (isOwner) room.ownerMemberId else "guest-${Uuid.random()}"
        val localMember = WatchPartyMember(memberId = localMemberId, name = localMemberName())

        val roomChannel = clientFor(server).channel("watch-party-$code") {
            presence { key = localMemberId }
        }

        suspend fun trackLocalMember() {
            try {
                roomChannel.track(localMember.copy(watching = localWatching))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The member list misses us until the next reconnect; sync still works.
            }
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
            server = server,
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
                if (next == WatchPartyConnection.Connected) trackLocalMember()
            }
        }
        // Also announced again whenever we open or close the player.
        announcePresence = {
            if (connection.value == WatchPartyConnection.Connected) roomScope.launch { trackLocalMember() }
        }
        roomScope.launch {
            roomControl.removed.first { it }
            val notice = getString(Res.string.watch_party_removed)
            NuvioToastController.show(notice, durationMillis = 5_000L)
            // Ends this room, this task included.
            leave()
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
