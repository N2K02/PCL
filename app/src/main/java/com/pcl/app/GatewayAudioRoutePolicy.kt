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

    fun available(types: List<Int>): Set<String> =
        routeTypes.keys.filterTo(linkedSetOf()) { route ->
            types.any { matches(route, it) }
        }
}
