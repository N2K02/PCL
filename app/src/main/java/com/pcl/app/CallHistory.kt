package com.pcl.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Remote: the calls made and received through the Gateway, for the Calls tab.
 * Kept only on this phone (a private file), newest first, at most [MAX] entries.
 *
 * SIM calls are worked out from the Gateway's call states (ringing, in call, idle):
 * ringing then idle is missed (or declined, when declined from this phone), ringing then
 * in call is incoming, and in call without ringing is outgoing. WhatsApp calls on the
 * Gateway are worked out the same way by [WhatsAppCallTracker].
 */
object CallHistory {
    /** [WHATSAPP] is only in lists saved by PCL 2.8 and older: a WhatsApp call asked for from the Remote. */
    enum class Type { INCOMING, OUTGOING, MISSED, DECLINED, WHATSAPP }

    data class Record(
        val number: String,
        val name: String,
        val type: Type,
        /** When the call started, in epoch milliseconds. */
        val at: Long,
        /** Talk time in seconds; 0 for calls that weren't answered. */
        val durationSec: Long = 0,
        /** A WhatsApp call on the Gateway rather than a SIM call. */
        val whatsapp: Boolean = false,
        /** Original VoIP app for non-WhatsApp third-party call history. */
        val sourceApp: String? = null,
        /** Verified SIM slot (zero-based), null if Android could not decide. */
        val simSlot: Int? = null,
        val simLabel: String? = null,
        val carrier: String? = null,
    ) {
        val simIdentity: SimCallIdentity? get() = simSlot?.takeIf { it >= 0 }?.let {
            SimCallIdentity(it, simLabel.orEmpty(), carrier.orEmpty())
        }
    }

    private const val FILE = "call_history.json"
    private const val MAX = 100
    private const val PREF_UNSEEN_MISSED = "unseen_missed_calls"

    private val _records = MutableStateFlow<List<Record>>(emptyList())
    val records: StateFlow<List<Record>> = _records

    /** Missed calls not yet seen in the Calls tab (the badge on its icon). */
    private val _unseenMissed = MutableStateFlow(0)
    val unseenMissed: StateFlow<Int> = _unseenMissed

    /** While a SIM call is connected: when it connected (elapsedRealtime), else 0. For the call timer. */
    @Volatile var connectedSince = 0L
        private set

    private var loaded = false
    private lateinit var app: Context

    // Tracker for the Gateway's SIM call states.
    private var lastState = LinkService.CALL_IDLE
    /** Snapshot after reconnect is not a new SIM call. */
    private val recovery = SimCallRecoveryGuard()
    private var ringingNumber = ""
    private var ringingName = ""
    private var ringingSim: SimCallIdentity? = null
    private var startedAt = 0L
    private var answered = false
    private var declinedHere = false
    private var lastDialNumber = ""
    private var lastDialName = ""
    private var lastDialAt = 0L

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        app = context.applicationContext
        loaded = true
        _records.value = try {
            val f = File(app.filesDir, FILE)
            if (!f.exists()) emptyList() else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    normalize(Record(
                        number = o.optString("n"),
                        name = o.optString("m"),
                        type = runCatching { Type.valueOf(o.optString("t")) }.getOrNull() ?: return@mapNotNull null,
                        at = o.optLong("a"),
                        durationSec = o.optLong("d"),
                        whatsapp = o.optBoolean("w"),
                        sourceApp = o.optString("s").takeIf { it.isNotBlank() && it != "null" },
                        simSlot = o.optInt("ss", -1).takeIf { it >= 0 },
                        simLabel = o.optString("sl").takeUnless { it.isBlank() || it == "null" },
                        carrier = o.optString("sc").takeUnless { it.isBlank() || it == "null" },
                    ))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        _unseenMissed.value = Prefs.of(app).getInt(PREF_UNSEEN_MISSED, 0)
    }

    /** Legacy data may contain "-", "Unknown number" or call-app placeholders.
     *  Canonicalize on both load and write so they never leak back into Recents. */
    internal fun normalize(record: Record): Record {
        val number = CallerId.asNumber(record.number).ifEmpty {
            CallerId.asNumber(record.name)
        }
        val name = CallerId.asName(record.name).ifEmpty {
            if (number.isEmpty() && CallerId.isPrivate(record.name)) CallerId.PRIVATE
            else ""
        }
        val validSim = !record.whatsapp && record.sourceApp.isNullOrBlank() &&
            record.simSlot != null && record.simSlot in 0..3
        fun label(value: String?): String? = CallerId.clean(value).takeIf {
            it.isNotBlank() && !CallerId.isPlaceholder(it)
        }?.take(48)
        return record.copy(number = number, name = name,
            sourceApp = CallerId.clean(record.sourceApp).takeIf {
                it.isNotBlank() && it.lowercase() !in setOf(
                    "unknown", "unknown number", "unknown caller", "-", "n/a", "null")
            },
            simSlot = record.simSlot.takeIf { validSim },
            simLabel = label(record.simLabel).takeIf { validSim },
            carrier = label(record.carrier).takeIf { validSim },
        )
    }

