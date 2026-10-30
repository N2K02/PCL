package com.pcl.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gateway's real call state, rather than an old Remote button, determines safety. */
class GatewayCallCommandPolicyTest {
    @Test fun answerAndRejectOnlyWhenRinging() {
        for (cmd in listOf("ANSWER", "REJECT")) {
            assertTrue(GatewayCallCommandPolicy.allows(cmd, LinkService.CALL_RINGING))
            assertFalse(GatewayCallCommandPolicy.allows(cmd, LinkService.CALL_OFFHOOK))
            assertFalse(GatewayCallCommandPolicy.allows(cmd, LinkService.CALL_IDLE))
        }
    }

    @Test fun hangupOnlyWhileConnected() {
        assertTrue(GatewayCallCommandPolicy.allows("HANGUP", LinkService.CALL_OFFHOOK))
        assertFalse(GatewayCallCommandPolicy.allows("HANGUP", LinkService.CALL_IDLE))
        assertFalse(GatewayCallCommandPolicy.allows("HANGUP", LinkService.CALL_RINGING))
    }

    @Test fun bothDialVariantsMustStartWhenIdle() {
        for (cmd in listOf("DIAL", "DIAL_SIM")) {
            assertTrue(GatewayCallCommandPolicy.allows(cmd, LinkService.CALL_IDLE))
            assertFalse(GatewayCallCommandPolicy.allows(cmd, LinkService.CALL_RINGING))
            assertFalse(GatewayCallCommandPolicy.allows(cmd, LinkService.CALL_OFFHOOK))
        }
        assertFalse(GatewayCallCommandPolicy.allows("UNKNOWN", LinkService.CALL_IDLE))
    }

    @Test fun remoteOwnershipIsSetBeforeBluetoothSendAndResetAtCallBoundaries() {
        val path = "app/src/main/java/com/pcl/app/LinkService.kt"
        val service = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        val request = service.substringAfter("private fun requestGateway(")
            .substringBefore("private fun clearPendingCall()")
        assertTrue(request.indexOf("pendingAnswer = command == \"ANSWER\"") <
            request.indexOf("else -> send(command, request.toString())"))
        assertTrue(request.indexOf("pendingReject = command == \"REJECT\"") <
            request.indexOf("else -> send(command, request.toString())"))
        val remote = service.substringAfter("private fun onRemoteCallUpdate(")
            .substringBefore("private fun showOngoingCall()")
        assertTrue(remote.contains("simDialAt = 0L"))
        assertTrue(remote.indexOf("simDialAt = 0L") < remote.indexOf("if (simCallHere)"))
    }

    @Test fun ringtoneAndVibrationStartOnlyAtNewIncomingTransition() {
        val path = "app/src/main/java/com/pcl/app/LinkService.kt"
        val service = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        val remote = service.substringAfter("private fun onRemoteCallUpdate(")
            .substringBefore("private fun showOngoingCall()")
        val initial = remote.substringAfter("CALL_RINGING -> {").substringBefore("CALL_OFFHOOK -> {")
        assertTrue(initial.contains("if (previous != CALL_RINGING)"))
        assertTrue(initial.contains("startRinging()"))
        assertFalse(initial.substringAfter("showIncoming()").contains("startRinging()"))
    }
}
