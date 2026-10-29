package com.pcl.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * Only select a voice SIM when Android itself establishes the subscription
 * associated with a call-capable, enabled, built-in SIM PhoneAccount.
 *
 * Android 10 and older don't expose TelephonyManager.getSubscriptionId(handle).
 * We intentionally decline directed SIM dialing on those devices rather than
 * assume the numeric account ID or the default mobile-data SIM is correct.
 */
internal object SimDialAccounts {
    enum class Unavailable {
        NO_PERMISSION, UNSUPPORTED_ANDROID, NO_SUBSCRIPTION, NO_VERIFIED_ACCOUNT, AMBIGUOUS_ACCOUNT,
    }
    data class Selection(val handle: PhoneAccountHandle?, val reason: Unavailable? = null)

    @SuppressLint("MissingPermission")
    fun select(context: Context, slot: Int): Selection {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) return Selection(null, Unavailable.NO_PERMISSION)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Selection(null, Unavailable.UNSUPPORTED_ANDROID)
        }
        if (slot !in 0..1) return Selection(null, Unavailable.NO_SUBSCRIPTION)
        return runCatching {
            val sm = context.getSystemService(SubscriptionManager::class.java)
                ?: return Selection(null, Unavailable.NO_SUBSCRIPTION)
            val telecom = context.getSystemService(TelecomManager::class.java)
                ?: return Selection(null, Unavailable.NO_VERIFIED_ACCOUNT)
            val telephony = context.getSystemService(TelephonyManager::class.java)
                ?: return Selection(null, Unavailable.NO_VERIFIED_ACCOUNT)
            val ids = sm.activeSubscriptionInfoList.orEmpty()
                .filter { it.simSlotIndex == slot && it.subscriptionId >= 0 }
                .map { it.subscriptionId }.distinct()
            if (ids.size != 1) return Selection(null, Unavailable.NO_SUBSCRIPTION)
            val subscription = ids.single()
            val candidates = telecom.callCapablePhoneAccounts.orEmpty().filter { handle ->
                val account = telecom.getPhoneAccount(handle) ?: return@filter false
                if (!account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) ||
                    !account.supportedUriSchemes.contains(PhoneAccount.SCHEME_TEL)) {
                    return@filter false
                }
                telephony.getSubscriptionId(handle) == subscription
            }
            when (candidates.size) {
                1 -> Selection(candidates.single())
                0 -> Selection(null, Unavailable.NO_VERIFIED_ACCOUNT)
                else -> Selection(null, Unavailable.AMBIGUOUS_ACCOUNT)
            }
        }.getOrElse { Selection(null, Unavailable.NO_VERIFIED_ACCOUNT) }
    }

    fun accountForSlot(context: Context, slot: Int): PhoneAccountHandle? =
        select(context, slot).handle

    fun availableSlots(context: Context): Set<Int> =
        (0..1).filter { select(context, it).handle != null }.toSet()
}
