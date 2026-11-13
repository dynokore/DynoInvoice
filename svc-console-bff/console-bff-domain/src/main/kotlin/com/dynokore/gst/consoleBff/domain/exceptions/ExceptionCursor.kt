package com.dynokore.gst.consoleBff.domain.exceptions

import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/** Sort key of one listed exception: due date (null sorts last), then raisedAt, then exceptionId. */
data class ExceptionSortKey(
    val dueDate: LocalDate?,
    val raisedAt: Instant,
    val exceptionId: String,
)

/**
 * Opaque keyset cursor: base64url without padding of the UTF-8 text `v1|<dueDate or ->|<raisedAt>|<exceptionId>`.
 * It is not signed; a forged cursor only moves the position inside the caller's own scope, which is applied
 * again on every request.
 */
object ExceptionCursor {
    private const val VERSION = "v1"
    private const val NO_DATE = "-"
    private const val FIELDS = 4

    fun encode(key: ExceptionSortKey): String {
        val text = "$VERSION|${key.dueDate?.toString() ?: NO_DATE}|${key.raisedAt}|${key.exceptionId}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
    }

    /** Null unless [text] is exactly the encoding of a valid key. */
    fun decode(text: String): ExceptionSortKey? {
        val key = parse(text) ?: return null
        return key.takeIf { encode(it) == text }
    }

    private fun parse(text: String): ExceptionSortKey? =
        runCatching {
            val raw = String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
            val parts = raw.split('|')
            if (parts.size != FIELDS || parts[0] != VERSION) return null
            ExceptionSortKey(
                dueDate = if (parts[1] == NO_DATE) null else LocalDate.parse(parts[1]),
                raisedAt = Instant.parse(parts[2]),
                exceptionId = parts[3],
            )
        }.getOrNull()
}
