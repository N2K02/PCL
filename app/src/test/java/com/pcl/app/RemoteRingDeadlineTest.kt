package com.pcl.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRingDeadlineTest {
    @Test fun capableGatewayMustKeepConfirmingRing() {
        val deadline = RemoteRingDeadline(startedAt = 1_000L, refreshSupported = true)

        assertEquals(1_000L, deadline.nextCheckIn(1_000L))
        assertFalse(deadline.expired(3_999L))
        assertTrue(deadline.expired(4_000L))

        deadline.confirm(4_001L)
        assertTrue(deadline.expired(4_001L))
    }

    @Test fun freshConfirmationExtendsRingUpToAbsoluteLimit() {
        val deadline = RemoteRingDeadline(startedAt = 0L, refreshSupported = true)

        for (now in 1_000L..89_000L step 1_000L) {
            deadline.confirm(now)
            assertFalse(deadline.expired(now))
        }

        assertEquals(1_000L, deadline.nextCheckIn(89_000L))
        assertTrue(deadline.expired(RemoteRingDeadline.MAX_RING_MS))
    }

    @Test fun staleStateQueryMustNotExtendThreeSecondDeadline() {
        val deadline = RemoteRingDeadline(startedAt = 10_000L, refreshSupported = true)
        deadline.confirm(11_000L)
        assertFalse(deadline.expired(13_999L))
        assertTrue(deadline.expired(14_000L))
        deadline.confirm(14_001L)
        assertTrue(deadline.expired(14_001L))
    }

    @Test fun legacyGatewayWithoutHeartbeatStopsAfterThreeSeconds() {
        val deadline = RemoteRingDeadline(startedAt = 0L, refreshSupported = false)

        assertEquals(1_000L, deadline.nextCheckIn(0L))
        assertFalse(deadline.expired(2_999L))
        assertTrue(deadline.expired(3_000L))
        deadline.confirm(1_000L)
        assertTrue(deadline.expired(3_000L))
    }

    @Test fun confirmedGatewayHeartbeatStopsAfterThreeSecondsOfSilence() {
        val deadline = RemoteRingDeadline(startedAt = 0L, refreshSupported = true)
        deadline.confirm(2_000L)
        assertFalse(deadline.expired(4_999L))
        assertTrue(deadline.expired(5_000L))
    }

    @Test fun staleHeartbeatCannotExtendRinging() {
        val deadline = RemoteRingDeadline(startedAt = 0L, refreshSupported = true)
        assertFalse(deadline.confirmQuery(1L, 2L, 1_000L))
        assertFalse(deadline.confirmQuery(null, 2L, 1_000L))
        assertFalse(deadline.confirmQuery(0L, 2L, 1_000L))
        assertTrue(deadline.expired(3_000L))
    }

    @Test fun matchingHeartbeatExtendsOnlyWithinActiveDeadline() {
        val deadline = RemoteRingDeadline(startedAt = 0L, refreshSupported = true)
        assertTrue(deadline.confirmQuery(2L, 2L, 2_000L))
        assertFalse(deadline.expired(4_999L))
        assertFalse(deadline.confirmQuery(2L, 3L, 4_000L))
        assertTrue(deadline.expired(5_000L))
        assertFalse(deadline.confirmQuery(3L, 3L, 5_001L))
    }

    @Test fun unsupportedPeerCannotConfirmHeartbeats() {
        val deadline = RemoteRingDeadline(startedAt = 0L, refreshSupported = false)
        assertFalse(deadline.confirmQuery(1L, 1L, 1_000L))
        assertTrue(deadline.expired(3_000L))
    }

    @Test fun gatewayPhoneStateReadsAreNotOnMainThread() {
        val path = "app/src/main/java/com/pcl/app/LinkService.kt"
        val service = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        val check = service.substringAfter("private val gatewayPhoneStateRecheck")
            .substringBefore("private fun scheduleGatewayPhoneStateRecheck")
        val query = service.substringAfter("\"CALL_STATE_QUERY\" -> {")
            .substringBefore("\"AUDIO_MODE\" ->")
        assertTrue(check.contains("callStateExecutor.execute"))
        assertTrue(query.contains("callStateExecutor.execute"))
        assertTrue(query.indexOf("currentGatewayPhoneState()") < query.indexOf("trafficHandler.post"))
        assertTrue(service.contains("sessionGeneration"))
        assertTrue(service.contains("writer !== session"))
        assertTrue(service.contains("confirmQuery(responseId, pendingRingQueryId"))
    }

    @Test fun bothBluetoothLossPathsStopAlertIndependentlyOfLockScreen() {
        val path = "app/src/main/java/com/pcl/app/LinkService.kt"
        val service = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        val session = service.substringAfter("private fun runSession(")
            .substringBefore("private val bluetoothDisconnectReceiver")
        val receiver = service.substringAfter("private val bluetoothDisconnectReceiver")
            .substringBefore("private fun registerBluetoothDisconnectReceiver")
        val alert = service.substringAfter("private fun clearRemoteIncomingAlert")
            .substringBefore("private fun vibrator")

        assertTrue(session.substringAfter("finally {").contains("clearRemoteIncomingAlert()"))
        assertTrue(receiver.contains("BluetoothDevice.ACTION_ACL_DISCONNECTED"))
        assertTrue(receiver.contains("BluetoothAdapter.STATE_TURNING_OFF"))
        assertTrue(receiver.contains("device == session.remoteDevice"))
        assertTrue(service.contains("private fun remoteBluetoothAvailable()"))
        assertTrue(service.contains("!remoteBluetoothAvailable() || deadline.expired(now)"))
        assertTrue(receiver.indexOf("clearRemoteIncomingAlert()") in 0 until receiver.indexOf("session.close()"))
        assertTrue(alert.contains("ringtone?.stop()"))
        assertTrue(alert.contains("vibrator()?.cancel()"))
        assertTrue(alert.contains("nm.cancel(ID_INCOMING)"))
    }

    @Test fun onlyGatewayAcceptsStateQueries() {
        assertTrue("CALL_STATE_QUERY" in Commands.GATEWAY_HANDLES)
        assertFalse("CALL_STATE_QUERY" in Commands.REMOTE_HANDLES)
    }
}
