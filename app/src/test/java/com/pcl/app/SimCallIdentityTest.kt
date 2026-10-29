package com.pcl.app

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimCallIdentityTest {
    private val idle = TelephonyManager.CALL_STATE_IDLE
    private val ring = TelephonyManager.CALL_STATE_RINGING
    private val active = TelephonyManager.CALL_STATE_OFFHOOK

    private fun sim(slot: Int, state: Int? = null, name: String = "", carrier: String = "") =
        SimCallSelection.Candidate(slot, name, carrier, state)

    @Test fun picksUniqueActiveIncomingLineOnDualSim() {
        val result = SimCallSelection.choose(
            listOf(sim(0, idle, "My SIM", "Carrier A"),
                sim(1, ring, "Work", "Carrier B")), ring, true)
        assertEquals(1, result?.slotIndex)
        assertEquals("SIM 2 · Work", result?.description())
    }

    @Test fun picksUniqueOutgoingLineOnDualSim() {
        assertEquals(SimCallIdentity(0, "Personal"),
            SimCallSelection.choose(listOf(sim(0, active, "Personal"),
                sim(1, idle, "Other")), active, true))
    }

    @Test fun twoActiveCallsNeverInventWhichSimWasUsed() {
        assertNull(SimCallSelection.choose(listOf(sim(0, active),
            sim(1, ring)), ring, true))
        assertNull(SimCallSelection.choose(listOf(sim(0, active),
            sim(1, active)), active, true))
    }

    @Test fun oldAndroidCanIdentifyOnlySingleActiveSubscription() {
        assertEquals(SimCallIdentity(0, "Primary"),
            SimCallSelection.choose(listOf(sim(0, null, "Primary")),
                active, false))
        assertNull(SimCallSelection.choose(listOf(sim(0), sim(1)), ring, false))
    }

    @Test fun unsupportedPerSimStateNeverSilentlyUsesDefaultDataSim() {
        assertNull(SimCallSelection.choose(listOf(sim(0, null), sim(1, null)), active, true))
        assertNull(SimCallSelection.choose(listOf(sim(0, null)), active, true))
    }

    @Test fun mismatchedStateEmptyOrDuplicateSlotsFailClosed() {
        assertNull(SimCallSelection.choose(listOf(sim(0, ring)), active, true))
        assertNull(SimCallSelection.choose(emptyList(), ring, true))
        assertNull(SimCallSelection.choose(listOf(sim(0, ring), sim(0, ring)), ring, true))
        assertNull(SimCallSelection.choose(listOf(sim(-1, ring)), ring, true))
    }

    @Test fun carriersAndNamesAreDisplayOnlyNeverCallerNumbers() {
        val plain = SimCallIdentity(0, "", "Carrier A")
        assertEquals("SIM 1", plain.badge())
        assertEquals("SIM 1 · Carrier A", plain.description())
        assertEquals("SIM 2", SimCallIdentity(1).description())
        assertEquals("SIM 1", SimCallIdentity(0, "-").description())
        assertEquals("SIM 1", SimCallIdentity(0, "Unknown number").description())
    }
}
