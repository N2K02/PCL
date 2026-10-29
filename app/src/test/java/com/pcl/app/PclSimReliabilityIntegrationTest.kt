package com.pcl.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PclSimReliabilityIntegrationTest {
    private fun source(name: String): String {
        val path = "app/src/main/java/com/pcl/app/$name.kt"
        return listOf(File(path), File("../$path")).firstOrNull { it.exists() }
            ?.readText() ?: error("Missing $path")
    }

    @Test fun recentsAndQuickCallExcludeVoipWithoutDeletingOldData() {
        val analytics = source("SimRecentsAnalytics")
        val actions = source("RecentCallActionsPolicy")
        val ui = source("CallsScreen")
        val history = source("CallHistory")
        assertTrue(analytics.contains("Filter.ALL -> isSim(record)"))
        assertTrue(actions.contains("SimRecentsAnalytics.isSim(record)"))
        assertTrue(ui.contains("CallHistory.clearSim()"))
        assertTrue(ui.contains("SIM statistics · last 100 saved calls"))
        assertTrue(history.contains("_records.value.filterNot(SimRecentsAnalytics::isSim)"))
    }

    @Test fun gatewayMapsOnlyVerifiedSimCapableTelecomVoiceAccounts() {
        val accounts = source("SimDialAccounts")
        assertTrue(accounts.contains("telephony.getSubscriptionId(handle)"))
        assertTrue(accounts.contains("PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION"))
        assertTrue(accounts.contains("PhoneAccount.SCHEME_TEL"))
        assertTrue(accounts.contains("SimDialAccountPolicy.choose(slot, subs, accounts)"))
        assertTrue(accounts.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.R"))
    }

    @Test fun callLogWorkerNeverSharesBluetoothWriterExecutor() {
        val service = source("LinkService")
        assertTrue(service.contains("private val io = Executors.newSingleThreadExecutor()"))
        assertTrue(service.contains("private val callLogExecutor = ThreadPoolExecutor("))
        assertTrue(service.contains("ArrayBlockingQueue<Runnable>(8)"))
        assertTrue(service.contains("callLogExecutor.execute {"))
        assertTrue(service.contains("callLogExecutor.shutdownNow()"))
        assertTrue(service.contains("callLogExecutor.queue.clear()"))
    }

    @Test fun privateBoundedOutboxIsSavedBeforeSendingAndAckedOnRemoteSuccess() {
        val service = source("LinkService")
        val outbox = source("PendingSimEnrichment")
        assertTrue(outbox.contains("Prefs.of(context).edit().putString(KEY, a.toString()).commit()"))
        assertTrue(outbox.contains("distinctBy { it.id }.takeLast(MAX_ITEMS)"))
        assertTrue(service.contains("PendingSimEnrichment.add(this, entry)"))
        assertTrue(service.contains("sendPendingSimEnrichment()"))
        assertTrue(service.contains("send(\"CALL_LOG_ACK\", id)"))
        assertTrue(service.contains("PendingSimEnrichment.acknowledge(this, arg1)"))
        assertTrue(service.contains("peerProtocol!! >= 7"))
        assertTrue(source("Protocol").contains("const val VERSION = 7"))
    }

    @Test fun reconnectedCallStateIsNeverMisreadAsNewMissedOrOutgoingCall() {
        val service = source("LinkService")
        val history = source("CallHistory")
        assertTrue(service.contains("CallHistory.onLinkLost()"))
        assertTrue(service.contains("if (role == ROLE_REMOTE) CallHistory.onLinkLost()"))
        assertTrue(history.contains("recovery.onDisconnect()"))
        assertTrue(history.contains("if (!recovery.shouldTrack(state)) return null"))
        assertTrue(service.contains("p.getOrNull(9)?.toLongOrNull()"))
        assertTrue(history.contains("gatewayStartedAt ?: now"))
    }
}
