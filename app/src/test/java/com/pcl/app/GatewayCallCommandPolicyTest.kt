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
            request.indexOf("else -> send(command, request.toString(), pendingCallToken?.toString().orEmpty())"))
        assertTrue(request.indexOf("pendingReject = command == \"REJECT\"") <
            request.indexOf("else -> send(command, request.toString(), pendingCallToken?.toString().orEmpty())"))
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

    @Test fun answerOwnershipRequiresBothCorrelatedAckAndOffhook() {
        fun owns(acked: Boolean, state: String) =
            RemoteCallOwnershipPolicy.claims(true, "ANSWER", acked, state, 0L, 200L, 60_000L)
        // CALL may arrive before CALL_RESULT or vice versa.
        assertFalse(owns(false, LinkService.CALL_OFFHOOK))
        assertFalse(owns(true, LinkService.CALL_RINGING))
        assertTrue(owns(true, LinkService.CALL_OFFHOOK))
        assertFalse(RemoteCallOwnershipPolicy.claims(true, "REJECT", true,
            LinkService.CALL_OFFHOOK, 0L, 200L, 60_000L))
    }

    @Test fun dialOwnershipExpiresAfterSingleCallAndRequiresAcknowledgement() {
        fun owns(command: String?, ack: Boolean, time: Long, now: Long) =
            RemoteCallOwnershipPolicy.claims(false, command, ack,
                LinkService.CALL_OFFHOOK, time, now, 60_000L)
        assertTrue(owns("DIAL", true, 1_000L, 2_000L))
        assertTrue(owns("DIAL_SIM", true, 1_000L, 2_000L))
        assertFalse(owns("DIAL", false, 1_000L, 2_000L))
        assertFalse(owns(null, true, 1_000L, 2_000L))
        assertFalse(owns("DIAL", true, 1_000L, 61_000L))
        assertFalse(owns("DIAL", true, 1_000L, 999L))
        assertFalse(owns("DIAL", true, 0L, 2_000L))
    }

    @Test fun serviceTracksDialBeforeWriteAndResolvesOwnershipOnLateAck() {
        val path = "app/src/main/java/com/pcl/app/LinkService.kt"
        val service = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        val request = service.substringAfter("private fun requestGateway(")
            .substringBefore("private fun clearPendingCall()")
        assertTrue(request.indexOf("CallHistory.onDialRequested(argument, displayName)") <
            request.indexOf("else -> send(command, request.toString(), pendingCallToken?.toString().orEmpty())"))
        assertTrue(request.indexOf("simDialAt = SystemClock.elapsedRealtime()") <
            request.indexOf("else -> send(command, request.toString(), pendingCallToken?.toString().orEmpty())"))
        val ack = service.substringAfter("\"CALL_RESULT\" -> {")
            .substringBefore("\"CONTACTS_HASH\" -> {")
        assertTrue(ack.indexOf("pendingCallAccepted = true") < ack.indexOf("if (remoteOwnsPendingCall())"))
        val remote = service.substringAfter("private fun onRemoteCallUpdate(")
            .substringBefore("private fun showOngoingCall()")
        assertTrue(remote.contains("if (remoteOwnsPendingCall()) simCallHere = true"))
    }

    @Test fun gatewayDialDoesNotBlockBluetoothReaderOnContactLookup() {
        val path = "app/src/main/java/com/pcl/app/LinkService.kt"
        val service = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        val dial = service.substringAfter("private fun gatewayDial(")
            .substringBefore("private fun lookupName(")
        assertFalse(dial.contains("callName = lookupName(n)"))
        assertTrue(dial.contains("callNumber = previousNumber"))
        assertTrue(dial.contains("callName = previousName"))
        assertTrue(dial.contains("callDirection = previousDirection"))
        assertTrue(service.contains("private fun refreshPhoneDetails("))
    }

    @Test fun disconnectedCallNotificationDoesNotUseDeviceBootAsStartTime() {
        val path = "app/src/main/java/com/pcl/app/LinkService.kt"
        val service = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        val ongoing = service.substringAfter("private fun showOngoingCall()")
            .substringBefore("private fun startRinging()")
        assertTrue(ongoing.contains(".setUsesChronometer(connectedAt > 0L)"))
        assertTrue(ongoing.contains("else System.currentTimeMillis()"))
    }
}
