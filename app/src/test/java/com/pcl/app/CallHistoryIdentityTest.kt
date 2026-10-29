package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHistoryIdentityTest {
    private fun record(
        name: String = "",
        number: String = "",
        type: CallHistory.Type = CallHistory.Type.INCOMING,
        whatsapp: Boolean = false,
        sourceApp: String? = null,
    ) = CallHistory.Record(number, name, type, 1_000L, 12L, whatsapp, sourceApp)

    @Test fun oldHistoryPlaceholderGetsCleanedOnLoad() {
        for (bad in listOf("-", "Unknown number", "unknown caller", "N/A",
                "WhatsApp", "Incoming call", "—")) {
            val clean = CallHistory.normalize(record(name = bad, number = "-"))
            assertEquals(bad, "", clean.name)
            assertEquals(bad, "", clean.number)
        }
    }

    @Test fun explicitPrivateCallerSurvivesCanonicalizationWithoutMadeUpNumber() {
        val private = CallHistory.normalize(record(name = "Withheld", number = "-"))
        assertEquals("Private number", private.name)
        assertEquals("", private.number)
    }

    @Test fun existingContactAndRealPhoneNumberAreRetained() {
        val clean = CallHistory.normalize(record(name = "Alice", number = "+230 5123 4567"))
        assertEquals("Alice", clean.name)
        assertEquals("+230 5123 4567", clean.number)
        assertEquals(12L, clean.durationSec)
        assertEquals(1_000L, clean.at)
    }

    @Test fun legacyNumberInNameFieldMovesToActualNumberInsteadOfBeingLost() {
        val clean = CallHistory.normalize(record(name = "+230 5123 4567", number = ""))
        assertEquals("", clean.name)
        assertEquals("+230 5123 4567", clean.number)
    }

    @Test fun privateOnlyWhenNoActualNumberExists() {
        val clean = CallHistory.normalize(record(name = "Private caller", number = "+23051234567"))
        assertEquals("", clean.name)
        assertEquals("+23051234567", clean.number)
    }

    @Test fun preservesWhatsappFlagAndSourceAppForOtherCallTypes() {
        val whatsapp = CallHistory.normalize(record(
            name = "Bob", whatsapp = true, type = CallHistory.Type.MISSED))
        assertTrue(whatsapp.whatsapp)
        val signal = CallHistory.normalize(record(
            name = "Unknown", sourceApp = "Signal", type = CallHistory.Type.DECLINED))
        assertEquals("Signal", signal.sourceApp)
        assertEquals("Declined Signal call", CallerId.callSummary(signal.type.name,
            signal.sourceApp.orEmpty()))
        val dashApp = CallHistory.normalize(record(sourceApp = "-"))
        assertEquals(null, dashApp.sourceApp)
    }

    @Test fun recentsFallbackCoversAllCallOutcomes() {
        for ((kind, label) in listOf(
            CallHistory.Type.INCOMING to "Incoming SIM call",
            CallHistory.Type.OUTGOING to "Outgoing SIM call",
            CallHistory.Type.MISSED to "Missed SIM call",
            CallHistory.Type.DECLINED to "Declined SIM call",
        )) {
            val history = CallHistory.normalize(record(type = kind, number = "Unknown number"))
            assertEquals(label, CallerId.display(history.name, history.number,
                CallerId.callSummary(history.type.name, "SIM")))
        }
    }

    @Test fun oldLegacyWhatsappTypeDoesNotInventCallerIdentity() {
        val legacy = CallHistory.normalize(record(type = CallHistory.Type.WHATSAPP,
            name = "-", number = "-", whatsapp = true))
        assertEquals("WhatsApp call",
            CallerId.display(legacy.name, legacy.number,
                CallerId.callSummary(legacy.type.name, "WhatsApp")))
    }

    @Test fun titleWithNoIdentityKeepsSourceSpecificSummary() {
        val remote = CallHistory.normalize(record(
            name = "Unknown caller", number = "", sourceApp = "Telegram"))
        assertEquals("Incoming Telegram call",
            CallerId.display(remote.name, remote.number,
                CallerId.callSummary(remote.type.name, remote.sourceApp!!)))
    }
}
