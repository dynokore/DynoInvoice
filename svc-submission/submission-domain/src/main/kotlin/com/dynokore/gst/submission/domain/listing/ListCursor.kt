package com.dynokore.gst.submission.domain.listing

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/** The keyset position a page of the submission list resumes from (INV-REQ-979, API-18). */
data class CursorPosition(
    val sortValue: String,
    val docUuid: String,
    val version: Int,
) {
    init {
        require(
            sortValue.isNotBlank() && !sortValue.contains(SEPARATOR),
        ) { "sortValue must be non-blank and without '|'" }
        require(docUuid.isNotBlank() && !docUuid.contains(SEPARATOR)) { "docUuid must be non-blank and without '|'" }
        require(version >= 1) { "version must be at least 1" }
    }
}

/** Why a cursor was refused; the reason never carries any part of the token or the filter. */
enum class CursorRefusal { MALFORMED, FILTER_MISMATCH }

/** The result of decoding a cursor. */
sealed interface CursorDecoding {
    data class Resumed(
        val position: CursorPosition,
    ) : CursorDecoding

    data class Refused(
        val reason: CursorRefusal,
    ) : CursorDecoding
}

private const val SEPARATOR = '|'
private const val LAYOUT = "v1"
private const val PART_COUNT = 5
private const val BINDING_AT = 1
private const val SORT_AT = 2
private const val DOC_AT = 3
private const val VERSION_AT = 4

/**
 * Opaque keyset cursor bound to the filter and sort that produced it (API-18, INV-AC-16296).
 * The token holds no identity, role, tenant or secret; authorization is re-checked on every request.
 */
object ListCursor {
    fun encode(
        position: CursorPosition,
        filterKey: String,
        sortKey: String,
    ): String {
        val text = "$LAYOUT|${binding(
            filterKey,
            sortKey,
        )}|${position.sortValue}|${position.docUuid}|${position.version}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
    }

    fun decode(
        token: String,
        filterKey: String,
        sortKey: String,
    ): CursorDecoding {
        val parts = split(token)
        val position = parts?.let { position(it) }
        return when {
            parts == null || position == null -> malformed()
            matches(parts[BINDING_AT], filterKey, sortKey) -> CursorDecoding.Resumed(position)
            else -> CursorDecoding.Refused(CursorRefusal.FILTER_MISMATCH)
        }
    }

    private fun matches(
        presented: String,
        filterKey: String,
        sortKey: String,
    ): Boolean =
        MessageDigest.isEqual(
            binding(filterKey, sortKey).toByteArray(Charsets.UTF_8),
            presented.toByteArray(Charsets.UTF_8),
        )

    private fun position(parts: List<String>): CursorPosition? {
        val version = parts[VERSION_AT].takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
        return version?.let { runCatching { CursorPosition(parts[SORT_AT], parts[DOC_AT], it) }.getOrNull() }
    }

    private fun malformed(): CursorDecoding = CursorDecoding.Refused(CursorRefusal.MALFORMED)

    private fun split(token: String): List<String>? {
        val bytes =
            token.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    Base64.getUrlDecoder().decode(
                        it,
                    )
                }.getOrNull()
            }
        val parts = bytes?.let { String(it, Charsets.UTF_8).split(SEPARATOR) }
        return parts?.takeIf { it.size == PART_COUNT && it[0] == LAYOUT }
    }

    private fun binding(
        filterKey: String,
        sortKey: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$filterKey\n$sortKey".toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}
