package com.pcl.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimCallRecoveryGuardTest {
    @Test fun normalRingingAnswerEndTracksWithoutDisconnect() {
        val guard = SimCallRecoveryGuard()
        assertTrue(guard.shouldTrack(LinkService.CALL_RINGING))
        assertTrue(guard.shouldTrack(LinkService.CALL_OFFHOOK))
        assertTrue(guard.shouldTrack(LinkService.CALL_IDLE))
    }

    @Test fun reconnectDuringRingingNeverCreatesFalseMissedCall() {
        val guard = SimCallRecoveryGuard()
        guard.onDisconnect()
        assertFalse(guard.shouldTrack(LinkService.CALL_RINGING))
        assertFalse(guard.shouldTrack(LinkService.CALL_RINGING))
        assertFalse(guard.shouldTrack(LinkService.CALL_IDLE))
        assertFalse(guard.suppressing)
        assertTrue(guard.shouldTrack(LinkService.CALL_RINGING))
        assertTrue(guard.shouldTrack(LinkService.CALL_IDLE))
    }

    @Test fun reconnectDuringOffhookNeverCreatesFalseOutgoingOrLongDuration() {
        val guard = SimCallRecoveryGuard()
        guard.onDisconnect()
        assertFalse(guard.shouldTrack(LinkService.CALL_OFFHOOK))
        assertFalse(guard.shouldTrack(LinkService.CALL_OFFHOOK))
        assertFalse(guard.shouldTrack(LinkService.CALL_IDLE))
        assertTrue(guard.shouldTrack(LinkService.CALL_OFFHOOK))
    }

    @Test fun initialIdleSnapshotUnlocksFreshCallsImmediately() {
        val guard = SimCallRecoveryGuard()
        guard.onDisconnect()
        assertFalse(guard.shouldTrack(LinkService.CALL_IDLE))
        assertTrue(guard.shouldTrack(LinkService.CALL_RINGING))
    }

    @Test fun severalRapidDisconnectsNeverReplayStaleSessions() {
        val guard = SimCallRecoveryGuard()
        guard.onDisconnect()
        assertFalse(guard.shouldTrack(LinkService.CALL_OFFHOOK))
        guard.onDisconnect()
        assertFalse(guard.shouldTrack(LinkService.CALL_OFFHOOK))
        assertFalse(guard.shouldTrack(LinkService.CALL_IDLE))
        assertTrue(guard.shouldTrack(LinkService.CALL_RINGING))
    }
}
