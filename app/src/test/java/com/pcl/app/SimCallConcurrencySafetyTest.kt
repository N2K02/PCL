package com.pcl.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimCallConcurrencySafetyTest {
    @Test fun callWaitingFromConnectedToRingingIsAmbiguous() {
        assertTrue(SimCallConcurrencyPolicy.isWaiting(LinkService.CALL_OFFHOOK, LinkService.CALL_RINGING))
        assertFalse(SimCallConcurrencyPolicy.isWaiting(LinkService.CALL_IDLE, LinkService.CALL_RINGING))
        assertFalse(SimCallConcurrencyPolicy.isWaiting(LinkService.CALL_RINGING, LinkService.CALL_OFFHOOK))
        assertTrue(SimCallConcurrencyPolicy.shouldBlock(true, false))
        assertTrue(SimCallConcurrencyPolicy.shouldBlock(false, true))
        assertFalse(SimCallConcurrencyPolicy.shouldBlock(false, false))
    }

    @Test fun watchdogReconcilesOffhookEndOrNewWaitingButNotRepeatedWaiting() {
        assertTrue(SimCallConcurrencyPolicy.shouldRecheck(LinkService.CALL_RINGING, LinkService.CALL_OFFHOOK, false))
        assertTrue(SimCallConcurrencyPolicy.shouldRecheck(LinkService.CALL_RINGING, LinkService.CALL_IDLE, false))
        assertTrue(SimCallConcurrencyPolicy.shouldRecheck(LinkService.CALL_OFFHOOK, LinkService.CALL_IDLE, false))
        assertTrue(SimCallConcurrencyPolicy.shouldRecheck(LinkService.CALL_OFFHOOK, LinkService.CALL_RINGING, false))
        assertFalse(SimCallConcurrencyPolicy.shouldRecheck(LinkService.CALL_OFFHOOK, LinkService.CALL_RINGING, true))
        assertFalse(SimCallConcurrencyPolicy.shouldRecheck(LinkService.CALL_OFFHOOK, null, false))
        assertFalse(SimCallConcurrencyPolicy.shouldRecheck(LinkService.CALL_IDLE, LinkService.CALL_RINGING, false))
    }

    @Test fun tokenMustMatchExactLiveCallAndNeverBeAbsent() {
        assertTrue(SimCallTokenPolicy.matches(102L, "102"))
        assertFalse(SimCallTokenPolicy.matches(102L, "103"))
        assertFalse(SimCallTokenPolicy.matches(0L, "0"))
        assertFalse(SimCallTokenPolicy.matches(102L, null))
        assertFalse(SimCallTokenPolicy.matches(102L, "invalid"))
        assertFalse(SimCallTokenPolicy.matches(102L, "-1"))
    }

    private fun source(name: String): String {
        val path = "app/src/main/java/com/pcl/app/$name.kt"
        return listOf(File(path), File("../$path")).first { it.isFile }.readText()
    }

    @Test fun gatewayNeverRelaysCallWaitingAsSecondIncomingCall() {
        val service = source("LinkService")
        val gateway = service.substringAfter("private fun applyGatewayPhoneState(")
            .substringBefore("private fun recoverRecentSimNumber(")
        assertTrue(gateway.contains("SimCallConcurrencyPolicy.isWaiting(previousState, newState)"))
        assertTrue(gateway.contains("sendCallState()"))
        assertTrue(gateway.contains("return"))
        assertTrue(service.contains("SimCallDetector.hasMultipleBusySubscriptions(this)"))
        assertTrue(service.contains("if (callControlsRestricted) stopSimRinging()"))
        assertTrue(service.contains("if (callControlsRestricted) {\n            nm.cancel(ID_INCOMING)"))
        val protectedCmd = service.substringAfter("private fun requestGateway(")
            .substringBefore("private fun clearPendingCall()")
        assertTrue(protectedCmd.contains("if (callControlsRestricted)"))
    }

    @Test fun staleLockScreenAndNotificationsCannotTargetNextCall() {
        val service = source("LinkService")
        val lock = source("IncomingCallActivity")
        assertTrue(service.contains("activeCallToken == token"))
        assertTrue(service.contains("SimCallTokenPolicy.matches(simCallStartedMs, raw)"))
        assertTrue(service.contains(".putExtra(EXTRA_CALL_TOKEN, activeCallToken ?: 0L)"))
        assertTrue(service.contains("pcl-sim-call:"))
        assertTrue(lock.contains("service.currentIncomingToken() != presentedToken"))
        assertTrue(lock.contains("answerForCall(presentedToken)"))
        assertTrue(lock.contains("rejectForCall(presentedToken)"))
        assertTrue(lock.contains("if (!submitted)"))
    }

    @Test fun restartAndDisconnectDropStaleCallIdentity() {
        val service = source("LinkService")
        val shutdown = service.substringAfter("private fun shutdown()")
            .substringBefore("private fun send(vararg parts: String)")
        assertTrue(shutdown.contains("simCallStartedMs = 0L"))
        assertTrue(shutdown.contains("activeCallToken = null"))
        assertTrue(shutdown.contains("callNumber = \"\""))
        val session = service.substringAfter("private fun runSession(")
            .substringBefore("private fun closeSocket()")
        assertTrue(session.contains("activeCallToken = null"))
        assertTrue(session.contains("callDirection = \"\""))
    }

    @Test fun ambiguousSimTimelineDoesNotCreateFalseMissedHistory() {
        val service = source("LinkService")
        val remote = service.substringAfter("\"CALL\" -> {").substringBefore("\"CALL_LOGGED\" -> {")
        assertTrue(remote.contains("if (callControlsRestricted && !wasRestricted) CallHistory.onLinkLost()"))
        assertTrue(remote.contains("val savedCall = if (callControlsRestricted) null else"))
        assertTrue(remote.contains("callControlError = \"FAILED: Call changed before Gateway confirmation\""))
        assertTrue(source("CallHistory").contains("if (!recovery.shouldTrack(state)) return null"))
    }
}
