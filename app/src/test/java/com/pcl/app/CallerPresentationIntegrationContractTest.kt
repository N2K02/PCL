package com.pcl.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level regression contracts for all UI/transport integration points.
 *  Behavioral parsing, ambiguity and fallbacks are tested by CallerIdTest. */
class CallerPresentationIntegrationContractTest {
    private fun source(name: String): String {
        val path = "app/src/main/java/com/pcl/app/$name.kt"
        return listOf(File(path), File("../$path")).firstOrNull { it.isFile }
            ?.readText() ?: error("Missing $path")
    }

    @Test fun incomingAndOngoingSimCardsUseSharedCleanedCallerDisplay() {
        val service = source("LinkService")
        val ui = source("CallsScreen")
        assertTrue(service.contains("fun display(): String = CallerId.displayWithNumber("))
        assertTrue(ui.contains("Text(ui.callDisplay,"))
        assertTrue(service.contains(".setContentText(display())"))
        assertFalse(service.contains("else -> \"Unknown number\"\n    }\n\n    // ---------------------------------------------------------------- remote: ringing"))
    }

    @Test fun recentsOldEntriesUseNameNumberAndCallDetailsWithoutUnknown() {
        val history = source("CallHistory")
        val ui = source("CallsScreen")
        assertTrue(history.contains("normalize(Record("))
        assertTrue(history.contains("val canonical = normalize(record)"))
        assertTrue(history.contains("val number = CallerId.asNumber(record.number)"))
        assertTrue(ui.contains("RecentCallCard("))
        assertTrue(source("RecentCallCard").contains("RecentCallActionsPolicy.title(record, lookedUpName)"))
        assertTrue(ui.contains("CallerId.callSummary(r.type.name, source)"))
        assertFalse(ui.contains("r.number.ifEmpty { \"Unknown number\" }"))
    }

    @Test fun remoteKeepsExplicitPrivateCallerButNotGenericPhonePlaceholders() {
        val service = source("LinkService")
        assertTrue(service.contains("callName = CallerId.asName(receivedName).ifEmpty"))
        assertTrue(service.contains("CallerId.isPrivate(receivedName)"))
        assertTrue(service.contains("CallerId.asNumber(p.getOrElse(2)"))
        assertTrue(service.contains("if (callNumber.isEmpty() && callName.isNotEmpty()"))
    }

    @Test fun gatewayNotificationReadsPersonCompatTitleAndNumberIndependently() {
        val listener = source("NotifListener")
        assertTrue(listener.contains("val allLabels = listOf(uriNumber, personName, compatCallerName(ex), title, text)"))
        assertTrue(listener.contains("CallerId.asNumber(it)"))
        assertTrue(listener.contains("CallerId.asName(it)"))
        assertTrue(listener.contains("allLabels.any(CallerId::isPrivate)"))
        assertTrue(listener.contains("svc.updateCallerFromNotification(candidate,"))
    }

    @Test fun contactInferenceRejectsAmbiguousNamesAndPrivateLabels() {
        val service = source("LinkService")
        assertTrue(service.contains("CallerId.numberForName(name, matches)"))
        assertTrue(service.contains("CallerId.numberForName(name, ContactStore.all(this))"))
        assertTrue(service.contains("CallerId.asName(lookupName(verifiedNumber))"))
        assertTrue(service.contains("if (CallerId.asNumber(number).isEmpty()"))
    }

    @Test fun missedCallNotificationsAndThirdPartyAppsNeverShowRawPlaceholder() {
        val service = source("LinkService")
        assertTrue(service.contains("val who = CallerId.display(call.name, call.number,"))
        assertTrue(service.contains("val safeTitle = CallerId.display(title, title,"))
        assertTrue(service.contains(".setContentTitle(safeTitle)"))
        assertTrue(service.contains("sourceApp = call.app"))
    }

    @Test fun whatsappTrackerNormalizesNumberNameAndExplicitPrivateCaller() {
        val tracker = source("WhatsAppCallTracker")
        assertTrue(tracker.contains("CallerId.clean(title)"))
        assertTrue(tracker.contains("CallerId.isPrivate(raw)"))
        assertTrue(tracker.contains("CallerId.asName(raw)"))
        assertTrue(tracker.contains("numberFor(call.name)"))
    }

    @Test fun missingDataCannotEnableCallBackOrFavouriteActions() {
        val ui = source("CallsScreen")
        assertTrue(ui.contains("val canCall = ui.connected && number.isNotEmpty()"))
        assertTrue(ui.contains("enabled = canCall"))
        assertTrue(ui.contains("enabled = number.isNotEmpty()"))
        assertTrue(ui.contains("number = number,"))
    }
}
