package com.pcl.app

import java.io.File
import org.junit.Assert.assertEquals
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

    /**
     * Regression: a call answered on the Gateway kept the Remote ringing because a
     * mirrored dialer/in-call notification held a second ring source
     * (ringingMirrorKey) that OFFHOOK did not stop. The Remote must have exactly one
     * ring source, and OFFHOOK/IDLE must stop it unconditionally.
     */
    @Test fun answeredOnGatewayAlwaysStopsRemoteRinging() {
        fun read(name: String): String {
            val path = "app/src/main/java/com/pcl/app/$name"
            return listOf(File(path), File("../$path")).first { it.isFile }.readText()
        }
        val service = read("LinkService.kt")
        assertFalse(service.contains("ringingMirrorKey"))
        assertFalse(service.contains("fun showMirroredCall"))
        assertFalse(service.contains("\"CALL_NOTIF\" ->"))
        assertTrue(service.contains("private fun stopSimRinging() = stopRinging()"))
        assertEquals(1, Regex("RingtoneManager\\.getRingtone\\(").findAll(service).count())

        val update = service.substringAfter("private fun onRemoteCallUpdate(")
            .substringBefore("private fun showOngoingCall(")
        val offhook = update.substringAfter("CALL_OFFHOOK -> {").substringBefore("else -> {")
        assertTrue(offhook.trimStart().startsWith("stopSimRinging()"))
        assertTrue(update.substringAfter("else -> {").contains("stopSimRinging()"))
        assertTrue(update.substringAfter("else -> {").contains("nm.cancel(ID_INCOMING)"))

        val manifestPath = "app/src/main/AndroidManifest.xml"
        val manifest = listOf(File(manifestPath), File("../$manifestPath")).first { it.isFile }.readText()
        assertFalse(manifest.contains("BIND_NOTIFICATION_LISTENER_SERVICE"))

        // The lock-screen incoming UI closes once the call is no longer ringing here.
        val incoming = read("IncomingCallActivity.kt")
        assertTrue(incoming.contains("service.currentIncomingToken() != presentedToken"))
        assertTrue(service.contains("role == ROLE_REMOTE && callState == CALL_RINGING && !callControlsRestricted"))
    }


    @Test fun gatewayAnswerStateUsesDirectTelephonyCallbackAndUrgentPacketOrdering() {
        val path = "app/src/main/java/com/pcl/app/LinkService.kt"
        val src = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(src.contains("TelephonyCallback.CallStateListener"))
        assertTrue(src.contains("telephony.registerTelephonyCallback(mainExecutor, callback)"))
        assertTrue(src.contains("registerGatewayCallStateCallback()"))
        assertTrue(src.contains("unregisterGatewayCallStateCallback()"))
        assertTrue(src.contains("TelephonyManager.CALL_STATE_OFFHOOK -> CALL_OFFHOOK"))
        val transition = src.substringAfter("private fun applyGatewayPhoneState(")
            .substringBefore("private fun recoverRecentSimNumber(")
        val send = transition.indexOf("sendCallState(if (newState == CALL_IDLE) priorStart else null)")
        val logging = transition.indexOf("Hub.log(\"Phone call: $newState")
        assertTrue("State must be sent before potentially slow diagnostics", send >= 0 && logging > send)
        val remote = src.substringAfter("private fun onRemoteCallUpdate(")
            .substringBefore("private fun showOngoingCall(")
        assertTrue(remote.contains("CALL_OFFHOOK -> {\\n                stopSimRinging()".replace("\\n", "\n")))
        assertTrue(remote.contains("else -> {\\n                simCallHere = false".replace("\\n", "\n")))
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
