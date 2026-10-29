package com.pcl.app

import kotlin.math.abs

/** Match a completed cellular call with one call-log entry, never by proximity alone
 *  when several calls are plausible. Does not control phone state or alter history.
 */
internal object SimCallLogMatch {
    data class Entry(
        val startedAtMs: Long,
        val type: Int,
        val number: String?,
        val name: String?,
        val presentation: Int,
        val phoneAccountId: String?,
        val phoneAccountComponent: String?,
    )

    // Android CallLog.Calls type constants (also JVM-testable without Android runtime).
    const val INCOMING = 1
    const val OUTGOING = 2
    const val MISSED = 3
    const val REJECTED = 5
    const val PRESENTATION_RESTRICTED = 2

    fun choose(
        startedAtMs: Long,
        direction: String,
        existingNumber: String?,
        rows: List<Entry>,
    ): Entry? {
        if (startedAtMs <= 0) return null
        val types = when (direction) {
            "INCOMING" -> setOf(INCOMING, MISSED, REJECTED)
            "OUTGOING" -> setOf(OUTGOING)
            else -> return null
        }
        val known = CallerId.asNumber(existingNumber)
        val matching = rows.filter { row ->
            val drift = abs(row.startedAtMs - startedAtMs)
            val observed = CallerId.asNumber(row.number)
            val compatible = known.isEmpty() ||
                (observed.isNotEmpty() && CallerId.sameNumber(known, observed))
            val thirdParty = row.phoneAccountComponent?.let {
                it.contains("whatsapp", true) || it.contains("signal", true) ||
                    it.contains("telegram", true) || it.contains("messenger", true)
            } ?: false
            row.type in types && !thirdParty && drift <=
                if (known.isNotEmpty()) 45_000L else 8_000L && compatible
        }
        return matching.singleOrNull()
    }

    data class Details(val number: String, val name: String, val privateCaller: Boolean,
                       val phoneAccountId: String?)

    fun details(entry: Entry): Details {
        val restricted = entry.presentation == PRESENTATION_RESTRICTED
        val number = if (restricted) "" else CallerId.asNumber(entry.number)
        val name = if (restricted && number.isEmpty()) CallerId.PRIVATE
            else CallerId.asName(entry.name)
        return Details(number, name, restricted && number.isEmpty(),
            entry.phoneAccountId)
    }
}
