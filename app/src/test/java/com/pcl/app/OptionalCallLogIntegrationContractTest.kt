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
}
