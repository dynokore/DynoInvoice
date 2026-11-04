package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.period.PagePosition
import com.dynokore.platform.crypto.MacAlgorithm
import com.dynokore.platform.crypto.MacKey
import com.dynokore.platform.crypto.Macs
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/**
 * The cursor of `listPeriodRecords` (ruling INV-Q-16673): `v1.` + base64url(position, issue time) + `.` +
 * base64url(tag), the tag a HMAC-SHA256 of `Macs` (CS-53) over the layout, the entityRef of the path, the acting user
 * ID (never a role), the sort key, the filter key and the payload. The entityRef, the user ID and the key are not in
 * the token. The key is read through [SecretStore] at every use, never cached (a rotated secret needs no restart); the
 * previous key is accepted for verification only.
 */
class PeriodListCursor(
    private val secrets: SecretStore,
) {
    /** The token for the page after [last]. A missing, blank or short current key is a 5xx, never an unsigned page. */
    fun issue(
        entityRef: String,
        userId: String,
        last: PagePosition,
        now: Instant,
    ): String {
        val payload = "${last.start}\n${now.toEpochMilli()}\n${last.periodRef}".toByteArray(Charsets.UTF_8)
        val tag = Macs.tag(currentKey(), signed(entityRef, userId, payload))
        return "$LAYOUT.${ENCODER.encodeToString(payload)}.${ENCODER.encodeToString(tag)}"
    }

    /** The position the token names, or a 400 that does not say which check failed (SRT-001). */
    fun open(
        token: String,
        entityRef: String,
        userId: String,
        now: Instant,
    ): PagePosition {
        val parts = token.split('.')
        val payload = if (parts.size == PARTS && parts[0] == LAYOUT) decoded(parts[1]) else null
        val tag = if (payload != null) decoded(parts[2]) else null
        val key = currentKey()
        val genuine =
            payload != null && tag != null &&
                keysToTry(key).any { Macs.verify(it, signed(entityRef, userId, payload), tag) }
        val position = if (genuine) positionOf(requireNotNull(payload), now) else null
        return position ?: invalid(REFUSED)
    }

    private fun positionOf(
        payload: ByteArray,
        now: Instant,
    ): PagePosition? {
        val fields = String(payload, Charsets.UTF_8).split('\n', limit = FIELDS)
        val start = fields.getOrNull(0)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val issued = fields.getOrNull(1)?.toLongOrNull()?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }
        val ref = fields.getOrNull(2)?.takeIf { it.isNotEmpty() }
        val fresh = issued != null && !issued.isAfter(now) && !issued.plus(MAX_AGE).isBefore(now)
        return if (start != null && ref != null && fresh) PagePosition(start, ref) else null
    }

    private fun keysToTry(current: MacKey): List<MacKey> = listOfNotNull(current, previousKey())

    private fun currentKey(): MacKey =
        runCatching { macKey(CURRENT_SECRET) }.getOrNull() ?: run {
            LOG.error("the cursor key secret '{}' is missing, blank or shorter than 32 bytes", CURRENT_SECRET)
            throw ApiRefusal(ClosedErrorCode.INTERNAL_ERROR)
        }

    private fun previousKey(): MacKey? = runCatching { macKey(PREVIOUS_SECRET) }.getOrNull()

    private fun macKey(path: String): MacKey {
        val text =
            try {
                secrets.read(SecretRef(path)).reveal()
            } catch (_: SecretNotFound) {
                error("no secret")
            }
        require(text.isNotBlank()) { "blank" }
        return MacKey(MacAlgorithm.HMAC_SHA_256, text.toByteArray(Charsets.UTF_8))
    }

    /** Strict base64url: the text must be the canonical encoding of what it decodes to. */
    private fun decoded(text: String): ByteArray? =
        runCatching { DECODER.decode(text) }.getOrNull()?.takeIf { ENCODER.encodeToString(it) == text }

    /** Length-prefixed fields: no value can shift into its neighbour. */
    private fun signed(
        entityRef: String,
        userId: String,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        listOf(
            LAYOUT.toByteArray(),
            entityRef.toByteArray(),
            userId.toByteArray(),
            SORT.toByteArray(),
            FILTER.toByteArray(),
            payload,
        ).forEach {
            data.writeInt(it.size)
            data.write(it)
        }
        return out.toByteArray()
    }

    companion object {
        const val CURRENT_SECRET = "tenancy/period-list-cursor-key"
        const val PREVIOUS_SECRET = "tenancy/period-list-cursor-key-previous"
        const val LAYOUT = "v1"
        const val SORT = "periodStart:asc"
        const val FILTER = "none"
        const val REFUSED = "cursor is not valid"
        val MAX_AGE: Duration = Duration.ofHours(24)
        private const val PARTS = 3
        private const val FIELDS = 3
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
        private val LOG: Logger = LoggerFactory.getLogger(PeriodListCursor::class.java)
    }
}
