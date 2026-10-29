package com.pcl.app

/** Bluetooth disconnect invalidates an unfinished call's timeline. On
 * reconnect the Gateway sends a snapshot: if that call is already active,
 * ignore every event belonging to it until a confirmed IDLE state. */
internal class SimCallRecoveryGuard {
    private var awaitingIdle = false

    fun onDisconnect() { awaitingIdle = true }

    fun shouldTrack(state: String): Boolean {
        if (!awaitingIdle) return true
        if (state == LinkService.CALL_IDLE) awaitingIdle = false
        return false
    }

    val suppressing: Boolean get() = awaitingIdle
}
