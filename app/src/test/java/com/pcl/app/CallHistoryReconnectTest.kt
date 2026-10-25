package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallHistoryReconnectTest {
    @Test fun linkLossDoesNotCreateFakeMissedCallOrCarryOldIdentityForward() {
        try {
            assertNull(CallHistory.onSimState(LinkService.CALL_RINGING, "11111111", "First caller"))
            CallHistory.onLinkLost()
            assertNull(CallHistory.onSimState(LinkService.CALL_RINGING, "22222222", "Second caller"))
            val record = CallHistory.onSimState(LinkService.CALL_IDLE, "", "")
            assertEquals(CallHistory.Type.MISSED, record?.type)
            assertEquals("22222222", record?.number)
            assertEquals("Second caller", record?.name)
        } finally {
            CallHistory.onLinkLost()
        }
    }

    @Test fun returningToIdleAfterLinkLossDoesNotInventHistory() {
        try {
            CallHistory.onSimState(LinkService.CALL_RINGING, "33333333", "")
            CallHistory.onLinkLost()
            assertNull(CallHistory.onSimState(LinkService.CALL_IDLE, "", ""))
        } finally {
            CallHistory.onLinkLost()
        }
    }
}
