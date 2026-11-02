package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM coverage for every surface that displays a PCL caller. */
class CallerIdTest {
    private val contacts = listOf(
        Contact("Alice Smith", "+44 7700 900123"),
        Contact("Bob", "07700 900456"),
        Contact("Bob", "+44 7700 900456"),
        Contact("Carol", "+44 7700 900789"),
        Contact("Carol", "+44 20 7946 0000"),
        Contact("José Ñúñez", "+34 600 111 222"),
        Contact("Mauritius caller", "+230 5123 4567"),
    )

    @Test fun normalizesPhoneNotificationBidiAndNonbreakingSpaces() {
        assertEquals("+44 7700 900123", CallerId.asNumber("\u202A+44 7700 900123\u202C"))
        assertEquals("+44 7700 900123", CallerId.asNumber("\u2066+44\u00A07700\u00A0900123\u2069"))
        assertEquals("+44 7700 900123", CallerId.asNumber("TEL:+44 7700 900123"))
        assertEquals("+44 7700 900123", CallerId.asNumber("Tel: +44 7700 900123"))
        assertEquals("Alice Smith", CallerId.asName("\u200eAlice   Smith\u200f"))
    }

    @Test fun missingAndPlaceholderStringsAreNotPhoneNumbers() {
        for (value in listOf("", "-", "—", "–", "N/A", "unknown number",
                "Unknown caller", "Private number", "555", "WhatsApp",
                "Incoming SIM call", "Call in progress", "null")) {
            assertEquals(value, "", CallerId.asNumber(value))
        }
        assertEquals("", CallerId.asNumber(null))
        assertEquals("", CallerId.asNumber("1234"))
    }

    @Test fun placeholderContactNamesNeverBecomeIdentities() {
        for (name in listOf("-", "—", "–", "n/a", "null", "unknown", "Unknown caller",
                "Unknown number", "Private number", "No caller ID", "Phone",
                "Incoming call", "Outgoing call", "Ongoing call",
                "WhatsApp", "WhatsApp Business", "Incoming WhatsApp video call",
                "WhatsApp video call", "Ongoing Signal voice call",
                "Telegram", "Viber", "Signal", "+230 51234567", "")) {
            assertEquals(name, "", CallerId.asName(name))
        }
        assertEquals("Alice Smith", CallerId.asName("Alice Smith"))
        assertEquals("José Ñúñez", CallerId.asName("José Ñúñez"))
    }

    @Test fun privateLabelsRequireExplicitSignalAndAreNormalized() {
        for (label in listOf("Private number", "private caller", "Withheld",
                "NO CALLER ID", "restricted", "anonymous", "hidden number",
                "Numéro masqué", "Número privado", "Blocked", "private")) {
            assertTrue(label, CallerId.isPrivate(label))
        }
        for (label in listOf("", "-", "unknown", "Unknown number", "SIM",
                "Incoming call", "555", "No number", "Unidentified")) {
            assertFalse(label, CallerId.isPrivate(label))
        }
    }

    @Test fun callerDisplayUsesNameThenVerifiedNumberThenPrivateThenDescription() {
        assertEquals("Alice Smith", CallerId.display("Alice Smith", "+44 7700 900123",
            "Incoming SIM call"))
        assertEquals("+44 7700 900123", CallerId.display("Unknown number", "+44 7700 900123",
            "Incoming SIM call"))
        assertEquals("Private number", CallerId.display("Withheld", "",
            "Incoming SIM call"))
        assertEquals("Incoming SIM call", CallerId.display("-", "-", "Incoming SIM call"))
        assertEquals("Missed WhatsApp call",
            CallerId.display("Unknown caller", null, "Missed WhatsApp call"))
        assertEquals("Call", CallerId.display(null, null, ""))
    }

