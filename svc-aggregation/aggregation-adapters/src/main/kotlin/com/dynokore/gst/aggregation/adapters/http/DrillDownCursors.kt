package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.gst.aggregation.application.DrillDownPosition
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The secret path of the HMAC key of the `nextCursor` of `getAggregateDrillDown`. */
const val DRILL_DOWN_CURSOR_KEY_SECRET = "aggregation/drill-down-cursor-key"

/**
 * API-18: the `nextCursor` of `getAggregateDrillDown` is a payload (tenant, principal, aggregate, the sort key of the
 * last item, an expiry 24 hours after issue) and an HMAC-SHA256 tag over it, both base64url. The key is read through
 * [SecretStore] at every use (STK-29), so a rotated secret needs no restart; when it cannot be read no cursor is
 * issued. [decode] answers null for a cursor that is malformed, forged, altered, issued for another tenant, principal
 * or aggregate, or expired: the caller answers 400.
 */
class DrillDownCursors(
    private val secrets: SecretStore,
    private val clock: Clock,
) {
    /** What a cursor is bound to: the principal and the filter (the aggregate); the sort is fixed by the version. */
    data class Scope(
        val tenant: String,
        val actor: String,
        val aggregateId: String,
    ) {
        internal fun fields() = listOf(tenant, actor, aggregateId)
    }

    fun encode(
        last: DrillDownPosition,
        scope: Scope,
    ): String {
        val expires = clock.instant().plus(VALID_FOR)
        val fields =
            listOf(VERSION) + scope.fields() + listOf(last.underlyingRef, last.transactionRef, expires.toString())
        val body =
            fields
                .joinToString(
                    SEPARATOR,
                ) { ENCODER.encodeToString(it.toByteArray(Charsets.UTF_8)) }
                .toByteArray()
        return "${ENCODER.encodeToString(body)}.${ENCODER.encodeToString(mac(body))}"
    }

    fun decode(
        cursor: String,
        scope: Scope,
    ): DrillDownPosition? {
        val fields = verified(cursor)?.takeIf { it.size == SIZE && it[0] == VERSION } ?: return null
        val expires = runCatching { Instant.parse(fields[EXPIRES]) }.getOrNull() ?: return null
        val bound = fields.subList(1, 1 + SCOPE) == scope.fields()
        val live = !clock.instant().isAfter(expires)
        return if (bound && live) DrillDownPosition(fields[UNDERLYING], fields[REF]) else null
    }

    /** The decoded fields when the tag is the HMAC of the payload, checked before anything in it is read; else null. */
    private fun verified(cursor: String): List<String>? {
        val parts = cursor.split('.')
        val body = parts.takeIf { it.size == 2 }?.let { decoded(it[0]) }
        val tag = parts.getOrNull(1)?.let { decoded(it) }
        if (body == null || tag == null || !MessageDigest.isEqual(mac(body), tag)) return null
        return String(
            body,
            Charsets.UTF_8,
        ).split(SEPARATOR).map { decoded(it)?.toString(Charsets.UTF_8) ?: return null }
    }

    private fun decoded(text: String): ByteArray? = runCatching { DECODER.decode(text) }.getOrNull()

    private fun mac(body: ByteArray): ByteArray {
        val key = secrets.read(SecretRef(DRILL_DOWN_CURSOR_KEY_SECRET)).reveal().toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(body)
    }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val VERSION = "v1"
        const val SEPARATOR = ":"
        const val SCOPE = 3
        const val UNDERLYING = 4
        const val REF = 5
        const val EXPIRES = 6
        const val SIZE = 7
        val VALID_FOR: Duration = Duration.ofHours(24)
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
