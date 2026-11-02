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
            val subs = sm.activeSubscriptionInfoList.orEmpty().map {
                SimDialAccountPolicy.Subscription(it.subscriptionId, it.simSlotIndex)
            }
            if (subs.count { it.slot == slot && it.id >= 0 } != 1) {
                return Selection(null, Unavailable.NO_SUBSCRIPTION)
            }
            val handles = telecom.callCapablePhoneAccounts.orEmpty()
            val accounts = handles.map { handle ->
                val account = telecom.getPhoneAccount(handle)
                val simCapable = account?.hasCapabilities(
                    PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true
                val tel = account?.supportedUriSchemes?.contains(PhoneAccount.SCHEME_TEL) == true
                val subId = if (simCapable && tel) runCatching {
                    telephony.getSubscriptionId(handle)
                }.getOrNull() else null
                SimDialAccountPolicy.Account(subId, simCapable, tel)
            }
            val matching = accounts.indices.count { index ->
                val a = accounts[index]
                a.simCapable && a.supportsTel &&
                    subs.any { it.slot == slot && it.id == a.mappedSubscriptionId }
            }
            val selected = SimDialAccountPolicy.choose(slot, subs, accounts)
            when {
                selected != null -> Selection(handles[selected])
                matching > 1 -> Selection(null, Unavailable.AMBIGUOUS_ACCOUNT)
                else -> Selection(null, Unavailable.NO_VERIFIED_ACCOUNT)
            }
        }.getOrElse { Selection(null, Unavailable.NO_VERIFIED_ACCOUNT) }
    }

    fun accountForSlot(context: Context, slot: Int): PhoneAccountHandle? =
        select(context, slot).handle

    fun availableSlots(context: Context): Set<Int> =
        (0..1).filter { select(context, it).handle != null }.toSet()
}
