package com.pcl.app

/** Bounds local alerting without changing the Gateway's call state or history.
 * A peer without call-state refresh cannot prove liveness, so its alert fails closed
 * after three seconds instead of potentially ringing on a dead Bluetooth session.
 */
internal class RemoteRingDeadline(startedAt: Long, val refreshSupported: Boolean) {
    private val maxUntil = startedAt + MAX_RING_MS
    private var confirmedUntil = startedAt + STATE_TIMEOUT_MS

    fun confirm(now: Long) {
        if (!expired(now) && refreshSupported) {
            confirmedUntil = minOf(now + STATE_TIMEOUT_MS, maxUntil)
        }
    }

    /** Reject delayed, duplicated, or unrelated heartbeat acknowledgements. */
    fun confirmQuery(responseId: Long?, pendingId: Long, now: Long): Boolean {
        if (!refreshSupported || responseId == null || responseId <= 0L ||
            responseId != pendingId || expired(now)) return false
        confirm(now)
        return true
    }

    fun expired(now: Long): Boolean = now >= confirmedUntil

    fun nextCheckIn(now: Long): Long =
        minOf(confirmedUntil - now, QUERY_INTERVAL_MS)
            .coerceAtLeast(0L)

    companion object {
        const val MAX_RING_MS = 90_000L
        private const val STATE_TIMEOUT_MS = 3_000L
        private const val QUERY_INTERVAL_MS = 1_000L
    }
}
