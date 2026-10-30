package com.pcl.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRingStateSyncTest {
    @Test fun answerOnGatewayRecoversMissingOffhookBroadcast() {
        assertTrue(GatewayRingStateSync.changedWhileRinging(
            LinkService.CALL_RINGING, LinkService.CALL_OFFHOOK))
        assertFalse(GatewayRingStateSync.changedWhileRinging(
            LinkService.CALL_RINGING, LinkService.CALL_RINGING))
    }

    @Test fun missedOrEndedCallSilencesRemote() {
        assertTrue(GatewayRingStateSync.changedWhileRinging(
            LinkService.CALL_RINGING, LinkService.CALL_IDLE))
        assertFalse(GatewayRingStateSync.changedWhileRinging(
            LinkService.CALL_IDLE, LinkService.CALL_OFFHOOK))
        assertFalse(GatewayRingStateSync.changedWhileRinging(
            LinkService.CALL_RINGING, null))
    }

    @Test fun staleBroadcastDoesNotRestartAnsweredCall() {
        assertTrue(GatewayRingStateSync.isStaleRingingAfterAnswer(
            LinkService.CALL_OFFHOOK, LinkService.CALL_OFFHOOK))
        assertFalse(GatewayRingStateSync.isStaleRingingAfterAnswer(
            LinkService.CALL_IDLE, LinkService.CALL_OFFHOOK))
        assertFalse(GatewayRingStateSync.isStaleRingingAfterAnswer(
            LinkService.CALL_OFFHOOK, LinkService.CALL_RINGING))
    }

    @Test fun serviceStopsRingBeforeWritingHistoryAndCancelsWatchdogOnQuit() {
        val path = "app/src/main/java/com/pcl/app/LinkService.kt"
        val service = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(service.contains("trafficHandler.postDelayed(gatewayPhoneStateRecheck,"))
        assertTrue(service.contains("trafficHandler.removeCallbacks(gatewayPhoneStateRecheck)"))
        assertTrue(service.contains("SimCallConcurrencyPolicy.shouldRecheck(callState, observed, callWaitingSeen)"))
        assertTrue(service.contains("GatewayRingStateSync.isStaleRingingAfterAnswer(callState, currentGatewayPhoneState())"))
        val remoteCall = service.substringAfter("\"CALL\" -> {").substringBefore("\"CALL_LOGGED\" -> {")
        assertTrue(remoteCall.indexOf("if (callState != CALL_RINGING || callControlsRestricted) stopSimRinging()") >= 0)
        assertTrue(remoteCall.indexOf("if (callState != CALL_RINGING || callControlsRestricted) stopSimRinging()") <
            remoteCall.indexOf("CallHistory.onSimState("))
    }
}
