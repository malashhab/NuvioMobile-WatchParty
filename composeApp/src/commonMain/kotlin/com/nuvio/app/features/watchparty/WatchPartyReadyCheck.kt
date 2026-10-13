package com.nuvio.app.features.watchparty

/**
 * Controller side of "everyone starts together". A hold pauses the room at one position; each
 * follower reports ready once loaded there, and when everyone (the controller included) is ready the
 * room plays. A member who takes too long is left behind so one phone can't stall the room.
 */
internal class WatchPartyReadyCheck(
    private val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
    private val announceAfterMs: Long = DEFAULT_ANNOUNCE_AFTER_MS,
) {
    sealed interface Decision {
        data object None : Decision
        /** Still waiting long enough that people should know for whom. */
        data class Announce(val memberIds: List<String>) : Decision
        data object Release : Decision
        data class ReleaseWithout(val memberIds: List<String>) : Decision
    }

    private class Hold(
        val id: String,
        val startedAtMs: Long,
        val waitingFor: MutableSet<String>,
        var announced: Boolean = false,
    )

    private var hold: Hold? = null
    private var nextNumber = 1L

    val holdId: String?
        get() = hold?.id

    val isHolding: Boolean
        get() = hold != null

    /** Starts a hold, or keeps the current one. Returns its id. */
    fun start(idPrefix: String, nowMs: Long, members: Set<String>): String {
        hold?.let { return it.id }
        val id = "$idPrefix:${nextNumber++}"
        hold = Hold(id = id, startedAtMs = nowMs, waitingFor = members.toMutableSet())
        return id
    }

    fun cancel() {
        hold = null
    }

    fun onReady(memberId: String, holdId: String) {
        val current = hold ?: return
        if (current.id == holdId) current.waitingFor.remove(memberId)
    }

    /** [activeMembers] are those still in the player; anyone who left is no longer waited for. */
    fun evaluate(nowMs: Long, controllerReady: Boolean, activeMembers: Set<String>): Decision {
        val current = hold ?: return Decision.None
        current.waitingFor.retainAll(activeMembers)
        if (controllerReady && current.waitingFor.isEmpty()) {
            hold = null
            return Decision.Release
        }
        if (nowMs - current.startedAtMs >= maxWaitMs) {
            hold = null
            return Decision.ReleaseWithout(current.waitingFor.toList())
        }
        if (!current.announced && current.waitingFor.isNotEmpty() && nowMs - current.startedAtMs >= announceAfterMs) {
            current.announced = true
            return Decision.Announce(current.waitingFor.toList())
        }
        return Decision.None
    }

    companion object {
        const val DEFAULT_MAX_WAIT_MS = 30_000L
        const val DEFAULT_ANNOUNCE_AFTER_MS = 2_000L
    }
}
