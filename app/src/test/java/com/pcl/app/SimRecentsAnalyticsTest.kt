package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimRecentsAnalyticsTest {
    private fun r(type: CallHistory.Type, slot: Int? = null,
                  app: String? = null, whatsapp: Boolean = false, seconds: Long = 0L) =
        CallHistory.Record("", "", type, 100L, seconds, whatsapp, app, slot)

    private val calls = listOf(
        r(CallHistory.Type.INCOMING, 0, seconds = 125),
        r(CallHistory.Type.OUTGOING, 0, seconds = 50),
        r(CallHistory.Type.MISSED, 0),
        r(CallHistory.Type.DECLINED, 1),
        r(CallHistory.Type.INCOMING, 1, seconds = 35),
        r(CallHistory.Type.OUTGOING, null, seconds = 15),
        r(CallHistory.Type.MISSED, 2),
        r(CallHistory.Type.INCOMING, null, app = "Signal"),
        r(CallHistory.Type.MISSED, null, whatsapp = true),
        r(CallHistory.Type.WHATSAPP),
    )

    @Test fun allShowsAllCallsIncludingThirdPartyHistory() {
        assertEquals(10, SimRecentsAnalytics.filter(calls, SimRecentsAnalytics.Filter.ALL).size)
    }

    @Test fun simOneAndSimTwoExcludeOtherSimAndAllVoip() {
        assertEquals(3, SimRecentsAnalytics.filter(calls, SimRecentsAnalytics.Filter.SIM_1).size)
        assertEquals(2, SimRecentsAnalytics.filter(calls, SimRecentsAnalytics.Filter.SIM_2).size)
        assertEquals(2, SimRecentsAnalytics.filter(calls, SimRecentsAnalytics.Filter.UNIDENTIFIED).size)
        assertFalse(SimRecentsAnalytics.isSim(calls[7]))
        assertFalse(SimRecentsAnalytics.isSim(calls[8]))
        assertFalse(SimRecentsAnalytics.isSim(calls[9]))
    }

    @Test fun perSimStatisticsAreDistinctAndDurationIsTalkTimeOnly() {
        assertEquals(SimRecentsAnalytics.Stats(3, 1, 1, 1, 0, 175),
            SimRecentsAnalytics.stats(calls, 0))
        assertEquals(SimRecentsAnalytics.Stats(2, 1, 0, 0, 1, 35),
            SimRecentsAnalytics.stats(calls, 1))
        assertEquals(SimRecentsAnalytics.Stats(2, 0, 1, 1, 0, 15),
            SimRecentsAnalytics.stats(calls, null))
    }

    @Test fun unknownSlotNeverMovesToSimOneOrTwo() {
        val legacy = r(CallHistory.Type.INCOMING)
        assertTrue(SimRecentsAnalytics.matches(legacy, SimRecentsAnalytics.Filter.UNIDENTIFIED))
        assertFalse(SimRecentsAnalytics.matches(legacy, SimRecentsAnalytics.Filter.SIM_1))
        assertFalse(SimRecentsAnalytics.matches(legacy, SimRecentsAnalytics.Filter.SIM_2))
    }

    @Test fun emptyHistoryShowsZeroStatsAndNoCrashes() {
        assertEquals(SimRecentsAnalytics.Stats(0, 0, 0, 0, 0, 0),
            SimRecentsAnalytics.stats(emptyList(), 0))
        assertEquals(emptyList<CallHistory.Record>(),
            SimRecentsAnalytics.filter(emptyList(), SimRecentsAnalytics.Filter.SIM_2))
    }

    @Test fun declinedIsNotMiscountedAsMissedAndNegativeDurationIgnored() {
        val rows = listOf(r(CallHistory.Type.DECLINED, 0, seconds = -12))
        assertEquals(SimRecentsAnalytics.Stats(1, 0, 0, 0, 1, 0),
            SimRecentsAnalytics.stats(rows, 0))
    }
}
