package com.pcl.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A small private, durable outbox for post-call metadata. Never keeps VoIP data. */
internal object PendingSimEnrichment {
    private const val KEY = "pending_sim_history_enrichment_v1"
    internal const val MAX_ITEMS = 12
    internal const val TTL_MS = 7L * 24L * 60L * 60L * 1000L

    data class Entry(
        val startedAt: Long,
        val direction: String,
        val number: String,
        val name: String,
        val privateCaller: Boolean,
        val simSlot: Int?,
        val simName: String,
        val carrier: String,
        val createdAt: Long,
    ) {
        val id: String get() = "${startedAt}_${direction}"
    }

    internal fun keep(entries: List<Entry>, now: Long): List<Entry> =
        entries.filter {
            it.startedAt > 0L && it.createdAt in (now - TTL_MS)..now &&
                it.direction in setOf("INCOMING", "OUTGOING") &&
                (it.simSlot == null || it.simSlot in 0..1)
        }.distinctBy { it.id }.takeLast(MAX_ITEMS)

    private fun decode(raw: String?): List<Entry> = runCatching {
        val arr = JSONArray(raw ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val j = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                j.optLong("at"), j.optString("flow"),
                CallerId.asNumber(j.optString("n")),
                CallerId.asName(j.optString("name")).ifEmpty {
                    if (j.optBoolean("private")) CallerId.PRIVATE else ""
                },
                j.optBoolean("private"),
                j.optInt("slot", -1).takeIf { it in 0..1 },
                CallerId.clean(j.optString("label")).take(48),
                CallerId.clean(j.optString("carrier")).take(48),
                j.optLong("created"),
            )
        }
    }.getOrDefault(emptyList())

    private fun store(context: Context, entries: List<Entry>) {
        val a = JSONArray()
        entries.forEach { e ->
            a.put(JSONObject()
                .put("at", e.startedAt).put("flow", e.direction)
                .put("n", e.number).put("name", e.name)
                .put("private", e.privateCaller)
                .put("slot", e.simSlot ?: JSONObject.NULL)
                .put("label", e.simName).put("carrier", e.carrier)
                .put("created", e.createdAt))
        }
        // Small bounded storage; commit before claiming an update is queued.
        Prefs.of(context).edit().putString(KEY, a.toString()).commit()
    }

    @Synchronized
    fun snapshot(context: Context, now: Long = System.currentTimeMillis()): List<Entry> {
        val existing = decode(Prefs.of(context).getString(KEY, null))
        val live = keep(existing, now)
        if (existing.size != live.size) store(context, live)
        return live
    }

    @Synchronized
    fun add(context: Context, entry: Entry) {
        val now = System.currentTimeMillis()
        val current = snapshot(context, now).filterNot { it.id == entry.id }
        store(context, keep(current + entry, now))
    }

    @Synchronized
    fun acknowledge(context: Context, id: String) {
        if (id.isBlank()) return
        val before = snapshot(context)
        val after = before.filterNot { it.id == id }
        if (after.size != before.size) store(context, after)
    }
}
