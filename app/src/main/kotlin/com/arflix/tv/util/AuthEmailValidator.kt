package com.arflix.tv.util

object AuthEmailValidator {
    private val emailRegex = Regex(
        pattern = "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,63}$",
        option = RegexOption.IGNORE_CASE
    )

    private val blockedDomains = setOf(
        "example.com",
        "example.net",
        "example.org",
        "invalid",
        "localhost",
        "mailinator.com",
        "guerrillamail.com",
        "guerrillamail.net",
        "10minutemail.com",
        "tempmail.com",
        "temp-mail.org",
        "yopmail.com"
    )

    fun normalize(email: String): String = email.trim().lowercase()

    fun validate(email: String, rejectDisposable: Boolean = true): String? {
        val normalized = normalize(email)
        if (normalized.isBlank()) return "Email is required"
        if (normalized.length > 254 || !emailRegex.matches(normalized)) {
            return "Enter a valid email address"
        }

        val localPart = normalized.substringBefore("@")
        val domain = normalized.substringAfter("@", missingDelimiterValue = "")
        if (localPart.isBlank() || domain.isBlank()) {
            return "Use a real email address"
        }
        if (rejectDisposable && domain in blockedDomains) {
            return "Use a real email address"
        }
        if (rejectDisposable && (domain.endsWith(".invalid") || domain.endsWith(".test") || domain.endsWith(".local"))) {
            return "Use a real email address"
        }
        if (domain.split(".").any { it.isBlank() }) {
            return "Enter a valid email address"
        }
        return null
    }
}
