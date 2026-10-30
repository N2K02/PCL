package com.pcl.app

import android.media.AudioDeviceInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests call-capable endpoints, avoiding media-only Bluetooth misrouting. */
class GatewayAudioRoutePolicyTest {
    @Test fun supportsSpeakerEarpieceAndBluetoothCallEndpoints() {
        val routes = GatewayAudioRoutePolicy.available(listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        ))
        assertEquals(setOf("SPEAKER", "EARPIECE", "BLUETOOTH"), routes)
        assertTrue(GatewayAudioRoutePolicy.valid("BLUETOOTH"))
        assertFalse(GatewayAudioRoutePolicy.valid("A2DP"))
    }

    @Test fun bleHeadsetsSupportCallsButMediaOnlyA2dpCannotBeSelected() {
        assertTrue(GatewayAudioRoutePolicy.matches("BLUETOOTH", AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(GatewayAudioRoutePolicy.matches("BLUETOOTH", AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertEquals(emptySet<String>(), GatewayAudioRoutePolicy.available(listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )))
    }

    @Test fun missingEarpieceDoesNotOfferAnUnrealizableRoute() {
        assertEquals(setOf("SPEAKER"),
            GatewayAudioRoutePolicy.available(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        assertFalse(GatewayAudioRoutePolicy.matches("EARPIECE", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    @Test fun remoteVolumeAndRouteCommandsReachGatewayAndReplyWithObservedStatus() {
        val link = source("LinkService")
        val calls = source("CallsScreen")
        val main = source("MainActivity")
        assertTrue(link.contains("sendAudioRequest(\"AUDIO_VOLUME\""))
        assertTrue(link.contains("sendAudioRequest(\"AUDIO_ROUTE\", route)"))
        assertTrue(link.contains("\"AUDIO_VOLUME\" -> gatewaySetAudioVolume("))
        assertTrue(link.contains("\"AUDIO_ROUTE\" -> gatewaySetAudioRoute("))
        assertTrue(link.contains("audio.setStreamVolume(stream, target, 0)"))
        assertTrue(link.contains("audio.getStreamVolume(stream)"))
        assertTrue(link.contains("audio.setCommunicationDevice(target)"))
        assertTrue(link.contains("verifyGatewayRouteLater(route, requestId)"))
        assertTrue(link.contains("send(\"AUDIO_RESULT\", \"ROUTE\""))
        assertTrue(link.contains("gatewayAvailableRoutes(audio).sorted().joinToString(\",\")"))
        assertTrue(link.contains("gatewayAvailableAudioRoutes = p.getOrNull(5)"))
        assertTrue(calls.contains("\"BLUETOOTH\" to \"Bluetooth\""))
        assertTrue(calls.contains("onValueChangeFinished = {"))
        assertTrue(calls.contains("actions.setGatewayVolume(target)"))
        assertFalse(calls.contains("onValueChange = { actions.setGatewayVolume(it.toInt()) }"))
        assertTrue(main.contains("gatewayAvailableAudioRoutes = service?.gatewayAvailableAudioRoutes.orEmpty()"))
    }

    @Test fun stopsOwnedRouteWhenCallEndsAndWhenLinkQuits() {
        val link = source("LinkService")
        val state = link.substringAfter("private fun applyGatewayPhoneState(")
            .substringBefore("private fun recoverRecentSimNumber(")
        val stop = link.substringAfter("private fun shutdown()")
            .substringBefore("private fun send(vararg parts: String)")
        assertTrue(state.contains("releaseGatewayAudioRoute()"))
        assertTrue(stop.contains("releaseGatewayAudioRoute()"))
        assertTrue(stop.contains("cancelRouteVerification()"))
        assertTrue(link.contains("if (actual == route) \"APPLIED\" else \"NOT_APPLIED\""))
    }


    @Test fun audioCommandsReportFailuresRatherThanSilentlyTimingOut() {
        val link = source("LinkService")
        val volume = link.substringAfter("private fun gatewaySetAudioVolume(")
            .substringBefore("private fun gatewaySetAudioRoute(")
        assertTrue(volume.contains("send(\"AUDIO_RESULT\", \"VOLUME\", \"INVALID\", requestId)"))
        val step = link.substringAfter("private fun gatewayAudioStep(")
            .substringBefore("private fun gatewayAvailableRoutes(")
        assertTrue(step.contains("send(\"AUDIO_RESULT\", \"STEP\", \"INVALID\", requestId)"))
        assertTrue(link.contains("ownsLegacyBluetoothSco = true"))
        assertTrue(link.contains("if (ownsLegacyBluetoothSco)"))
        assertTrue(link.contains("if (role == ROLE_GATEWAY) releaseGatewayAudioRoute()"))
        assertTrue(link.contains("pendingRouteVerification = null"))
    }


    @Test fun audioRequestsCarryLiveCallTokenAndRejectStaleGatewayCommands() {
        val service = source("LinkService")
        val outgoing = service.substringAfter("private fun sendAudioRequest(")
            .substringBefore("fun requestGatewayVolume(")
        assertTrue(outgoing.contains("activeCallToken == null"))
        assertTrue(outgoing.contains("callControlsRestricted"))
        assertTrue(outgoing.contains("send(command, value, id, token.toString())"))
        val incoming = service.substringAfter("private fun handleCommand(")
            .substringBefore("private fun gatewayTokenMatches(")
        assertTrue(incoming.contains("gatewayAudioCommandAllowed(p.getOrNull(3), \"MODE\""))
        assertTrue(incoming.contains("gatewayAudioCommandAllowed(p.getOrNull(3), \"VOLUME\""))
        assertTrue(incoming.contains("gatewayAudioCommandAllowed(p.getOrNull(3), \"STEP\""))
        assertTrue(incoming.contains("gatewayAudioCommandAllowed(p.getOrNull(3), \"ROUTE\""))
        assertTrue(service.contains("SimCallTokenPolicy.matches(simCallStartedMs, token)"))
        assertTrue(service.contains("send(\"AUDIO_RESULT\", kind, \"STALE_CALL\", requestId)"))
    }

    @Test fun notificationAudioControlsRequireSameConnectedCallAndGatewaySimState() {
        val link = source("LinkService")
        val notificationActions = link.substringAfter("ACTION_AUDIO_DOWN, ACTION_AUDIO_UP ->")
            .substringBefore("ACTION_CALL_BACK ->")
        assertTrue(notificationActions.contains("validNotificationCallAction(intent)"))
        assertTrue(link.contains("ACTION_AUDIO_DOWN, ACTION_AUDIO_UP, ACTION_AUDIO_SPEAKER ->"))
        assertTrue(link.contains("callState == CALL_OFFHOOK && currentGatewayPhoneState() == CALL_OFFHOOK"))
        assertFalse(link.contains("val active = callState == CALL_OFFHOOK ||"))
        assertTrue(link.contains("Gateway audio result:"))
        val screen = source("CallsScreen")
        assertTrue(screen.contains("ui.gatewayAudioError?.let"))
        val main = source("MainActivity")
        assertTrue(main.contains("gatewayAudioError = service?.gatewayAudioError"))
    }

    @Test fun incomingRemoteCallStateNeverBlocksOnContactDatabaseLookup() {
        val link = source("LinkService")
        val packet = link.substringAfter("\"CALL\" -> {")
            .substringBefore("\"CALL_LOGGED\" -> {")
        assertFalse(packet.contains("resolveUniqueContactNumber(callName)"))
        assertTrue(packet.contains("if (callState != CALL_RINGING || callControlsRestricted) stopSimRinging()"))
    }

    @Test fun experimentalWorkflowBuildsR8MinifiedReleaseBeforeMerge() {
        val path = ".github/workflows/build.yml"
        val ci = listOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(ci.contains("testDebugUnitTest assembleDebug assembleRelease"))
        assertTrue(ci.contains("testDebugUnitTest assembleRelease"))
        assertTrue(source("Protocol").contains("const val VERSION = 12"))
    }

    private fun source(name: String): String {
        val p = "app/src/main/java/com/pcl/app/$name.kt"
        return listOf(File(p), File("../$p")).first { it.isFile }.readText()
    }
}
