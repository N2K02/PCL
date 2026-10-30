package com.pcl.app

/** Reject stale Bluetooth call controls instead of acting on an unrelated SIM call. */
internal object GatewayCallCommandPolicy {
    fun allows(command: String, actualState: String): Boolean = when (command) {
        "ANSWER", "REJECT" -> actualState == LinkService.CALL_RINGING
        "HANGUP" -> actualState == LinkService.CALL_OFFHOOK
        "DIAL", "DIAL_SIM" -> actualState == LinkService.CALL_IDLE
        else -> false
    }
}
