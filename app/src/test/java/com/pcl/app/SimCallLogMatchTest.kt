package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimCallLogMatchTest {
    private fun e(t: Long = 100_000, type: Int = 1,
        number: String? = "+230 5123 4567", name: String? = "Saved name",
        pres: Int = 1, account: String? = "2", component: String? = "com.android.phone") =
        SimCallLogMatch.Entry(t, type, number, name, pres, account, component)

    @Test fun incomingMatchesUniqueCallWithCorrectFullNumber() {
        val result = SimCallLogMatch.choose(100_000, "INCOMING", "5123 4567",
            listOf(e(101_000), e(103_000, number = "+230 5123 9999")))
        assertEquals("+230 5123 4567", result?.number)
    }

    @Test fun matchesMissedOrRejectedForRingingCallsNotOutgoing() {
        assertEquals(SimCallLogMatch.MISSED, SimCallLogMatch.choose(100_000, "INCOMING",
            "", listOf(e(type = 3)))?.type)
        assertEquals(SimCallLogMatch.REJECTED, SimCallLogMatch.choose(100_000, "INCOMING",
            "", listOf(e(type = 5)))?.type)
        assertNull(SimCallLogMatch.choose(100_000, "OUTGOING",
            "", listOf(e(type = 3))))
    }

    @Test fun outgoingMatchesOnlyAnOutgoingType() {
        assertEquals(SimCallLogMatch.OUTGOING, SimCallLogMatch.choose(100_000,
            "OUTGOING", "", listOf(e(type = 2)))?.type)
        assertNull(SimCallLogMatch.choose(100_000, "OUTGOING",
            "", listOf(e(type = 1))))
    }

    @Test fun concurrentOrDuplicateCallsFailClosed() {
        assertNull(SimCallLogMatch.choose(100_000, "INCOMING",
            "", listOf(e(), e(t = 102_000, number = "+230 5555 5555"))))
        assertNull(SimCallLogMatch.choose(100_000, "INCOMING",
            "+230 5123 4567", listOf(e(), e(t = 105_000))))
    }

    @Test fun rejectsStaleOrWrongNumberAndNonCellularVoip() {
        assertNull(SimCallLogMatch.choose(100_000, "INCOMING",
            "", listOf(e(t = 130_000))))
        assertNull(SimCallLogMatch.choose(100_000, "INCOMING",
            "+230 5123 4567", listOf(e(t = 170_000))))
        assertNull(SimCallLogMatch.choose(100_000, "INCOMING",
            "+230 5123 4567", listOf(e(number = "+230 5123 4568"))))
        assertNull(SimCallLogMatch.choose(100_000, "INCOMING",
            "", listOf(e(component = "com.whatsapp/.Call"))))
    }

    @Test fun noDirectionNoStartNoLogCannotInventCall() {
        assertNull(SimCallLogMatch.choose(0, "INCOMING", "", listOf(e())))
        assertNull(SimCallLogMatch.choose(100_000, "", "", listOf(e())))
        assertNull(SimCallLogMatch.choose(100_000, "INCOMING", "", emptyList()))
    }

    @Test fun explicitlyRestrictedCallIsPrivateUnavailableIsNot() {
        val private = SimCallLogMatch.details(e(number = null, pres = 2, name = ""))
        assertEquals(CallerId.PRIVATE, private.name)
        assertEquals("", private.number)
        assertTrue(private.privateCaller)
        val unknown = SimCallLogMatch.details(e(number = null, name = "", pres = 3))
        assertEquals("", unknown.name)
        assertEquals("", unknown.number)
    }

    @Test fun callerNameCannotBeContactFromPhoneAppPlaceholder() {
        val data = SimCallLogMatch.details(e(name = "Unknown number", number = ""))
        assertEquals("", data.name)
        assertEquals("", data.number)
    }
}
