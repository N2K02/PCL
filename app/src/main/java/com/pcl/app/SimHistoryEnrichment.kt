package com.pcl.app

import kotlin.math.abs

/** Reconciles a completed SIM call without guessing identity or changing its outcome. */
internal object SimHistoryEnrichment {
    fun findUnique(
        records: List<CallHistory.Record>,
        started: Long,
        direction: String,
    ): Int? {
        if (started <= 0) return null
        val accepted = when (direction) {
            "OUTGOING" -> setOf(CallHistory.Type.OUTGOING)
            "INCOMING" -> setOf(
                CallHistory.Type.INCOMING, CallHistory.Type.MISSED,
                CallHistory.Type.DECLINED)
            else -> return null
        }
        return records.indices.filter { i ->
            val row = records[i]
            SimRecentsAnalytics.isSim(row) &&
                row.type in accepted && abs(row.at - started) <= 45_000L
        }.singleOrNull()
    }

    fun merge(
        old: CallHistory.Record, number: String?, name: String?,
        restricted: Boolean, sim: SimCallIdentity?,
    ): CallHistory.Record? {
        if (!SimRecentsAnalytics.isSim(old)) return null
        val existingNumber = CallerId.asNumber(old.number)
        val newNumber = CallerId.asNumber(number)
        if (existingNumber.isNotEmpty() && newNumber.isNotEmpty() &&
            !CallerId.sameNumber(existingNumber, newNumber)) return null
        if (old.simSlot != null && sim != null && old.simSlot != sim.slotIndex) return null
        val safeNumber = existingNumber.ifEmpty { newNumber }
        val safeName = CallerId.asName(old.name).ifEmpty {
            CallerId.asName(name)
        }.ifEmpty {
            if (safeNumber.isEmpty() && (restricted ||
                    CallerId.isPrivate(old.name) || CallerId.isPrivate(name)))
                CallerId.PRIVATE else ""
        }
        val updated = CallHistory.normalize(old.copy(
            number = safeNumber,
            name = safeName,
            simSlot = old.simSlot ?: sim?.slotIndex,
            simLabel = old.simLabel ?: sim?.displayName,
            carrier = old.carrier ?: sim?.carrier,
        ))
        return updated.takeIf { it != old }
    }
}
