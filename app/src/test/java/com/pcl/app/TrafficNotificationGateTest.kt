package com.pcl.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficNotificationGateTest {
    @Test fun coalescesFastSamplesButPublishesLatestAfterWindow() {
        val gate = TrafficNotificationGate()
        assertTrue(gate.shouldPublish(0, 0, 0))
        assertFalse(gate.shouldPublish(3000, 50, 10))
        assertFalse(gate.shouldPublish(6000, 50, 10))
        assertTrue(gate.shouldPublish(12000, 50, 10))
        assertFalse(gate.shouldPublish(24000, 50, 10))
        assertTrue(gate.shouldPublish(27000, 0, 0))
    }

    @Test fun aNewLinkResetsNotificationThrottle() {
        val gate = TrafficNotificationGate()
        assertTrue(gate.shouldPublish(5000, 100, 0))
        assertFalse(gate.shouldPublish(7000, 0, 0))
        gate.reset()
        assertTrue(gate.shouldPublish(7000, 0, 0))
    }
}
