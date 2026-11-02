package com.pcl.app

/** Pure reconciliation rules for a SIM call ringing on the Gateway. */
internal object GatewayRingStateSync {
    /** If the next broadcast was missed, OFFHOOK/IDLE must end the Remote ringtone. */
    fun changedWhileRinging(current: String, observed: String?): Boolean =
        current == LinkService.CALL_RINGING &&
            observed in setOf(LinkService.CALL_OFFHOOK, LinkService.CALL_IDLE)

    /** Do not reopen ringing for a delayed broadcast after an answered call. */
    fun isStaleRingingAfterAnswer(current: String, observed: String?): Boolean =
        current == LinkService.CALL_OFFHOOK && observed == LinkService.CALL_OFFHOOK
}
