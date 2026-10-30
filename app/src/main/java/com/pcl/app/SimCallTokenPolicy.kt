package com.pcl.app

/** Correlates Remote call controls with the exact Gateway SIM call instance. */
internal object SimCallTokenPolicy {
    fun matches(gatewayStartedAtMs: Long, remoteToken: String?): Boolean =
        gatewayStartedAtMs > 0L &&
            remoteToken?.toLongOrNull()?.let { it > 0L && it == gatewayStartedAtMs } == true
}
