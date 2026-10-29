package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentCallPresentationTest {
    @Test fun verifiedSimBadgesAreOneBasedAndStable() {
        assertEquals("SIM 1", RecentCallPresentation.source(null, false, SimCallIdentity(0)))
        assertEquals("SIM 2", RecentCallPresentation.source(null, false, SimCallIdentity(1)))
        assertEquals("SIM", RecentCallPresentation.source(null, false, null))
    }

    @Test fun whatsappAndOtherCallingAppsRemainIndependentOfSim() {
        assertEquals("WhatsApp", RecentCallPresentation.source(null, true, SimCallIdentity(0)))
        assertEquals("Signal", RecentCallPresentation.source("Signal", false, null))
        assertTrue(RecentCallPresentation.isVoip(null, true))
        assertTrue(RecentCallPresentation.isVoip("Signal", false))
        assertFalse(RecentCallPresentation.isVoip(null, false))
    }

    @Test fun everyCallOutcomeGetsItsOwnLabelWithoutUnknownPlaceholder() {
        val names = listOf("Incoming", "Outgoing", "Missed", "Declined", "Call")
        assertEquals(names, CallHistory.Type.entries.map(RecentCallPresentation::outcome))
    }

    @Test fun sourceNamesNeverBecomeCallerIdentity() {
        assertEquals("", CallerId.asName("WhatsApp"))
        assertEquals("", CallerId.asName("SIM"))
        assertEquals("Incoming SIM call", CallerId.callSummary("INCOMING", "SIM"))
    }
}
