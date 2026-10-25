package com.pcl.app

/**
 * Pure phone-notification parser: preserve numbers from Person URI/name, compat Person,
 * PEOPLE_LIST, title and text, while ignoring generic dialer labels.
 */
internal object DialerCallerDetails {
    data class Identity(val number: String, val name: String)

    private val phonePattern = Regex("""^\\+?[0-9(][0-9() .-]{3,28}[0-9]$""")

    private fun phoneNumber(value: String): String {
        val trimmed = value.trim()
        val raw = if (trimmed.startsWith("tel:", ignoreCase = true)) trimmed.substring(4) else trimmed
        val cleaned = CallerDisplay.number(raw)
        return cleaned.takeIf { phonePattern.matches(it) && it.count(Char::isDigit) >= 5 }.orEmpty()
    }

    fun fromNotification(
        personUri: String,
        personName: String,
        compatName: String,
        peopleName: String,
        title: String,
        text: String,
    ): Identity {
        val number = listOf(personUri, personName, compatName, peopleName, title, text)
            .firstNotNullOfOrNull { phoneNumber(it).takeIf(String::isNotEmpty) }.orEmpty()
        val name = listOf(personName, compatName, peopleName, title)
            .asSequence()
            .map(CallerDisplay::name)
            .firstOrNull { it.isNotEmpty() && phoneNumber(it).isEmpty() }.orEmpty()
        return Identity(number, name)
    }
}
