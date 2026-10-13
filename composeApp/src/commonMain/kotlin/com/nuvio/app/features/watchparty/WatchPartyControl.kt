package com.nuvio.app.features.watchparty

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ControlRequestState {
    None,
    Waiting,
    Denied,
}

/**
 * Who controls playback. The owner starts in control, can hand it to a member who asks, and can
 * take it back at any time. Only the owner's announcements change the controller.
 */
@OptIn(ExperimentalUuidApi::class)
class WatchPartyControl(
    private val room: WatchPartyRoom,
    private val localMemberId: String,
    private val send: (WatchPartyMessage) -> Unit,
    private val epoch: String = Uuid.random().toString(),
) {
    val isOwner: Boolean
        get() = localMemberId == room.ownerMemberId

    private val _controllerId = MutableStateFlow(room.ownerMemberId)
    val controllerId: StateFlow<String> = _controllerId.asStateFlow()

    /** Owner side: members waiting for an answer. */
    private val _pendingRequests = MutableStateFlow<List<WatchPartyMember>>(emptyList())
    val pendingRequests: StateFlow<List<WatchPartyMember>> = _pendingRequests.asStateFlow()

    /** Requester side: the answer to our own request. */
    private val _requestState = MutableStateFlow(ControlRequestState.None)
    val requestState: StateFlow<ControlRequestState> = _requestState.asStateFlow()

    // Owner side.
    private var nextSeq = 1L

    // Everyone else.
    private var ownerEpoch: String? = null
    private var lastOwnerSeq = 0L

    val isController: Boolean
        get() = _controllerId.value == localMemberId

    fun requestControl(name: String) {
        if (isOwner || isController) return
        _requestState.value = ControlRequestState.Waiting
        send(WatchPartyMessage.ControlRequest(senderId = localMemberId, name = name))
    }

    fun returnControl() {
        if (isOwner || !isController) return
        send(WatchPartyMessage.ControlReturn(senderId = localMemberId))
    }

    fun grant(memberId: String) {
        if (!isOwner) return
        _pendingRequests.update { requests -> requests.filterNot { it.memberId == memberId } }
        announceController(memberId)
    }

    fun deny(memberId: String) {
        if (!isOwner) return
        _pendingRequests.update { requests -> requests.filterNot { it.memberId == memberId } }
        send(WatchPartyMessage.ControlDenied(senderId = localMemberId, memberId = memberId))
    }

    fun takeBack() {
        if (!isOwner || isController) return
        announceController(localMemberId)
    }

    /** Owner side: forget requests from members who left, and recover control if the controller left. */
    fun onMembersChanged(members: List<WatchPartyMember>) {
        if (!isOwner) return
        val presentIds = members.map { it.memberId }.toSet()
        _pendingRequests.update { requests -> requests.filter { it.memberId in presentIds } }
        if (!isController && _controllerId.value !in presentIds) {
            announceController(localMemberId)
        }
    }

    /** Returns true when the message was about control and needs no further handling. */
    fun onMessage(message: WatchPartyMessage): Boolean {
        if (message.senderId == localMemberId) return false
        return when (message) {
            is WatchPartyMessage.ControlRequest -> {
                if (isOwner && message.senderId != _controllerId.value) {
                    val member = WatchPartyMember(memberId = message.senderId, name = message.name)
                    _pendingRequests.update { requests ->
                        requests.filterNot { it.memberId == member.memberId } + member
                    }
                }
                true
            }
            is WatchPartyMessage.ControlReturn -> {
                if (isOwner && message.senderId == _controllerId.value) announceController(localMemberId)
                true
            }
            is WatchPartyMessage.ControlDenied -> {
                if (message.senderId == room.ownerMemberId && message.memberId == localMemberId) {
                    _requestState.value = ControlRequestState.Denied
                }
                true
            }
            is WatchPartyMessage.ControlChanged -> {
                onControlChanged(message)
                true
            }
            is WatchPartyMessage.StateRequest -> {
                // Newcomers assume the owner controls; correct them when someone else does.
                if (isOwner && !isController) announceController(_controllerId.value)
                false
            }
            is WatchPartyMessage.State -> false
        }
    }

    private fun onControlChanged(message: WatchPartyMessage.ControlChanged) {
        if (isOwner || message.senderId != room.ownerMemberId) return
        if (message.epoch == ownerEpoch && message.seq <= lastOwnerSeq) return
        ownerEpoch = message.epoch
        lastOwnerSeq = message.seq
        _controllerId.value = message.controllerId
        if (message.controllerId == localMemberId) _requestState.value = ControlRequestState.None
    }

    private fun announceController(memberId: String) {
        _controllerId.value = memberId
        send(
            WatchPartyMessage.ControlChanged(
                senderId = localMemberId,
                epoch = epoch,
                seq = nextSeq++,
                controllerId = memberId,
            ),
        )
    }
}
