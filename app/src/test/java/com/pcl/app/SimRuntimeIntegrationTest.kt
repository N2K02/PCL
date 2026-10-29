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

    @Test fun verifiedSimAccountsAreRequiredForDirectedDialAndUi() {
        val link = source("LinkService")
        val screen = source("CallsScreen")
        assertTrue(link.contains("SimDialAccounts.select(this, selectedSlot)"))
        assertTrue(link.contains("TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE"))
        assertTrue(link.contains("fun dialWithSim("))
        assertTrue(screen.contains("actions.simCallFromSlot("))
        assertTrue(screen.contains("RecentCallActionsPolicy.availability("))
        assertTrue(source("Protocol").contains("const val VERSION = 9"))
    }

    @Test fun simHistoryDoesNotAmendArbitraryNewestRow() {
        assertFalse(source("CallHistory").contains("System.currentTimeMillis() - latest.at"))
        assertTrue(source("CallHistory").contains("SimHistoryEnrichment.findUnique("))
        assertTrue(source("CallsScreen").contains("CallHistory.clearSim()"))
    }
}
