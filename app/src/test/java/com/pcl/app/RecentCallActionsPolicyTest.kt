package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RecentCallActionsPolicyTest {
    private fun r(name: String = "", number: String = "+23051234567",
                  type: CallHistory.Type = CallHistory.Type.INCOMING,
                  whatsapp: Boolean = false, app: String? = null) =
        CallHistory.Record(number, name, type, 1000, 125, whatsapp, app, 0)

    @Test fun quickCallRequiresConnectionAndIdleAndVerifiedNumber() {
        assertTrue(RecentCallActionsPolicy.availability(r(), true, true).quickCall)
        assertFalse(RecentCallActionsPolicy.availability(r(), false, true).quickCall)
        assertFalse(RecentCallActionsPolicy.availability(r(), true, false).quickCall)
        assertFalse(RecentCallActionsPolicy.availability(r(number = ""), true, true).quickCall)
    }

    @Test fun privateUnknownDashAndUnavailableDisableAllNumberDependentActions() {
        for (bad in listOf("", "-", "Unknown number", "Private number", "N/A")) {
            val row = r(name = "Private number", number = bad)
            val a = RecentCallActionsPolicy.availability(row, true, true, setOf(0, 1))
            assertFalse(a.quickCall)
            assertFalse(a.simOne)
            assertFalse(a.simTwo)
            assertFalse(a.share)
            assertFalse(a.copy)
            assertFalse(a.contact)
            assertTrue(a.details)
            assertTrue(a.delete)
            assertNull(RecentCallActionsPolicy.share(row, null))
        }
    }

    @Test fun simSelectionRequiresVerifiedActiveSlotAndNeverAppliesToVoip() {
        val one = RecentCallActionsPolicy.availability(r(), true, true, setOf(0))
        assertTrue(one.simOne)
        assertFalse(one.simTwo)
        val both = RecentCallActionsPolicy.availability(r(), true, true, setOf(0, 1))
        assertTrue(both.simOne)
        assertTrue(both.simTwo)
        val unknown = RecentCallActionsPolicy.availability(r(), true, true)
        assertFalse(unknown.simOne)
        assertFalse(unknown.simTwo)
        val whatsapp = RecentCallActionsPolicy.availability(r(whatsapp = true),
            true, true, setOf(0, 1))
        assertFalse(whatsapp.simOne)
        assertFalse(whatsapp.simTwo)
    }

    @Test fun shareRealContactOrOnlyTheRealNumberNeverPlaceholders() {
        assertEquals("John Smith\n+23051234567",
            RecentCallActionsPolicy.share(r(name = "John Smith"), null))
        assertEquals("Saved name\n+23051234567",
            RecentCallActionsPolicy.share(r(), "Saved name"))
        assertEquals("+23051234567",
            RecentCallActionsPolicy.share(r(name = "Unknown number"), null))
        assertEquals("Private number",
            RecentCallActionsPolicy.title(r(name = "Private number", number = ""), null))
    }

    @Test fun exactDateTimeAndDurationFormat() {
        // The actual hour follows the device timezone; verify exactly the
        // requested display structure without hardcoding a timezone.
        val shown = RecentCallActionsPolicy.dateTime(0L, Locale.US)
        assertTrue(shown.matches(Regex("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}")))
        assertEquals("00:00", RecentCallActionsPolicy.duration(0))
        assertEquals("02:05", RecentCallActionsPolicy.duration(125))
        assertEquals("1:01:01", RecentCallActionsPolicy.duration(3661))
        assertEquals("00:00", RecentCallActionsPolicy.duration(-10))
    }

    @Test fun sourceAwareFallbackAvoidsUnknownCaller() {
        assertEquals("+23051234567", RecentCallActionsPolicy.title(r(), null))
        assertEquals("Missed SIM 1 call",
            RecentCallActionsPolicy.title(r(number = "",
                type = CallHistory.Type.MISSED), null))
    }
}
