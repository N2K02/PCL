package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteTest {
    @Test fun switchOffersTheOtherOutput() {
        assertEquals("SPEAKER", LinkService.otherRoute("EARPIECE"))
        assertEquals("EARPIECE", LinkService.otherRoute("SPEAKER"))
        // Headset or unknown: the useful switch is to the loudspeaker.
        assertEquals("SPEAKER", LinkService.otherRoute("BLUETOOTH"))
        assertEquals("SPEAKER", LinkService.otherRoute("UNKNOWN"))
        assertEquals("Switch to speaker", LinkService.routeSwitchLabel("EARPIECE"))
        assertEquals("Switch to earpiece", LinkService.routeSwitchLabel("SPEAKER"))
    }

    @Test fun phoneAppSpeakerButtonsAreRecognised() {
        for (label in listOf("Speaker", "Loudspeaker", "Turn on speaker", "Haut-parleur", "Altavoz", "免提"))
            assertTrue(label, WhatsAppCalls.isSpeaker(label))
        for (label in listOf("Hang up", "Mute", "Answer", "Hold"))
            assertFalse(label, WhatsAppCalls.isSpeaker(label))
    }

    @Test fun remoteExplainsFailedAudioRequests() {
        assertEquals(null, LinkService.audioOutcomeNote("VOLUME", "APPLIED"))
        assertEquals(null, LinkService.audioOutcomeNote("ROUTE", "APPLIED"))
        assertTrue(LinkService.audioOutcomeNote("ROUTE", "NOT_APPLIED")!!.contains("Accessibility"))
        assertTrue(LinkService.audioOutcomeNote("VOLUME", "NOT_APPLIED")!!.contains("volume"))
        assertEquals("No call on the Gateway to switch", LinkService.audioOutcomeNote("ROUTE", "NO_CALL"))
    }
}
