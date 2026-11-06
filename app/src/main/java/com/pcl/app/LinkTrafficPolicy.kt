package com.pcl.app

/** Keep connected throughput accuracy; do not wake often while awaiting a peer. */
internal object LinkTrafficPolicy {
    fun nextIntervalMs(connected: Boolean): Long =
        if (connected) 5_000L else 30_000L
}
