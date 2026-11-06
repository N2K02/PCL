package com.pcl.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LinkTrafficPolicyTest {
    @Test fun connectedThroughputRetainsFiveSecondResolution() {
        assertEquals(5000L, LinkTrafficPolicy.nextIntervalMs(true))
        assertEquals(30000L, LinkTrafficPolicy.nextIntervalMs(false))
    }

    @Test fun reconnectRebasesCountersAndShutdownStopsPolling() {
        val source = listOf(
            File("src/main/java/com/pcl/app/LinkService.kt"),
            File("app/src/main/java/com/pcl/app/LinkService.kt")
        ).first { it.exists() }.readText()
        assertTrue(source.contains("trafficHandler.postDelayed(this, LinkTrafficPolicy.nextIntervalMs(connected))"))
        assertTrue(source.contains("val changed = connected != isConnected"))
        assertTrue(source.contains("lastRxSample = rxTotal.get()"))
        assertTrue(source.contains("lastTxSample = txTotal.get()"))
        assertTrue(source.contains("if (running) trafficHandler.postDelayed("))
        assertTrue(source.contains("trafficHandler.removeCallbacks(trafficSampler)"))
        assertTrue(source.contains("(rx - lastRxSample).coerceAtLeast(0L) * 1000L / TRAFFIC_SAMPLE_MS"))
    }
}
