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

    @Test fun legacyGatewayKeepsNormalRingingWithAnAbsoluteLimit() {
        val deadline = RemoteRingDeadline(startedAt = 0L, refreshSupported = false)

        assertEquals(RemoteRingDeadline.MAX_RING_MS, deadline.nextCheckIn(0L))
        assertFalse(deadline.expired(8_000L))
        assertTrue(deadline.expired(RemoteRingDeadline.MAX_RING_MS))
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
