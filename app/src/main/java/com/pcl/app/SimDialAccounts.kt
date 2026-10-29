package com.pcl.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager

/** Read-only resolution of a voice account; never silently fall back to default SIM. */
internal object SimDialAccounts {
    @SuppressLint("MissingPermission")
    fun accountForSlot(context: Context, requestedSlot: Int): PhoneAccountHandle? {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED || requestedSlot !in 0..3) return null
        return runCatching {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
            val tm = context.getSystemService(TelecomManager::class.java) ?: return null
            val subscriptions = sm.activeSubscriptionInfoList.orEmpty()
            val handles = tm.callCapablePhoneAccounts.orEmpty()
            val candidates = subscriptions.flatMap { sub ->
                handles.map { handle ->
                    Triple(sub, handle, SimDialAccountPolicy.Candidate(
                        sub.subscriptionId, sub.simSlotIndex, handle.id))
                }
            }
            val matches = candidates.filter { (_, _, candidate) ->
                candidate.slot == requestedSlot &&
                    candidate.accountId == candidate.subscriptionId.toString() &&
                    candidate.subscriptionId >= 0
            }
            if (matches.size != 1) return null
            val chosen = matches.single()
            val safe = SimDialAccountPolicy.choose(requestedSlot,
                matches.map { it.third }) ?: return null
            chosen.second.takeIf { it.id == safe }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    fun availableSlots(context: Context): Set<Int> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) return emptySet()
        return (0..1).filter { accountForSlot(context, it) != null }.toSet()
    }
}
