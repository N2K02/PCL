package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSimEnrichmentTest {
    private val now = 1_900_000_000_000L
    private fun e(at: Long = 1000L, created: Long = now,
                  direction: String = "INCOMING", slot: Int? = 0) =
        PendingSimEnrichment.Entry(at, direction, "+23051234567", "Alice", false,
            slot, "Personal", "Operator", created)

    @Test fun queuesOneVerifiedCallAndKeepsExactStableAckId() {
        val single = PendingSimEnrichment.keep(listOf(e()), now)
        assertEquals(1, single.size)
        assertEquals("1000_INCOMING", single.single().id)
    }

    @Test fun severalReconnectionsCannotDuplicateSameCallInOutbox() {
        val entries = listOf(e(), e(), e(created = now - 100L))
        assertEquals(1, PendingSimEnrichment.keep(entries, now).size)
    }

    @Test fun simultaneousIncomingAndOutgoingHaveDifferentIds() {
        val entries = PendingSimEnrichment.keep(
            listOf(e(), e(direction = "OUTGOING")), now)
        assertEquals(2, entries.size)
        assertFalse(entries[0].id == entries[1].id)
    }

    @Test fun expiryAndFutureOrInvalidCallsArePruned() {
        val entries = listOf(
            e(), e(at = 0), e(at = 2, created = now - PendingSimEnrichment.TTL_MS - 1),
            e(at = 3, created = now + 1), e(at = 4, direction = "VOIP"),
            e(at = 5, slot = -1))
        assertEquals(listOf(e()), PendingSimEnrichment.keep(entries, now))
    }

    @Test fun backlogIsBoundedToLastTwelveUniqueCalls() {
        val backlog = (1L..30L).map { e(at = it) }
        val current = PendingSimEnrichment.keep(backlog, now)
        assertEquals(PendingSimEnrichment.MAX_ITEMS, current.size)
        assertEquals(19L, current.first().startedAt)
        assertEquals(30L, current.last().startedAt)
    }

    @Test fun unknownVerifiedSlotIsAllowedWithoutFabricatingSimOne() {
        assertTrue(PendingSimEnrichment.keep(listOf(e(slot = null)), now)
            .single().simSlot == null)
    }
}
