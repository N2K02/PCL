package com.pcl.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * Verified SIM association for a cellular call. Slot numbers are stored
 * zero-based and displayed to people as SIM 1, SIM 2, etc. A missing slot
 * means it is NOT safe to guess which line handled the call.
 */
data class SimCallIdentity(
    val slotIndex: Int,
    val displayName: String = "",
    val carrier: String = "",
) {
    fun badge(): String = "SIM ${slotIndex + 1}"
    fun description(): String {
        val descriptive = CallerId.clean(displayName).takeIf {
            it.isNotBlank() && !CallerId.isPlaceholder(it) &&
                !it.equals(badge(), ignoreCase = true)
        } ?: CallerId.clean(carrier).takeIf {
            it.isNotBlank() && !CallerId.isPlaceholder(it)
        }
        return if (descriptive == null) badge() else "${badge()} · $descriptive"
    }
}

/**
 * Distinguish a single active SIM from several plausible slots.
 * When per-subscription state is supported, refuse concurrent-call ambiguity.
 * Never infer a voice-call SIM from the default mobile data subscription.
 */
internal object SimCallSelection {
    data class Candidate(
        val slot: Int,
        val name: String = "",
        val carrier: String = "",
        val callState: Int? = null,
    )

    fun choose(candidates: List<Candidate>, expectedState: Int, perSimSupported: Boolean): SimCallIdentity? {
        // A subscription with no trustworthy slot may be the active line.
        // Never silently discard it and label another SIM by elimination.
        if (candidates.any { it.slot !in 0..3 }) return null
        val valid = candidates.distinctBy { it.slot }
        if (valid.size != candidates.size) return null
        val matched = if (perSimSupported) {
            val busy = valid.filter { it.callState != null &&
                it.callState != TelephonyManager.CALL_STATE_IDLE }
            // Even if only one SIM is ringing, a second call may be in progress.
            if (busy.size != 1) return null
            busy.filter { it.callState == expectedState }
        } else if (valid.size == 1) valid else emptyList()
        val selected = matched.singleOrNull() ?: return null
        return SimCallIdentity(selected.slot, selected.name, selected.carrier)
    }
}

internal object SimCallDetector {

    /** Best-effort concurrent-SIM detection; never infer the target of a Telecom action. */
    @SuppressLint("MissingPermission")
    fun hasMultipleBusySubscriptions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return false
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
            val states = sm.activeSubscriptionInfoList.orEmpty().map { sub ->
                runCatching { tm.createForSubscriptionId(sub.subscriptionId).callStateForSubscription }
                    .getOrNull()
            }
            states.count { it != null && it != TelephonyManager.CALL_STATE_IDLE } > 1
        }.getOrDefault(false)
    }
    @SuppressLint("MissingPermission")
    fun identify(context: Context, callState: String): SimCallIdentity? {
        if (callState != LinkService.CALL_RINGING && callState != LinkService.CALL_OFFHOOK) return null
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) return null
        val expected = if (callState == LinkService.CALL_RINGING)
            TelephonyManager.CALL_STATE_RINGING else TelephonyManager.CALL_STATE_OFFHOOK
        return runCatching {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
            val subscriptions = sm.activeSubscriptionInfoList.orEmpty()
            val perSim = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val entries = subscriptions.map { subscription ->
                val state = if (perSim) runCatching {
                    tm.createForSubscriptionId(subscription.subscriptionId).callStateForSubscription
                }.getOrNull() else null
                SimCallSelection.Candidate(
                    subscription.simSlotIndex,
                    CallerId.clean(subscription.displayName?.toString()).take(48),
                    CallerId.clean(subscription.carrierName?.toString()).take(48),
                    state,
                )
            }
            // Do not substitute a guessed SIM if per-subscription reads fail.
            SimCallSelection.choose(entries, expected, perSim)
        }.getOrNull()
    }
}
