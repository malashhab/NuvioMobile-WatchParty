package com.nuvio.app.features.watchparty

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveWatchParty(
    val room: WatchPartyRoom,
    val localMemberId: String,
    val transport: WatchPartyTransport,
)

/** The party this device is in, if any. The player syncs only while this is set. */
object WatchPartySessionRepository {
    private val _active = MutableStateFlow<ActiveWatchParty?>(null)
    val active: StateFlow<ActiveWatchParty?> = _active.asStateFlow()

    fun join(party: ActiveWatchParty) {
        _active.value = party
    }

    fun leave() {
        _active.value = null
    }
}
