package com.pcl.app

/** SIM caller IDs may be absent or explicitly withheld; neither is dialable. */
object RecentCallNumber {
    fun isDialable(value: String): Boolean {
        val number = value.trim()
        if (number.isEmpty() || number.lowercase() in setOf(
                "private number", "unknown", "unknown number", "no caller id",
                "restricted", "withheld", "anonymous")) return false
        if (!number.matches(Regex("^\\+?[0-9 ()-]{3,30}$"))) return false
        return number.count(Char::isDigit) >= 3
    }
}
