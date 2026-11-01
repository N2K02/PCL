package com.pcl.app

/** Coalesce only periodic traffic notifications; connection/call changes remain immediate. */
internal class TrafficNotificationGate(private val minIntervalMs: Long = 10_000L) {
    private var lastAt = -1L
    private var lastDown = -1L
    private var lastUp = -1L

    fun reset() {
        lastAt = -1L
        lastDown = -1L
        lastUp = -1L
    }

    fun shouldPublish(elapsedMs: Long, down: Long, up: Long): Boolean {
        if (lastAt >= 0 && lastDown == down && lastUp == up) return false
        if (lastAt >= 0 && elapsedMs - lastAt < minIntervalMs) return false
        lastAt = elapsedMs
        lastDown = down
        lastUp = up
        return true
    }
}
