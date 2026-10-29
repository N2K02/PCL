package com.pcl.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure, consistent permission-less display and action policy for Recents. */
internal object RecentCallActionsPolicy {
    data class Availability(
        val quickCall: Boolean,
        val simOne: Boolean,
        val simTwo: Boolean,
        val share: Boolean,
        val copy: Boolean,
        val contact: Boolean,
        val details: Boolean = true,
        val delete: Boolean = true,
    )

    fun availability(record: CallHistory.Record, connected: Boolean,
                     idle: Boolean, knownSimSlots: Set<Int> = emptySet()): Availability {
        val hasNumber = CallerId.asNumber(record.number).isNotEmpty()
        val dial = hasNumber && connected && idle
        // Selecting a SIM must be explicit and supported by an active
        // Gateway subscription; the Gateway checks the real phone account too.
        return Availability(
            quickCall = dial,
            simOne = dial && 0 in knownSimSlots && SimRecentsAnalytics.isSim(record),
            simTwo = dial && 1 in knownSimSlots && SimRecentsAnalytics.isSim(record),
            share = hasNumber,
            copy = hasNumber,
            contact = hasNumber,
        )
    }

    fun title(record: CallHistory.Record, lookedUpName: String?): String {
        val source = RecentCallPresentation.source(record.sourceApp, record.whatsapp, record.simIdentity)
        return CallerId.display(
            CallerId.asName(record.name).ifEmpty { CallerId.asName(lookedUpName) }
                .ifEmpty { record.name },
            record.number,
            CallerId.callSummary(record.type.name, source),
        )
    }

    /** Format explicitly requested by the user; 24-hour clock. */
    fun dateTime(at: Long, locale: Locale = Locale.US): String =
        SimpleDateFormat("MM/dd/yyyy HH:mm", locale).format(Date(at))

    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        return if (s >= 3600) "%d:%02d:%02d".format(Locale.US,
            s / 3600, s % 3600 / 60, s % 60)
        else "%02d:%02d".format(Locale.US, s / 60, s % 60)
    }

    fun share(record: CallHistory.Record, lookedUpName: String?): String? {
        val number = CallerId.asNumber(record.number).takeIf { it.isNotEmpty() } ?: return null
        val name = CallerId.asName(record.name).ifEmpty { CallerId.asName(lookedUpName) }
        return if (name.isNotEmpty()) "$name\n$number" else number
    }
}
