package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimHistoryEnrichmentTest {
    private fun r(
        type: CallHistory.Type = CallHistory.Type.INCOMING,
        time: Long = 100_000,
        number: String = "", name: String = "",
        slot: Int? = null, app: String? = null,
    ) = CallHistory.Record(number, name, type, time, 30L, sourceApp = app, simSlot = slot)

    @Test fun matchesOneCellularRecordNotNeighboringWhatsapp() {
        val rows = listOf(r(), r(app = "WhatsApp"))
        assertEquals(0, SimHistoryEnrichment.findUnique(rows, 100_000, "INCOMING"))
    }

    @Test fun overlappingSimCallsOrDirectionMismatchAreRejected() {
        assertNull(SimHistoryEnrichment.findUnique(listOf(r(), r(time = 101_000)),
            100_000, "INCOMING"))
        assertNull(SimHistoryEnrichment.findUnique(listOf(r()), 100_000, "OUTGOING"))
        assertNull(SimHistoryEnrichment.findUnique(listOf(r()), 100_000, ""))
        assertNull(SimHistoryEnrichment.findUnique(listOf(r()), 190_000, "INCOMING"))
        assertNull(SimHistoryEnrichment.findUnique(listOf(r()), 0, "INCOMING"))
    }

    @Test fun preservesRecordedNumberOrNameWhenLogOnlyProvidesPlaceholders() {
        val old = r(name = "Saved contact", number = "+23051234567")
        assertNull(SimHistoryEnrichment.merge(old, "Unknown number", "-", false, null))
    }

    @Test fun onlyVerifiedNumberAndNewNameCanEnrichMissingDetails() {
        val new = SimHistoryEnrichment.merge(r(), "+230 51234567", "Saved caller",
            false, SimCallIdentity(1, "Work", "Carrier"))
        assertEquals("+230 51234567", new?.number)
        assertEquals("Saved caller", new?.name)
        assertEquals(1, new?.simSlot)
    }

    @Test fun explicitRestrictedNumberIsPrivateButUnavailableIsNot() {
        assertEquals("Private number",
            SimHistoryEnrichment.merge(r(), "", "", true, null)?.name)
        assertNull(SimHistoryEnrichment.merge(r(), "", "", false, null))
    }

    @Test fun duplicateOrConflictingSlotOrNumberNeverChangesHistory() {
        assertNull(SimHistoryEnrichment.merge(r(slot = 0), null, "New",
            false, SimCallIdentity(1)))
        assertNull(SimHistoryEnrichment.merge(r(number = "+23051234567"),
            "+23051234568", "New", false, null))
    }

    @Test fun neverEnrichThirdPartyOrWhatsappRecords() {
        assertNull(SimHistoryEnrichment.merge(r(app = "Signal"),
            "+23051234567", "Someone", false, null))
        assertNull(SimHistoryEnrichment.findUnique(
            listOf(r(app = "WhatsApp")), 100_000, "INCOMING"))
    }

    @Test fun missedAndDeclinedRemainDistinct() {
        for (type in listOf(CallHistory.Type.MISSED, CallHistory.Type.DECLINED)) {
            val old = r(type = type)
            val changed = SimHistoryEnrichment.merge(old, "+23051234567", "", false, null)
            assertEquals(type, changed?.type)
        }
    }
}
