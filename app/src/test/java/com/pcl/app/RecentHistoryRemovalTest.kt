package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentHistoryRemovalTest {
    private fun entry(at: Long, number: String = "+23051234567",
                      type: CallHistory.Type = CallHistory.Type.INCOMING) =
        CallHistory.Record(number, "Caller", type, at, 30)

    @Test fun deletingOneRowPreservesOtherCallsAndTheirOrder() {
        val first = entry(3_000)
        val second = entry(2_000)
        val third = entry(1_000)
        assertEquals(listOf(first, third),
            CallHistory.withoutOne(listOf(first, second, third), second))
    }

    @Test fun duplicateDataDeletesExactSelectedInstanceNotAllDuplicates() {
        val first = entry(3_000)
        val duplicate = first.copy()
        val records = listOf(first, duplicate)
        val result = CallHistory.withoutOne(records, duplicate)!!
        assertEquals(1, result.size)
        assertTrue(result.single() === first)
    }

    @Test fun unknownRecordIsNotDeletedByTimeOrPhoneNumberGuess() {
        assertNull(CallHistory.withoutOne(listOf(entry(3_000)),
            entry(3_000, "+23051230000")))
    }

    @Test fun differentDirectionsAtSameTimestampAreIndependent() {
        val received = entry(3_000)
        val placed = entry(3_000, type = CallHistory.Type.OUTGOING)
        assertEquals(listOf(placed),
            CallHistory.withoutOne(listOf(received, placed), received))
    }

    @Test fun sameExactValueDetachedFromHistoryIsAmbiguousAndRefused() {
        val first = entry(3_000)
        val duplicate = first.copy()
        val detached = first.copy()
        assertNull(CallHistory.withoutOne(listOf(first, duplicate), detached))
    }
}
