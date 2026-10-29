package com.pcl.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalCallLogIntegrationContractTest {
    private fun source(name: String): String =
        File("app/src/main/java/com/pcl/app/$name.kt").readText()

    @Test fun optionalLookupRequiresGrantedPermissionAndNeverRunsForUnknownDirection() {
        val reader = source("OptionalSimCallLog")
        assertTrue(reader.contains("Manifest.permission.READ_CALL_LOG"))
        assertTrue(reader.contains("if (!canRead(context) || startedAt <= 0L"))
        assertTrue(reader.contains("direction !in setOf(\"INCOMING\", \"OUTGOING\")"))
    }

    @Test fun callLogReaderBoundsAndMatchesBeforeRelaying() {
        val reader = source("OptionalSimCallLog")
        assertTrue(reader.contains("list.size < 40"))
        assertTrue(reader.contains("SimCallLogMatch.choose("))
        assertTrue(reader.contains("knownSim.slotIndex != loggedSim.slotIndex"))
        assertTrue(reader.contains("?.activeSubscriptionInfoList.orEmpty()"))
        assertTrue(reader.contains("subscriptions.singleOrNull()"))
    }
    @Test fun permissionIsOptionalAndNeverPartOfRequiredGatewayConnection() {
        val activity = source("MainActivity")
        val manifest = File("app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.READ_CALL_LOG"))
        val required = activity.substringAfter("private fun gatewayPerms(): List<String>")
            .substringBefore("private fun granted(p: String)")
        assertTrue(!required.contains("READ_CALL_LOG"))
        assertTrue(activity.contains("Call history (optional)"))
        assertTrue(activity.contains("requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), 91)"))
        assertTrue(activity.contains("installer must allowlist it"))
    }

    @Test fun finishedGatewayCallIsEnrichedOnlyAfterEndWithOneRetry() {
        val service = source("LinkService")
        assertTrue(service.contains("enrichCompletedSimCall("))
        assertTrue(service.contains("OptionalSimCallLog.canRead(this)"))
        assertTrue(service.contains("schedule(2_500L, true)"))
        assertTrue(service.contains("schedule(10_000L, false)"))
        assertTrue(service.contains("pendingCallLogTasks.forEach(trafficHandler::removeCallbacks)"))
        assertTrue(service.contains("if (!running || generation != currentGeneration)"))
    }

    @Test fun remoteAcceptsOptionalHistoricalCorrectionsOnlyWhenUniqueAndDirectional() {
        val service = source("LinkService")
        val history = source("CallHistory")
        val protocol = source("Protocol")
        assertTrue(service.contains("\"CALL_LOGGED\" ->"))
        assertTrue(service.contains("CallHistory.amendSim("))
        assertTrue(service.contains("p.getOrNull(8).orEmpty()"))
        assertTrue(protocol.contains("\"CALL\", \"CALL_LOGGED\""))
        assertTrue(history.contains("SimHistoryEnrichment.findUnique(records, originalStartMs,"))
        assertTrue(history.contains("SimHistoryEnrichment.merge("))
    }
}
