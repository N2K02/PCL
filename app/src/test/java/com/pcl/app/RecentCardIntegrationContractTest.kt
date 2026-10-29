package com.pcl.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentCardIntegrationContractTest {
    private fun source(name: String) = listOf(
        File("app/src/main/java/com/pcl/app/$name.kt"),
        File("../app/src/main/java/com/pcl/app/$name.kt")
    ).firstOrNull { it.isFile }?.readText() ?: error("Missing $name")

    @Test fun recentsUsesModernCardInsteadOfExpandableContactRow() {
        val calls = source("CallsScreen")
        val card = source("RecentCallCard")
        assertTrue(calls.contains("RecentCallCard("))
        assertTrue(card.contains("Card("))
        assertTrue(card.contains("RoundedCornerShape(23.dp)"))
        assertTrue(card.contains("RecentCallBadges(record)"))
        assertTrue(card.contains("RecentCallActionsPolicy.dateTime(record.at)"))
        assertTrue(card.contains("RecentCallActionsPolicy.duration(record.durationSec)"))
        assertTrue(card.contains("Icons.Default.AccessTime"))
        assertTrue(card.contains("Icons.Default.MoreVert"))
    }

    @Test fun sheetHasAllRequestedSafeActionsAndIndividualDeleteConfirmation() {
        val calls = source("CallsScreen")
        val card = source("RecentCallCard")
        for (label in listOf("Make a quick call", "Call with SIM 1", "Call with SIM 2",
                "Share contact", "Copy number", "View details",
                "Add / Edit contact", "Delete from list")) {
            assertTrue(label, card.contains("\"$label\""))
        }
        assertTrue(card.contains("ModalBottomSheet("))
        assertTrue(card.contains("flags.quickCall"))
        assertTrue(card.contains("flags.simOne"))
        assertTrue(card.contains("flags.simTwo"))
        assertTrue(card.contains("flags.contact"))
        assertTrue(calls.contains("onDelete = { CallHistory.remove(it) }"))
    }

    @Test fun privateNumberAndNoNumberNeverEnableSensitiveActions() {
        val policy = source("RecentCallActionsPolicy")
        assertTrue(policy.contains("hasNumber = CallerId.asNumber(record.number).isNotEmpty()"))
        assertTrue(policy.contains("share = hasNumber"))
        assertTrue(policy.contains("copy = hasNumber"))
        assertTrue(policy.contains("contact = hasNumber"))
        assertTrue(policy.contains("quickCall = dial"))
        assertTrue(policy.contains("details: Boolean = true"))
        assertTrue(policy.contains("delete: Boolean = true"))
    }

    @Test fun remoteAndGatewayUseExplicitSimAccountForSelectedCallsOnly() {
        val link = source("LinkService")
        val protocol = source("Protocol")
        assertTrue(protocol.contains("\"DIAL_SIM\""))
        assertTrue(link.contains("fun dialWithSim(number: String, name: String, slot: Int)"))
        assertTrue(link.contains("SimDialAccounts.accountForSlot(this, selectedSlot)"))
        assertTrue(link.contains("extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)"))
        assertTrue(link.contains("if (account == null)"))
        assertTrue(link.contains("callControlError = \"FAILED: Android cannot securely select SIM"))
        assertTrue(link.contains("val slot = p.getOrNull(2)?.toIntOrNull()?.takeIf"))
    }

    @Test fun externalActionsAreIntentBasedAndDoNotExposePrivateNumber() {
        val card = source("RecentCallCard")
        assertTrue(card.contains("Intent(Intent.ACTION_SEND).setType(\"text/plain\")"))
        assertTrue(card.contains("ClipData.newPlainText(\"Phone number\", number)"))
        assertTrue(card.contains("Intent(Intent.ACTION_INSERT_OR_EDIT)"))
        assertTrue(card.contains("ContactsContract.Intents.Insert.PHONE"))
        assertTrue(card.contains("flags.share"))
        assertTrue(card.contains("flags.copy"))
        assertTrue(card.contains("flags.contact"))
    }

    @Test fun noVideoBadgesAndUnrelatedContactsMenuRemainsPresent() {
        val card = source("RecentCallCard")
        assertFalse(card.contains("Videocam"))
        assertTrue(card.contains("Call details"))
        val calls = source("CallsScreen")
        assertTrue(calls.contains("private fun PersonRow("))
    }
}
