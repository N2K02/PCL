package com.pcl.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contracts: PCL only supports SIM calls. */
class SimOnlyContractTest {
    private fun source(path: String): String {
        val file = listOf(File(path), File("../$path")).firstOrNull { it.isFile }
            ?: error("Missing source: $path")
        return file.readText()
    }

    @Test fun protocolAllowsOnlySimCallsAndCoreSync() {
        assertTrue("ANSWER" in Commands.GATEWAY_HANDLES)
        assertTrue("REJECT" in Commands.GATEWAY_HANDLES)
        assertTrue("HANGUP" in Commands.GATEWAY_HANDLES)
        assertTrue("DIAL" in Commands.GATEWAY_HANDLES)
        assertTrue("CALL" in Commands.REMOTE_HANDLES)
        assertTrue("CALL_RESULT" in Commands.REMOTE_HANDLES)
        assertTrue("CONTACTS" in Commands.REMOTE_HANDLES)
        assertTrue("AUDIO_STATUS" in Commands.REMOTE_HANDLES)
        val unsupported = listOf("WHATSAPP_CALL", "WHATSAPP_CHAT", "CALL_NOTIF", "NOTIF", "NOTIF_V2",
            "NOTIF_ACTION", "NOTIF_REMOVE", "WHATSAPP_CALL_STATE", "NOTIF_DISMISS")
        unsupported.forEach {
            assertFalse("Gateway must reject $it", it in Commands.GATEWAY_HANDLES)
            assertFalse("Remote must reject $it", it in Commands.REMOTE_HANDLES)
        }
    }

    @Test fun serviceHasNoThirdPartyCallOrNotificationMirror() {
        val service = source("app/src/main/java/com/pcl/app/LinkService.kt")
        listOf("WhatsApp", "WhatsAppDialer", "CALL_NOTIF", "mirrorCallPosted",
            "showMirroredCall", "NOTIF_ACTION", "NotifListener").forEach {
            assertFalse("Obsolete service dependency: $it", service.contains(it))
        }
        assertTrue(service.contains("gatewayAnswer()"))
        assertTrue(service.contains("gatewayEnd("))
        assertTrue(service.contains("onRemoteCallUpdate("))
    }

    @Test fun callHistoryRetainsSimMissedCalls() {
        val history = source("app/src/main/java/com/pcl/app/CallHistory.kt")
        assertTrue(history.contains("INCOMING, OUTGOING, MISSED, DECLINED"))
        assertTrue(history.contains("onSimState("))
        assertTrue(history.contains("unseenMissed"))
        assertFalse(history.contains("WHATSAPP"))
    }

    @Test fun manifestAndSettingsRequireNoNotificationListenerOrAccessibility() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        val activity = source("app/src/main/java/com/pcl/app/MainActivity.kt")
        assertFalse(manifest.contains("BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertFalse(manifest.contains("BIND_ACCESSIBILITY_SERVICE"))
        assertFalse(activity.contains("Send other notifications to remote"))
        assertFalse(activity.contains("WhatsApp"))
    }
}
