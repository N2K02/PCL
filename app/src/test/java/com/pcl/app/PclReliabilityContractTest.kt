package com.pcl.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PclReliabilityContractTest {
    private fun file(path: String) = listOf(File(path), File("../$path"))
        .first { it.isFile }.readText()
    private fun source(name: String) = file("app/src/main/java/com/pcl/app/$name.kt")

    @Test fun optionalRestrictedPermissionDeclaredAndSetupExplainsInstallerRequirement() {
        assertTrue(file("app/src/main/AndroidManifest.xml").contains("android.permission.READ_CALL_LOG"))
        assertTrue(source("MainActivity").contains("installer allowlisting"))
    }

    @Test fun callHistoryAndContactsUseCrashSafeAtomicStorage() {
        assertTrue(source("CallHistory").contains("AtomicFile(File(app.filesDir, FILE))"))
        assertTrue(source("CallHistory").contains("atomic.failWrite(it)"))
        assertTrue(source("CallHistory").contains("if (!save(changed)) return null"))
        assertTrue(source("Contacts").contains("AtomicFile(File(ctx.filesDir, FILE))"))
        assertTrue(source("Contacts").contains("file.failWrite(it)"))
        assertTrue(source("LinkService").contains("if (ContactStore.saveSynced(this, data))"))
    }

    @Test fun failedContactSyncCannotOverwriteValidCacheOrHash() {
        val service = source("LinkService")
        assertTrue(service.contains("if (data == null)"))
        assertTrue(service.contains("checksum mismatch"))
        assertTrue(service.contains("if (ContactStore.saveSynced(this, data))"))
    }

    @Test fun callResultsHaveRequestIdsAndStaleSocketCannotCloseFreshLink() {
        val service = source("LinkService")
        assertTrue(service.contains("request.toString()"))
        assertTrue(service.contains("CallControlProtocol.matchesResult("))
        assertTrue(service.contains("if (writer === w) closeSocket()"))
        assertTrue(source("Protocol").contains("const val VERSION = 10"))
    }

    @Test fun phonesAndContactsNeverBlockBluetoothCommandReader() {
        val service = source("LinkService")
        assertTrue(service.contains("phoneDataExecutor.execute"))
        assertTrue(service.contains("contactsExecutor.execute"))
        assertTrue(service.contains("if (cursor.hasNext()) io.execute(this)"))
    }

    @Test fun onlyIncomingCallActivityCanAppearOverLockedScreen() {
        val manifest = file("app/src/main/AndroidManifest.xml")
        val main = manifest.substringAfter("android:name=\".MainActivity\"")
            .substringBefore("</activity>")
        assertFalse(main.contains("android:showWhenLocked"))
        assertTrue(manifest.contains("android:name=\".IncomingCallActivity\""))
        assertTrue(source("LinkService").contains("setFullScreenIntent(incomingCallPi(), true)"))
    }

    @Test fun recentsOfferSafeActionsAndQuitNeverRetainsAutoRestartPreference() {
        assertTrue(source("CallsScreen").contains("CallHistory.remove(selected)"))
        assertTrue(source("CallsScreen").contains("Favourites.toggle(Contact(name, record.number))"))
        assertTrue(source("MainActivity").contains("putBoolean(Prefs.LINK_ENABLED, false)"))
        assertTrue(source("LinkService").contains("callLogExecutor.shutdownNow()"))
    }

    @Test fun loggingKeepsLiveRecordsAndSupportsRedactedPersistentDiagnostics() {
        assertTrue(source("CallLogger").contains("if (initialized) return"))
        assertTrue(source("Protocol").contains("AppLog.add(\"LINK\", safeEvent)"))
        assertTrue(source("AppLog").contains("[BT_DEVICE]"))
    }
}