    @Synchronized
    fun add(record: Record) {
        if (!loaded) return
        val canonical = normalize(record)
        val list = (listOf(canonical) + _records.value).take(MAX)
        _records.value = list
        save(list)
        if (canonical.type == Type.MISSED) setUnseen(_unseenMissed.value + 1)
    }

    private fun save(list: List<Record>) {
        try {
            val arr = JSONArray()
            list.forEach { r ->
                arr.put(JSONObject().put("n", r.number).put("m", r.name).put("t", r.type.name)
                    .put("a", r.at).put("d", r.durationSec).put("w", r.whatsapp)
                    .put("s", r.sourceApp ?: JSONObject.NULL)
                    .put("ss", r.simSlot ?: JSONObject.NULL)
                    .put("sl", r.simLabel ?: JSONObject.NULL)
                    .put("sc", r.carrier ?: JSONObject.NULL))
            }
            File(app.filesDir, FILE).writeText(arr.toString())
        } catch (e: Exception) {
            Hub.log("Could not save call history: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun setUnseen(count: Int) {
        _unseenMissed.value = count
        if (loaded) Prefs.of(app).edit().putInt(PREF_UNSEEN_MISSED, count).apply()
    }

    fun markMissedSeen() {
        if (_unseenMissed.value != 0) setUnseen(0)
    }

    @Synchronized
    fun clear() {
        if (!loaded) return
        _records.value = emptyList()
        save(emptyList())
        setUnseen(0)
    }

    /** Clear saved SIM records only; preserve legacy VoIP history and contacts. */
    @Synchronized
    fun clearSim() {
        if (!loaded) return
        val retained = _records.value.filterNot(SimRecentsAnalytics::isSim)
        _records.value = retained
        save(retained)
        setUnseen(retained.count { it.type == Type.MISSED })
    }

    /**
     * Delete only one selected local history item. Never affect Android's
     * device call log or synced Gateway contacts.
     */
    internal fun withoutOne(list: List<Record>, selected: Record): List<Record>? {
        val byReference = list.indexOfFirst { it === selected }
        val index = if (byReference >= 0) byReference else {
            val matches = list.indices.filter { list[it] == selected }
            matches.singleOrNull() ?: return null
        }
        return list.toMutableList().apply { removeAt(index) }
    }

    @Synchronized
    fun remove(record: Record): Boolean {
        if (!loaded) return false
        val updated = withoutOne(_records.value, record) ?: return false
        _records.value = updated
        save(updated)
        return true
    }

    /** Update only the unique recently-ended SIM call. Never touch VoIP or
     *  assume an arbitrary row in Recents is the matching telephone call.
     */
    @Synchronized
    fun amendSim(
        originalStartMs: Long,
        number: String?,
        name: String?,
        restricted: Boolean,
        sim: SimCallIdentity?,
        direction: String,
    ): Record? {
        if (!loaded || originalStartMs <= 0L) return null
        val records = _records.value
        val index = SimHistoryEnrichment.findUnique(records, originalStartMs,
            direction) ?: return null
        val updated = SimHistoryEnrichment.merge(
            records[index], number, name, restricted, sim) ?: return null
        val changed = records.toMutableList().also { it[index] = updated }
        _records.value = changed
        save(changed)
        return updated
    }

    /** The Remote asked the Gateway to dial: remembered because Android may not report the number. */
    @Synchronized
    fun onDialRequested(number: String, name: String) {
        lastDialNumber = CallerId.asNumber(number)
        lastDialName = CallerId.asName(name)
        lastDialAt = System.currentTimeMillis()
    }

    /** The Remote declined a ringing call, so its end is "declined", not "missed". */
    @Synchronized
    fun onDeclinedHere() {
        if (lastState == LinkService.CALL_RINGING) declinedHere = true
    }

    /** An interrupted call lacks complete timing and cannot be finalized. */
    @Synchronized
    fun onLinkLost() {
        recovery.onDisconnect()
        lastState = LinkService.CALL_IDLE
        connectedSince = 0L
        startedAt = 0L
        ringingNumber = ""
        ringingName = ""
        ringingSim = null
        answered = false
        declinedHere = false
        lastDialAt = 0L
        lastDialNumber = ""
        lastDialName = ""
    }

    /**
     * Remote: the Gateway reported its SIM call state. Repeats of the same state are ignored.
     * Returns the call that just ended, if any.
     */
    @Synchronized
    fun onSimState(
        state: String, number: String, name: String,
        sim: SimCallIdentity? = null,
    ): Record? {
        if (!recovery.shouldTrack(state)) return null
        val verifiedNumber = CallerId.asNumber(number)
        val verifiedName = CallerId.asName(name).ifEmpty {
            if (verifiedNumber.isEmpty() && CallerId.isPrivate(name)) CallerId.PRIVATE
            else ""
        }
        return onVerifiedSimState(state, verifiedNumber, verifiedName, sim)
    }

    @Synchronized
    private fun onVerifiedSimState(
        state: String, number: String, name: String,
        sim: SimCallIdentity?,
    ): Record? {
        val previous = lastState
        if (state == previous) {
            // Ringing may be reported again once the caller's name is known.
            if (state == LinkService.CALL_RINGING || state == LinkService.CALL_OFFHOOK) {
                if (number.isNotEmpty()) ringingNumber = number
                if (name.isNotEmpty()) ringingName = name
                if (ringingSim == null && sim != null) ringingSim = sim
            }
            return null
        }
        lastState = state
        val now = System.currentTimeMillis()
        when (state) {
            LinkService.CALL_RINGING -> {
                ringingNumber = number
                ringingName = name
                ringingSim = sim
                startedAt = now
                answered = false
                declinedHere = false
                connectedSince = 0L
            }
            LinkService.CALL_OFFHOOK -> {
                connectedSince = SystemClock.elapsedRealtime()
                if (previous == LinkService.CALL_RINGING) {
                    if (number.isNotEmpty()) ringingNumber = number
                    if (name.isNotEmpty()) ringingName = name
                    if (ringingSim == null) ringingSim = sim
                    answered = true
                } else {
                    // Outgoing: Android may not report the number, so use the last dial request.
                    val recentDial = now - lastDialAt < 90_000L
                    ringingNumber = number.ifEmpty { if (recentDial) lastDialNumber else "" }
                    ringingName = name.ifEmpty { if (recentDial) lastDialName else "" }
                    startedAt = now
                    ringingSim = sim
                    answered = false
                }
            }
            else -> {
                val talk = if (connectedSince > 0) (SystemClock.elapsedRealtime() - connectedSince) / 1000 else 0
                val source = ringingSim ?: sim
                val record = when {
                    previous == LinkService.CALL_RINGING ->
                        Record(ringingNumber, ringingName, if (declinedHere) Type.DECLINED else Type.MISSED, startedAt)
                    previous == LinkService.CALL_OFFHOOK && answered ->
                        Record(ringingNumber, ringingName, Type.INCOMING, startedAt, talk)
                    previous == LinkService.CALL_OFFHOOK ->
                        Record(ringingNumber, ringingName, Type.OUTGOING, startedAt, talk)
                    else -> null
                }?.copy(simSlot = source?.slotIndex,
                    simLabel = source?.displayName, carrier = source?.carrier)
                ringingSim = null
                connectedSince = 0L
                declinedHere = false
                record?.let(::add)
                return record
            }
        }
        return null
    }
}

/** Remote: starred contacts, shown first in the Calls tab. Kept on this phone only. */
object Favourites {
    private const val KEY = "favourite_contacts"
    private val _list = MutableStateFlow<List<Contact>>(emptyList())
    val list: StateFlow<List<Contact>> = _list
    private var app: Context? = null

    /** Numbers match whatever their format: compare the last 9 digits. */
    fun key(number: String): String = ContactStore.digits(number).takeLast(9)

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        _list.value = Prefs.of(ctx).getStringSet(KEY, emptySet()).orEmpty()
            .mapNotNull { line ->
                val i = line.indexOf('\t')
                if (i < 0) null else Contact(line.substring(0, i), line.substring(i + 1))
            }
            .sortedBy { it.name.ifEmpty { it.number }.lowercase() }
    }

    fun isFavourite(number: String): Boolean {
        val k = key(number)
        return k.isNotEmpty() && _list.value.any { key(it.number) == k }
    }

    @Synchronized
    fun toggle(contact: Contact) {
        val ctx = app ?: return
        val k = key(contact.number)
        if (k.isEmpty()) return
        val current = _list.value
        val updated = if (current.any { key(it.number) == k }) current.filterNot { key(it.number) == k }
        else (current + contact).sortedBy { it.name.ifEmpty { it.number }.lowercase() }
        _list.value = updated
        Prefs.of(ctx).edit()
            .putStringSet(KEY, updated.map { "${it.name.replace('\t', ' ')}\t${it.number}" }.toSet())
            .apply()
    }
}
