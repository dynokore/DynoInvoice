package com.dynokore.gst.rules.application

import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One active regulatory pack of the platform layer as the installer reads it (INV-REQ-1105 behavior 1). */
data class ActivePack(
    val packType: String,
    val version: String,
    val status: String,
    val minEngineVersion: String,
    val effectiveFrom: LocalDate?,
    val effectiveTo: LocalDate?,
)

/** The position after which a page continues: the last (packType, version) of the previous page. */
data class ActivePackPosition(
    val packType: String,
    val version: String,
)

/** The platform rows in state ARMED (or ACTIVE) of the REGULATORY layer, ordered by packType then version. */
fun interface ActivePackReader {
    fun page(
        after: ActivePackPosition?,
        limit: Int,
    ): List<ActivePack>
}

/** The key the cursor integrity check uses (API-18: opaque, integrity-protected). */
fun interface ListCursorKey {
    fun bytes(): ByteArray
}

sealed interface ActivePacksOutcome {
    data class Page(
        val items: List<ActivePack>,
        val nextCursor: String?,
    ) : ActivePacksOutcome

    /** The cursor does not decode, or fails its integrity check: it is not one this service issued (HTTP 400). */
    data object MalformedCursor : ActivePacksOutcome

    /** The limit is outside 1..[ListActiveRegulatoryPacks.MAX_LIMIT] (HTTP 400). */
    data object InvalidLimit : ActivePacksOutcome
}

/**
 * `listActiveRegulatoryPacks`: the armed regulatory packs of the platform layer with their minimum engine version, one
 * page at a time by an HMAC-protected keyset cursor (API-18). Read-only; the caller check is the generated one.
 */
class ListActiveRegulatoryPacks(
    private val reader: ActivePackReader,
    private val cursorKey: ListCursorKey,
) {
    fun handle(
        limit: Int,
        cursor: String?,
    ): ActivePacksOutcome {
        val after = cursor?.let(::decode)
        return when {
            limit !in 1..MAX_LIMIT -> ActivePacksOutcome.InvalidLimit
            cursor != null && after == null -> ActivePacksOutcome.MalformedCursor
            else -> pageAfter(after, limit)
        }
    }

    private fun pageAfter(
        after: ActivePackPosition?,
        limit: Int,
    ): ActivePacksOutcome.Page {
        val rows = reader.page(after, limit + 1)
        val page = rows.take(limit)
        val last = page.lastOrNull()?.takeIf { rows.size > limit }
        return ActivePacksOutcome.Page(page, last?.let { encode(ActivePackPosition(it.packType, it.version)) })
    }

    private fun encode(position: ActivePackPosition): String {
        val body = listOf(VERSION, b64(position.packType), b64(position.version)).joinToString(SEPARATOR)
        return b64(body) + "." + B64.encodeToString(mac(body))
    }

    private fun decode(cursor: String): ActivePackPosition? {
        val parts = cursor.split('.')
        val body = parts.getOrNull(0)?.let(::unb64)
        val given = parts.getOrNull(1)?.let { runCatching { B64D.decode(it) }.getOrNull() }
        val genuine = parts.size == 2 && body != null && given != null && MessageDigest.isEqual(given, mac(body))
        val fields = if (genuine) body.split(SEPARATOR) else null
        val type = fields?.takeIf { it.size == FIELDS && it[0] == VERSION }?.let { unb64(it[1]) }
        val version = fields?.takeIf { it.size == FIELDS }?.let { unb64(it[2]) }
        return if (type != null && version != null) ActivePackPosition(type, version) else null
    }

    private fun mac(body: String): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(cursorKey.bytes(), HMAC))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8))
    }

    private fun b64(text: String): String = B64.encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun unb64(text: String): String? = runCatching { String(B64D.decode(text), Charsets.UTF_8) }.getOrNull()

    companion object {
        const val MAX_LIMIT = 500
        private const val HMAC = "HmacSHA256"
        private const val VERSION = "v1"
        private const val SEPARATOR = "|"
        private const val FIELDS = 3
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64D = Base64.getUrlDecoder()
    }
}
