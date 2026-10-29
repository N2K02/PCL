package com.pcl.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transport and UI contracts enforce the same metadata path on both phones. */
class SimCallIntegrationContractTest {
    private fun source(name: String) =
        listOf(File("app/src/main/java/com/pcl/app/$name.kt"),
            File("../app/src/main/java/com/pcl/app/$name.kt"))
            .firstOrNull { it.exists() }?.readText() ?: error("Missing $name")

    @Test fun gatewayDetectsRealActiveSubscriptionWithoutDataSimGuess() {
        val gateway = source("LinkService")
        val detector = source("SimCallIdentity")
        assertTrue(gateway.contains("SimCallDetector.identify(this@LinkService, newState)"))
        assertTrue(detector.contains("getCallStateForSubscription") ||
            detector.contains(".callStateForSubscription"))
        assertTrue(detector.contains("activeSubscriptionInfoList"))
        assertTrue(detector.contains("if (busy.size != 1) return null"))
        assertTrue(detector.contains("else if (valid.size == 1)"))
    }

    @Test fun bluetoothCallCommandKeepsOriginalFourFieldsAndAddsOptionalSim() {
        val service = source("LinkService")
        assertTrue(service.contains("send(\"CALL\", callState, callNumber, callName, callDirection,"))
        assertTrue(service.contains("sim?.slotIndex?.toString().orEmpty()"))
        assertTrue(service.contains("encodeText(sim?.displayName.orEmpty())"))
        assertTrue(service.contains("encodeText(sim?.carrier.orEmpty())"))
        assertTrue(service.contains("p.getOrNull(5)?.toIntOrNull()"))
        assertTrue(service.contains("p.getOrNull(6)?.let(::decodeText)"))
        assertTrue(service.contains("p.getOrNull(7)?.let(::decodeText)"))
    }

    @Test fun remoteStoresSimSlotForMissedAnsweredAndOutgoingCalls() {
        val service = source("LinkService")
        val history = source("CallHistory")
        assertTrue(service.contains("CallHistory.onSimState(callState, callNumber, callName, callSimIdentity)"))
        assertTrue(history.contains("ringingSim = sim"))
        assertTrue(history.contains("val source = ringingSim ?: sim"))
        assertTrue(history.contains("simSlot = source?.slotIndex"))
        assertTrue(history.contains(".put(\"ss\", r.simSlot ?: JSONObject.NULL)"))
        assertTrue(history.contains("simSlot = o.optInt(\"ss\", -1).takeIf { it >= 0 }"))
    }

    @Test fun recentsAndLiveCardsShowSameSourceWithNoGuessedSim() {
        val ui = source("CallsScreen")
        val main = source("MainActivity")
        assertTrue(ui.contains("badges = { RecentCallBadges(r) }"))
        assertTrue(ui.contains("RecentCallChip("))
        assertTrue(ui.contains("Icons.Default.SimCard"))
        assertTrue(main.contains("simIdentity = service?.callSimIdentity"))
        assertTrue(main.contains("service?.callSimIdentity?.let"))
    }

    @Test fun activeNotificationsDoNotExposeRawSimIdentifiersOrInventSlot() {
        val service = source("LinkService")
        assertTrue(service.contains("callSimIdentity?.description().orEmpty()"))
        assertTrue(service.contains("callSimIdentity?.description()?.let"))
        assertTrue(service.contains("callSimIdentity = null"))
    }
}
