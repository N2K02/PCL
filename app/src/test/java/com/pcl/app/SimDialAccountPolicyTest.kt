package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimDialAccountPolicyTest {
    private fun c(subscriptionId: Int, slot: Int, id: String) =
        SimDialAccountPolicy.Candidate(subscriptionId, slot, id)

    @Test fun onlyExactAccountIdForRequestedActiveSlot() {
        assertEquals("8", SimDialAccountPolicy.choose(1,
            listOf(c(7, 0, "7"), c(8, 1, "8"))))
    }

    @Test fun unknownOemAccountsMustNotSilentlyDialDefaultSim() {
        assertNull(SimDialAccountPolicy.choose(1,
            listOf(c(7, 0, "SIM1"), c(8, 1, "SIM2"))))
        assertNull(SimDialAccountPolicy.choose(1,
            listOf(c(7, 0, "7"))))
        assertNull(SimDialAccountPolicy.choose(-1, listOf(c(8, 1, "8"))))
        assertNull(SimDialAccountPolicy.choose(4, listOf(c(8, 1, "8"))))
    }

    @Test fun ambiguousDuplicateAccountFailsClosed() {
        assertNull(SimDialAccountPolicy.choose(0,
            listOf(c(7, 0, "7"), c(7, 0, "7"))))
    }
}
