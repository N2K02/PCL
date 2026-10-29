package com.pcl.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.SubscriptionManager

/**
 * Opt-in post-call lookup, never used to start/stop calls. The platform may
 * refuse READ_CALL_LOG despite a permission request (installer restriction).
 * Queries are bounded; any missing/ambiguous data fails closed.
 */
internal object OptionalSimCallLog {
    data class Enrichment(
        val number: String,
        val name: String,
        val privateCaller: Boolean,
        val sim: SimCallIdentity?,
    )

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun read(
        context: Context, startedAt: Long, direction: String,
        knownNumber: String?, knownSim: SimCallIdentity?,
    ): Enrichment? {
        if (!canRead(context) || startedAt <= 0L ||
            direction !in setOf("INCOMING", "OUTGOING")) return null
        val entries = runCatching {
            val start = (startedAt - 45_000L).coerceAtLeast(0L)
            val end = startedAt + 45_000L
            val selection = "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?"
            val projection = arrayOf(
                CallLog.Calls.DATE, CallLog.Calls.TYPE,
                CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER_PRESENTATION, CallLog.Calls.PHONE_ACCOUNT_ID,
                CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
            )
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection,
                selection, arrayOf(start.toString(), end.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                val list = mutableListOf<SimCallLogMatch.Entry>()
                while (list.size < 41 && cursor.moveToNext()) {
                    list += SimCallLogMatch.Entry(
                        cursor.getLong(0), cursor.getInt(1),
                        cursor.getString(2), cursor.getString(3),
                        cursor.getInt(4), cursor.getString(5), cursor.getString(6),
                    )
                }
                // If >40 rows fall in this window, don't treat partial data as unique.
                if (list.size > 40) emptyList() else list
            }.orEmpty()
        }.getOrElse {
            Hub.log("Optional call history lookup unavailable; normal call detection unaffected")
            return null
        }
        val match = SimCallLogMatch.choose(
            startedAt, direction, knownNumber, entries) ?: return null
        val fields = SimCallLogMatch.details(match)
        val loggedSim = resolveAccount(context, fields.phoneAccountId)
        if (knownSim != null && loggedSim != null &&
            knownSim.slotIndex != loggedSim.slotIndex) return null
        return Enrichment(
            fields.number, fields.name, fields.privateCaller, loggedSim ?: knownSim)
    }

    @SuppressLint("MissingPermission")
    private fun resolveAccount(context: Context, account: String?): SimCallIdentity? {
        if (account.isNullOrBlank() ||
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
                PackageManager.PERMISSION_GRANTED) return null
        // PHONE_ACCOUNT_ID is not guaranteed to be the subscription ID on
        // every OEM. Accept only an exact numeric subscription ID match.
        val id = account.toIntOrNull() ?: return null
        return runCatching {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty()
                .filter { it.subscriptionId == id && it.simSlotIndex in 0..3 }
            subscriptions.singleOrNull()?.let { info ->
                SimCallIdentity(info.simSlotIndex,
                    CallerId.clean(info.displayName?.toString()).take(48),
                    CallerId.clean(info.carrierName?.toString()).take(48))
            }
        }.getOrNull()
    }
}
