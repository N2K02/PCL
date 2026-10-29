package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimDialAccountPolicyTest {
    private fun s(id: Int, slot: Int) = SimDialAccountPolicy.Subscription(id, slot)
    private fun a(id: Int?, sim: Boolean = true, tel: Boolean = true) =
        SimDialAccountPolicy.Account(id, sim, tel)

    @Test fun choosesUniqueVerifiedSimSubscriptionRatherThanAccountIdText() {
        assertEquals(1, SimDialAccountPolicy.choose(1,
            listOf(s(21, 0), s(31, 1)), listOf(a(21), a(31))))
    }

    @Test fun rejectsVoipOnlyPhoneAccountsEvenIfSubscriptionMatches() {
        assertNull(SimDialAccountPolicy.choose(0, listOf(s(21, 0)),
            listOf(a(21, sim = false))))
        assertNull(SimDialAccountPolicy.choose(0, listOf(s(21, 0)),
            listOf(a(21, tel = false))))
    }

    @Test fun ambiguousOrDuplicateVoicePhoneAccountsCannotSelectDefault() {
        assertNull(SimDialAccountPolicy.choose(0, listOf(s(21, 0)),
            listOf(a(21), a(21))))
        assertNull(SimDialAccountPolicy.choose(0, listOf(s(21, 0), s(31, 0)),
            listOf(a(21), a(31))))
    }

    @Test fun slotOrAccountMismatchIsUnsupportedNotInferred() {
        assertNull(SimDialAccountPolicy.choose(1,
            listOf(s(21, 0)), listOf(a(21))))
        assertNull(SimDialAccountPolicy.choose(0,
            listOf(s(21, 0)), listOf(a(31))))
        assertNull(SimDialAccountPolicy.choose(-1,
            listOf(s(21, 0)), listOf(a(21))))
        assertNull(SimDialAccountPolicy.choose(2,
            listOf(s(21, 2)), listOf(a(21))))
        assertNull(SimDialAccountPolicy.choose(0,
            listOf(s(21, 0)), listOf(a(null))))
    }

    @Test fun distinctSubscriptionPhoneAccountsCannotBeSelectedOnWrongSim() {
        assertEquals(0, SimDialAccountPolicy.choose(0,
            listOf(s(21, 0), s(31, 1)), listOf(a(21), a(31))))
        assertEquals(1, SimDialAccountPolicy.choose(1,
            listOf(s(21, 0), s(31, 1)), listOf(a(21), a(31))))
    }
}
