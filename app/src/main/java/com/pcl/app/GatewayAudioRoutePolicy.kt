package com.pcl.app

import android.media.AudioDeviceInfo

/** Distinguish call-audio endpoints from media-only Bluetooth A2DP outputs. */
internal object GatewayAudioRoutePolicy {
    private val bluetoothCallTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )
    private val routeTypes = mapOf(
        "EARPIECE" to setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
        "SPEAKER" to setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        "BLUETOOTH" to bluetoothCallTypes,
    )

    fun valid(route: String): Boolean = route in routeTypes

    fun matches(route: String, type: Int): Boolean =
        routeTypes[route]?.contains(type) == true

    /**
     * Cycle only through actual Gateway call-audio endpoints from the Remote
     * notification. This never routes voice audio through the Remote phone.
     */
    fun next(current: String, available: Set<String>): String? {
        val choices = listOf("EARPIECE", "SPEAKER", "BLUETOOTH")
            .filter { it in available }
        if (choices.size < 2) return null
        val index = choices.indexOf(current)
        return choices[(if (index < 0) 0 else index + 1) % choices.size]
    }

    fun available(types: List<Int>): Set<String> =
        routeTypes.keys.filterTo(linkedSetOf()) { route ->
            types.any { matches(route, it) }
        }
}
