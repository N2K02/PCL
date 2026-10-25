package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerIdTest {
    private val contacts = listOf(
        Contact("Alice Smith", "+44 7700 900123"),
        Contact("Bob", "07700 900456"),
        Contact("Bob", "+44 7700 900456"),          // same line, two formats
        Contact("Carol", "+44 7700 900789"),
        Contact("Carol", "+44 20 7946 0000"),       // two different lines
        Contact("José Ñúñez", "+34 600 111 222"),
    )

    // ---- numbers as Phone apps and WhatsApp show them

    @Test fun numberWrappedInDirectionMarksIsANumber() {
        assertEquals("+44 7700 900123", CallerId.asNumber("‪+44 7700 900123‬"))
        assertEquals("+44 7700 900123", CallerId.asNumber("⁦+44 7700 900123⁩"))
        assertEquals("+44 7700 900123", CallerId.asNumber("tel:+44 7700 900123"))
    }

    @Test fun namesAndShortCodesAreNotNumbers() {
        assertEquals("", CallerId.asNumber("Alice Smith"))
        assertEquals("", CallerId.asNumber("123"))
        assertEquals("", CallerId.asNumber(null))
    }

    @Test fun placeholdersAreNotNames() {
        for (label in listOf("Incoming call", "Unknown", "unknown number", "Private number",
                "No caller ID", "WhatsApp", "Incoming voice call", " Ongoing call ", "")) {
            assertEquals("'$label' must not become a name", "", CallerId.asName(label))
        }
        assertEquals("Alice Smith", CallerId.asName("‪Alice  Smith‬"))
        assertEquals("", CallerId.asName("+44 7700 900123"))
    }

    @Test fun privateLabelsAreRecognisedButUnknownIsNot() {
        assertTrue(CallerId.isPrivate("Private number"))
        assertTrue(CallerId.isPrivate("No caller ID"))
        assertTrue(CallerId.isPrivate("Withheld"))
        assertFalse(CallerId.isPrivate("Unknown"))
        assertFalse(CallerId.isPrivate("Alice"))
    }

    @Test fun sameNumberAcrossFormats() {
        assertTrue(CallerId.sameNumber("07700 900123", "+44 7700 900123"))
        assertTrue(CallerId.sameNumber("+44 (0)7700-900123", "447700900123"))
        assertFalse(CallerId.sameNumber("+44 7700 900123", "+44 7700 900124"))
        assertFalse(CallerId.sameNumber("", "+44 7700 900123"))
        assertTrue(CallerId.sameNumber("12345", "12345"))
    }

    // ---- name -> number (WhatsApp and Phone apps that show only the contact name)

    @Test fun uniqueNameFindsItsNumber() {
        assertEquals("+44 7700 900123", CallerId.numberForName("Alice Smith", contacts))
        assertEquals("+44 7700 900123", CallerId.numberForName("‪alice smith‬", contacts))
        assertEquals("+34 600 111 222", CallerId.numberForName("José Ñúñez", contacts))
    }

    @Test fun sameLineInTwoFormatsIsStillUnique() {
        assertTrue(CallerId.numberForName("Bob", contacts).isNotEmpty())
    }

    @Test fun ambiguousOrUnknownNameGivesNoNumber() {
        assertEquals("", CallerId.numberForName("Carol", contacts))
        assertEquals("", CallerId.numberForName("Dave", contacts))
        assertEquals("", CallerId.numberForName("Alice", contacts))   // partial name never matches
        assertEquals("", CallerId.numberForName("Unknown", contacts))
    }

    @Test fun resolveHandlesNumberOrName() {
        assertEquals("+44 7700 900123" to "Alice Smith", CallerId.resolve("‪07700 900123‬".replace("07700", "+44 7700"), contacts))
        assertEquals("+44 7700 900123" to "Alice Smith", CallerId.resolve("Alice Smith", contacts))
        assertEquals("+1 555 0100 999" to "", CallerId.resolve("+1 555 0100 999", contacts))
        assertEquals("" to "", CallerId.resolve("Incoming call", contacts))
    }

    // ---- Recents corrected from the Gateway's call log

    private fun record(number: String = "", name: String = "") =
        CallHistory.Record(number, name, CallHistory.Type.MISSED, 1_000L)

    @Test fun callLogFillsMissingNumberAndName() {
        val r = CallHistory.amended(record(), "+44 7700 900123", "Alice Smith", withheld = false)
        assertEquals("+44 7700 900123", r?.number)
        assertEquals("Alice Smith", r?.name)
    }

    @Test fun withheldCallBecomesPrivateNumber() {
        assertEquals(CallerId.PRIVATE, CallHistory.amended(record(), "", "", withheld = true)?.name)
    }

    @Test fun callLogNeverOverwritesADifferentKnownNumber() {
        assertNull(CallHistory.amended(record("+44 7700 900456", "Bob"), "+44 7700 900123", "Alice", false))
        assertNull(CallHistory.amended(record("+44 7700 900456", "Bob"), "", "", withheld = true))
    }

    @Test fun callLogKeepsExistingNameAndAddsNumber() {
        val r = CallHistory.amended(record("", "Alice Smith"), "+44 7700 900123", "", false)
        assertEquals("+44 7700 900123", r?.number)
        assertEquals("Alice Smith", r?.name)
        assertNull(CallHistory.amended(record("+44 7700 900123", "Alice Smith"), "07700 900123", "Alice Smith", false))
    }
}
