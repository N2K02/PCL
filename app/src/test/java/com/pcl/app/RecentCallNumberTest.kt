package com.pcl.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentCallNumberTest {
    @Test fun acceptsUnsavedDialableNumbers() {
        assertTrue(RecentCallNumber.isDialable("57981490"))
        assertTrue(RecentCallNumber.isDialable("+230 5798 1490"))
    }

    @Test fun rejectsPrivateUnknownAndMalformedCallerIds() {
        for (value in listOf("", "Private number", "Unknown number", "No caller ID",
                "Withheld", "Anonymous", "12", "Incoming call", "123;456")) {
            assertFalse(value, RecentCallNumber.isDialable(value))
        }
    }
}
