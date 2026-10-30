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

/** Correlated ACK + actual connected state are both required to claim Remote ownership. */
internal object RemoteCallOwnershipPolicy {
    fun claims(
        pendingAnswer: Boolean,
        pendingCommand: String?,
        accepted: Boolean,
        state: String,
        dialStartedAtMs: Long,
        nowMs: Long,
        dialWindowMs: Long,
    ): Boolean {
        if (!accepted || state != LinkService.CALL_OFFHOOK) return false
        if (pendingAnswer && pendingCommand == "ANSWER") return true
        return pendingCommand in setOf("DIAL", "DIAL_SIM") &&
            dialStartedAtMs > 0L && nowMs >= dialStartedAtMs &&
            nowMs - dialStartedAtMs < dialWindowMs
    }
}
