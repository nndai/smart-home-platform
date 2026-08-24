package com.nndai.myhome.core.utils

/**
 * Derives avatar initials from a person's display name or email.
 *
 * Vietnamese convention: surname first ("Nguyễn Văn Dài") → "ND"
 * (first-word initial + last-word initial). Single word → its own initial.
 * Email input uses the local part ("nndai.x@gmail.com" → "NN").
 */
object Initials {

    fun fromName(name: String?): String? {
        val words = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }
        return when {
            words.isNullOrEmpty() -> null
            words.size == 1 -> words[0].take(1).uppercase()
            else -> (words.first().take(1) + words.last().take(1)).uppercase()
        }
    }

    /** Email variant: use the local part before '@'. */
    fun fromEmail(email: String?): String? {
        val localPart = email?.substringBefore("@")?.takeIf { it.isNotBlank() } ?: return null
        // Dots/dashes/underscores act as word separators: "nguyen.dai" → "ND"
        val words = localPart.split(".", "_", "-").filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> null
            words.size == 1 && words[0].length == 1 -> words[0].take(1).uppercase()
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words.first().take(1) + words.last().take(1)).uppercase()
        }
    }

    /** Best-effort: try display name first, then email. */
    fun of(displayName: String?, email: String?): String? =
        fromName(displayName) ?: fromEmail(email)
}
