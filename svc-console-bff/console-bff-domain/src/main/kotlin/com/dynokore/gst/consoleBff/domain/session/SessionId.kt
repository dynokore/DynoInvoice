package com.dynokore.gst.consoleBff.domain.session

import java.security.MessageDigest
import java.util.Locale

/** A well-formed session cookie value. The value is never printed: `toString` is fixed text. */
@JvmInline
value class SessionId private constructor(
    internal val value: String,
) {
    override fun toString(): String = "SessionId(redacted)"

    companion object {
        private val SHAPE = Regex("[A-Za-z0-9_-]{43}")

        /** Exactly 43 base64url characters (256 bits, ASVS V3.2.2) parse; anything else is Malformed. */
        fun parse(cookieValue: String): SessionIdParse =
            if (SHAPE.matches(cookieValue)) SessionIdParse.Parsed(SessionId(cookieValue)) else SessionIdParse.Malformed
    }
}

sealed interface SessionIdParse {
    data class Parsed(
        val id: SessionId,
    ) : SessionIdParse

    data object Malformed : SessionIdParse
}

object SessionIdHash {
    /** Lower-case 64-character hexadecimal SHA-256 of the UTF-8 bytes of the value. */
    fun of(id: SessionId): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(id.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
