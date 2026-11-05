package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.registry.RegisterSuggestion
import com.dynokore.gst.masterdata.application.registry.SuggestionCursor
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The secret path of the HMAC key of the `nextCursor` of `listRegisterSuggestions` (INV-Q-10175 A). */
const val REGISTER_SUGGESTION_CURSOR_KEY_SECRET = "masterdata/register-suggestion-cursor-key"

/**
 * API-18, INV-Q-10175 A: the `nextCursor` of `listRegisterSuggestions` is a payload (tenant, principal,
 * `counterpartyRef`, `state`, the `suggestedAt` and reference of the last item, an expiry 24 hours after issue) and an
 * HMAC-SHA256 tag over it, both base64url. The key is read through [SecretStore] at every use (STK-29), so a rotated
 * secret needs no restart. When the key cannot be read no cursor is issued: the read throws and the call fails.
 * [decode] answers null for a cursor that is malformed, forged, altered, issued for another tenant, principal,
 * counterparty or state, or expired.
 */
class SuggestionCursors(
    private val secrets: SecretStore,
    private val clock: Clock,
) {
    /** What a cursor is bound to. */
    data class Scope(
        val tenant: String,
        val actor: String,
        val party: String?,
        val state: String?,
    ) {
        internal fun fields() = listOf(tenant, actor, party.orEmpty(), state.orEmpty())
    }

    fun encode(
        last: RegisterSuggestion,
        scope: Scope,
    ): String {
        val expires = clock.instant().plus(VALID_FOR)
        val payload =
            (
                listOf(VERSION) + scope.fields() +
                    listOf(last.suggestedAt.toString(), last.suggestionRef, expires.toString())
            ).joinToString(SEPARATOR)
        val body = payload.toByteArray(Charsets.UTF_8)
        return "${ENCODER.encodeToString(body)}.${ENCODER.encodeToString(mac(body))}"
    }

    fun decode(
        cursor: String,
        scope: Scope,
    ): SuggestionCursor? {
        val fields = verified(cursor)?.split(SEPARATOR)?.takeIf { it.size == SIZE && it[0] == VERSION } ?: return null
        val at = runCatching { Instant.parse(fields[AT]) }.getOrNull()
        val expires = runCatching { Instant.parse(fields[EXPIRES]) }.getOrNull()
        val bound = fields.subList(1, 1 + FILTERS) == scope.fields()
        val live = expires?.let { !clock.instant().isAfter(it) } == true
        return if (at != null && bound && live) {
            SuggestionCursor(at, fields[REF])
        } else {
            null
        }
    }

    /** The payload text when the tag is the HMAC of the payload, checked before anything in it is read; else null. */
    private fun verified(cursor: String): String? {
        val parts = cursor.split('.')
        val body = parts.takeIf { it.size == 2 }?.let { decoded(it[0]) }
        val tag = parts.getOrNull(1)?.let { decoded(it) }
        return if (body != null && tag != null && MessageDigest.isEqual(mac(body), tag)) {
            String(body, Charsets.UTF_8)
        } else {
            null
        }
    }

    private fun decoded(text: String): ByteArray? = runCatching { DECODER.decode(text) }.getOrNull()

    private fun mac(body: ByteArray): ByteArray {
        val key = secrets.read(SecretRef(REGISTER_SUGGESTION_CURSOR_KEY_SECRET)).reveal().toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(body)
    }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val VERSION = "v1"
        const val SEPARATOR = "|"
        const val FILTERS = 4
        const val AT = 5
        const val REF = 6
        const val EXPIRES = 7
        const val SIZE = 8
        val VALID_FOR: Duration = Duration.ofHours(24)
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
