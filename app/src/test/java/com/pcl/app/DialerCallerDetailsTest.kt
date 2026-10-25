package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DialerCallerDetailsTest {
    private fun caller(
        uri: String = "",
        person: String = "",
        compat: String = "",
        people: String = "",
        title: String = "",
        text: String = "",
    ) = DialerCallerDetails.fromNotification(uri, person, compat, people, title, text)

    @Test fun prefersStructuredTelephoneUri() {
        assertEquals("+230 5555 1234", caller(uri = "tel:+230 5555 1234", title = "Incoming call").number)
        assertEquals("(230) 5555-1234", caller(uri = "TEL:(230) 5555-1234").number)
    }

    @Test fun recoversNumberFromOtherDialerFields() {
        assertEquals("+230 5555 1234", caller(person = "+230 5555 1234").number)
        assertEquals("55551234", caller(compat = "55551234").number)
        assertEquals("+23055551234", caller(people = "+23055551234").number)
        assertEquals("55551234", caller(title = "55551234").number)
        assertEquals("55551234", caller(title = "Incoming call", text = "55551234").number)
    }

    @Test fun originalNumericTitleSurvivesGenericPersonLabel() {
        val found = caller(person = "Unknown", title = "+230 5555 1234")
        assertEquals("+230 5555 1234", found.number)
        assertEquals("", found.name)
    }

    @Test fun usesContactNameAlongsideNumber() {
        val found = caller(uri = "tel:55551234", person = "Alice")
        assertEquals("55551234", found.number)
        assertEquals("Alice", found.name)
    }

    @Test fun ignoresPlaceholderLabelsAndNonNumbers() {
        val found = caller(person = "UNKNOWN", title = "Incoming call", text = "Call in progress")
        assertEquals("", found.number)
        assertEquals("", found.name)
        assertEquals("", caller(title = "Alice").number)
        assertEquals("Alice", caller(title = "Alice").name)
    }

    @Test fun doesNotInventShortOrEmbeddedNumbers() {
        assertEquals("", caller(text = "Call 1234").number)
        assertEquals("", caller(title = "1234").number)
    }
}
