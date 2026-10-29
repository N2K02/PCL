package com.pcl.app

/** Presentation-only filters and all-time statistics. VoIP is never counted as SIM. */
internal object SimRecentsAnalytics {
    enum class Filter(val title: String) {
        ALL("All SIM"), SIM_1("SIM 1"), SIM_2("SIM 2"), UNIDENTIFIED("Other SIM")
    }

    data class Stats(
        val calls: Int,
        val incoming: Int,
        val outgoing: Int,
        val missed: Int,
        val declined: Int,
        val talkSeconds: Long,
    )

    fun isSim(record: CallHistory.Record): Boolean =
        !record.whatsapp && record.sourceApp.isNullOrBlank() &&
            record.type != CallHistory.Type.WHATSAPP

    fun matches(record: CallHistory.Record, filter: Filter): Boolean = when (filter) {
        Filter.ALL -> isSim(record)
        Filter.SIM_1 -> isSim(record) && record.simSlot == 0
        Filter.SIM_2 -> isSim(record) && record.simSlot == 1
        Filter.UNIDENTIFIED -> isSim(record) && record.simSlot !in setOf(0, 1)
    }

    fun filter(records: List<CallHistory.Record>, choice: Filter): List<CallHistory.Record> =
        records.filter { matches(it, choice) }

    fun stats(records: List<CallHistory.Record>, slot: Int?): Stats {
        val simCalls = records.filter { isSim(it) &&
            (if (slot == null) it.simSlot !in setOf(0, 1) else it.simSlot == slot) }
        return Stats(
            calls = simCalls.size,
            incoming = simCalls.count { it.type == CallHistory.Type.INCOMING },
            outgoing = simCalls.count { it.type == CallHistory.Type.OUTGOING },
            missed = simCalls.count { it.type == CallHistory.Type.MISSED },
            declined = simCalls.count { it.type == CallHistory.Type.DECLINED },
            talkSeconds = simCalls.sumOf { it.durationSec.coerceAtLeast(0) },
        )
    }
}