    @Test fun numberWinsOverConflictingPrivatePlaceholder() {
        assertEquals("+230 51234567", CallerId.display("Private number",
            "+230 51234567", "Incoming SIM call"))
        assertEquals("Alice Smith (+230 51234567)", CallerId.displayWithNumber(
            "Alice Smith", "+230 51234567", "Call"))
        assertEquals("Private number", CallerId.displayWithNumber(
            "No caller ID", "", "Incoming SIM call"))
        assertEquals("Outgoing SIM call", CallerId.displayWithNumber(
            "Unknown", "-", "Outgoing SIM call"))
    }

    @Test fun callSummaryRetainsActualCallDirectionAndApp() {
        assertEquals("Incoming SIM call", CallerId.callSummary("INCOMING", "SIM"))
        assertEquals("Outgoing SIM call", CallerId.callSummary("OUTGOING", "SIM"))
        assertEquals("Missed WhatsApp call", CallerId.callSummary("MISSED", "WhatsApp"))
        assertEquals("Declined Signal call", CallerId.callSummary("DECLINED", "Signal"))
        assertEquals("Telegram call", CallerId.callSummary("", "Telegram"))
        assertEquals("Call", CallerId.callSummary("", "-"))
    }

    @Test fun safeNationalAndInternationalNumberComparison() {
        assertTrue(CallerId.sameNumber("07700 900123", "+44 7700 900123"))
        assertTrue(CallerId.sameNumber("+44 (0)7700-900123", "447700900123"))
        assertTrue(CallerId.sameNumber("5123 4567", "+230 5123 4567"))
        assertTrue(CallerId.sameNumber("+23051234567", "+230 5123 4567"))
        assertFalse(CallerId.sameNumber("+1 700 123 4567", "+2 700 123 4567"))
        assertFalse(CallerId.sameNumber("+230 5123 4567", "+230 5123 4568"))
        assertFalse(CallerId.sameNumber("", "+230 51234567"))
        assertFalse(CallerId.sameNumber("123", "123"))
    }

    @Test fun uniqueExactContactNameCanResolveNumber() {
        assertEquals("+44 7700 900123", CallerId.numberForName("Alice Smith", contacts))
        assertEquals("+44 7700 900123", CallerId.numberForName(
            "\u202Aalice smith\u202C", contacts))
        assertEquals("+34 600 111 222", CallerId.numberForName("José Ñúñez", contacts))
        assertEquals("+230 5123 4567", CallerId.numberForName("Mauritius caller", contacts))
    }

    @Test fun twoFormatsOfSameNumberRemainUnambiguous() {
        assertTrue(CallerId.numberForName("Bob", contacts).isNotEmpty())
    }

    @Test fun ambiguousPartialOrPlaceholderContactNamesNeverGuessNumber() {
        for (label in listOf("Carol", "Alice", "No match", "Unknown number",
                "-", "Private number", "Incoming call", "WhatsApp")) {
            assertEquals(label, "", CallerId.numberForName(label, contacts))
        }
    }

    @Test fun namesWithSimilarLastDigitsAreNotConflatedAcrossCountryCodes() {
        val similar = listOf(
            Contact("Person", "+1 700 123 4567"),
            Contact("Person", "+2 700 123 4567"),
        )
        assertEquals("", CallerId.numberForName("Person", similar))
    }

    @Test fun resolvesExplicitNumberToContactWhenPresent() {
        assertEquals("+44 7700 900123" to "Alice Smith",
            CallerId.resolve("+44 7700 900123", contacts))
        assertEquals("+44 7700 900123" to "Alice Smith",
            CallerId.resolve("Alice Smith", contacts))
        assertEquals("+1 555 0100 999" to "",
            CallerId.resolve("+1 555 0100 999", contacts))
    }

    @Test fun doesNotResolveUnknownPrivateOrAppNameAsCaller() {
        for (value in listOf("Unknown number", "-", "WhatsApp",
                "Private number", "Incoming WhatsApp video call")) {
            assertEquals(value, "" to "", CallerId.resolve(value, contacts))
        }
        assertNull(null)
    }
}
