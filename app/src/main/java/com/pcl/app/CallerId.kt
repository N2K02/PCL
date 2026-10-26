package com.pcl.app

import java.text.Normalizer
import java.util.Locale

/**
 * Turning what Android, the Phone app and WhatsApp show for a caller into a clean number and
 * contact name. Pure Kotlin (no Android types) so it can be unit tested.
 *
 * Phone apps and WhatsApp often wrap numbers in invisible direction marks (U+202A…U+202E,
 * U+2066…U+2069, U+200E/U+200F) and use non-breaking spaces. Those must be removed before a
 * label can be recognised as a number or matched against a contact.
 */
object CallerId {
    /** Shown on the Remote, and in Recents, for a call whose number the caller withheld. */
    const val PRIVATE = "Private number"

    /** Labels that mean "no number" because the caller withheld it. */
    private val PRIVATE_LABELS = setOf(
        "private number", "private", "no caller id", "restricted", "withheld", "number withheld",
        "hidden number", "anonymous", "blocked", "numéro masqué", "numero privato", "número privado",
        "unterdrückte nummer", "privat"
    )

    /** Labels that say nothing about who is calling. */
    private val PLACEHOLDER_LABELS = setOf(
        "unknown", "unknown number", "unknown caller", "incoming call", "ongoing call",
        "outgoing call", "call in progress", "calling", "calling…", "calling...", "phone",
        "voice call", "incoming voice call", "ongoing voice call", "whatsapp voice call",
        "video call", "incoming video call", "whatsapp", "whatsapp business", "dialing", "dialling"
    )

    private val NUMBER = Regex("^\\+?[0-9][0-9() .\\-/]{3,28}[0-9]$")

    /** Removes invisible format characters, turns any space into a plain one and trims. */
    fun clean(label: String?): String {
        if (label == null) return ""
        val sb = StringBuilder(label.length)
        for (ch in label) {
            when {
                Character.getType(ch) == Character.FORMAT.toInt() -> Unit
                ch == '﻿' -> Unit
                Character.isSpaceChar(ch) || ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim().replace(Regex(" {2,}"), " ")
    }

    /** The label as a phone number (cleaned, original formatting kept), or "" when it isn't one. */
    fun asNumber(label: String?): String {
        val cleaned = clean(label)
        val c = if (cleaned.startsWith("tel:", ignoreCase = true)) cleaned.substring(4).trim() else cleaned
        return if (NUMBER.matches(c) && c.count(Char::isDigit) >= 5) c else ""
    }

    fun isPrivate(label: String?): Boolean = clean(label).lowercase(Locale.ROOT) in PRIVATE_LABELS

    /** True when the label is a generic placeholder or a withheld-number label, not a name. */
    fun isPlaceholder(label: String?): Boolean {
        val c = clean(label).lowercase(Locale.ROOT)
        return c.isEmpty() || c in PLACEHOLDER_LABELS || c in PRIVATE_LABELS
    }

    /** The label as a contact name, or "" when it is empty, a placeholder or a number. */
    fun asName(label: String?): String {
        val c = clean(label)
        return if (isPlaceholder(c) || asNumber(c).isNotEmpty()) "" else c
    }

    /** For comparing names: compatibility-normalised, single-spaced and case-folded. */
    fun nameKey(name: String?): String =
        Normalizer.normalize(clean(name), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /** True when two numbers are the same line whatever their format or country prefix. */
    fun sameNumber(first: String, second: String): Boolean {
        val a = first.filter(Char::isDigit)
        val b = second.filter(Char::isDigit)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        // A national number (leading 0 dropped) inside an international one: compare the tail.
        val tail = minOf(a.length, b.length, 9)
        return tail >= 7 && a.takeLast(tail) == b.takeLast(tail)
    }

    /**
     * The number of the contact called [name], or "" when no contact or several different
     * numbers have that name (never guess between two people or two lines).
     */
    fun numberForName(name: String, contacts: List<Contact>): String {
        val key = nameKey(name)
        if (key.length < 2 || isPlaceholder(name)) return ""
        val matches = contacts.filter { nameKey(it.name) == key && it.number.any(Char::isDigit) }
        if (matches.isEmpty()) return ""
        val distinct = ArrayList<Contact>()
        for (m in matches) if (distinct.none { sameNumber(it.number, m.number) }) distinct.add(m)
        return if (distinct.size == 1) distinct[0].number else ""
    }

    /**
     * Resolves a caller label (title or Person name from a notification) into number and name.
     * A label that is itself a number fills the number; a name is looked up in [contacts].
     */
    fun resolve(label: String, contacts: List<Contact>): Pair<String, String> {
        val number = asNumber(label)
        if (number.isNotEmpty()) {
            val name = contacts.firstOrNull { sameNumber(it.number, number) }?.name.orEmpty()
            return number to name
        }
        val name = asName(label)
        return numberForName(name, contacts) to name
    }
}
