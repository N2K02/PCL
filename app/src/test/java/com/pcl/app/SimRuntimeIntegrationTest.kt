package com.pcl.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression checks for the reconnected SIM-only live paths. */
class SimRuntimeIntegrationTest {
    private fun source(name: String): String {
        val path = "app/src/main/java/com/pcl/app/$name.kt"
        return listOf(File(path), File("../$path")).first { it.exists() }.readText()
    }

    @Test fun linkLossResetsTrackerAndPendingCallLogSurvives() {
        val service = source("LinkService")
        assertTrue(service.contains("CallHistory.onLinkLost()"))
        assertTrue(service.contains("sendPendingSimEnrichment()"))
        assertTrue(service.contains("PendingSimEnrichment.acknowledge(this, arg1)"))
        assertTrue(service.contains("PendingSimEnrichment.add(this, entry)"))
        assertTrue(service.contains("callLogExecutor.execute {"))
        assertTrue(service.contains("ArrayBlockingQueue<Runnable>(8)"))
        assertTrue(service.contains("callLogExecutor.shutdownNow()"))
    }

    @Test fun firstSnapshotAfterFreshConnectionCannotBecomeSpuriousCall() {
        val service = source("LinkService")
        val start = service.substringAfter("private fun runSession(")
            .substringBefore("val input = DataInputStream")
        assertTrue("Fresh socket must arm the reconnect guard",
            start.contains("if (role == ROLE_REMOTE) CallHistory.onLinkLost()"))
        assertTrue("Guard must be armed before first CALL can arrive",
            start.indexOf("CallHistory.onLinkLost()") < start.indexOf("send(\"HELLO\""))
        val history = source("CallHistory")
        assertTrue(history.contains("if (!recovery.shouldTrack(state)) return null"))
    }

    @Test fun verifiedSimAccountsAreRequiredForDirectedDialAndUi() {
        val link = source("LinkService")
        val screen = source("CallsScreen")
        assertTrue(link.contains("SimDialAccounts.select(this, selectedSlot)"))
        assertTrue(link.contains("TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE"))
        assertTrue(link.contains("fun dialWithSim("))
        assertTrue(screen.contains("actions.simCallFromSlot("))
        assertTrue(screen.contains("RecentCallActionsPolicy.availability("))
        assertTrue(source("Protocol").contains("const val VERSION = 10"))
    }

    @Test fun simHistoryDoesNotAmendArbitraryNewestRow() {
        assertFalse(source("CallHistory").contains("System.currentTimeMillis() - latest.at"))
        assertTrue(source("CallHistory").contains("SimHistoryEnrichment.findUnique("))
        assertTrue(source("CallsScreen").contains("CallHistory.clearSim()"))
    }

    @Test fun staleBluetoothReaderCannotClearFreshSession() {
        val service = source("LinkService")
        val reader = service.substringAfter("private fun runSession(")
            .substringBefore("private fun closeSocket()")
        assertTrue(reader.contains("if (!alive(gen))"))
        assertTrue(reader.contains("if (writer === w) {"))
        val ownerGuard = reader.substringAfter("if (writer === w) {")
        assertTrue(ownerGuard.indexOf("connected = false") > ownerGuard.indexOf("writer = null"))
        assertTrue(ownerGuard.contains("stopRinging()"))
        assertTrue(service.contains("if (serverSocket === ss) serverSocket = null"))
        assertTrue(service.contains("private fun connectOnce(gen: Int)"))
    }

    @Test fun streamInitializationFailureStillExecutesSessionCleanup() {
        val service = source("LinkService")
        val session = service.substringAfter("private fun runSession(")
            .substringBefore("private fun closeSocket()")
        assertTrue(session.contains("try {\n            val peer = try {"))
        val setup = session.indexOf("val input = DataInputStream")
        val cleanup = session.indexOf("} finally {\n            // Only the owner")
        assertTrue("Input setup must be protected by the session finally", setup in 1 until cleanup)
    }
}
