package com.dynokore.gst.tenancy.adapters.http

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
import java.util.Base64

/** What a delta cursor is bound to besides its payload: the entity, the acting user ID and the release asked for. */
data class PackDeltaScope(
    val entityRef: String,
    val userId: String,
    val packIdentifier: String,
    val version: String,
)

/**
 * The cursor of `getCalendarPackDelta` (INV-Q-16810 C), built like [PeriodListCursor] with its own layout label
 * (`pd1`), so a `listPeriodRecords` token is refused here and the reverse: `pd1.` + base64url(issue time,
 * last periodRef) + `.` + base64url(tag), the tag a HMAC-SHA256 of `Macs` (CS-53) over the layout, the entityRef,
 * the acting user ID (never a role), the packIdentifier, the version, the sort key and the payload. The same secrets
 * are used, read through [SecretStore] at every use; the previous key is accepted for verification only.
 */
class PackDeltaCursor(
    private val secrets: SecretStore,
) {
    /** The token for the page after [lastPeriodRef]. A missing, blank or short current key is a 5xx, never unsigned. */
    fun issue(
        scope: PackDeltaScope,
        lastPeriodRef: String,
        now: Instant,
    ): String {
        val payload = "${now.toEpochMilli()}\n$lastPeriodRef".toByteArray(Charsets.UTF_8)
        val tag = Macs.tag(currentKey(), signed(scope, payload))
        return "$LAYOUT.${ENCODER.encodeToString(payload)}.${ENCODER.encodeToString(tag)}"
    }

    /** The last periodRef the token names, or a 400 that does not say which check failed (SRT-001). */
    fun open(
        token: String,
        scope: PackDeltaScope,
        now: Instant,
    ): String {
        val parts = token.split('.')
        val payload = if (parts.size == PARTS && parts[0] == LAYOUT) decoded(parts[1]) else null
        val tag = if (payload != null) decoded(parts[2]) else null
        val key = currentKey()
        val genuine =
            payload != null && tag != null &&
                listOfNotNull(key, previousKey()).any {
                    Macs.verify(it, signed(scope, payload), tag)
                }
        val position = if (genuine) positionOf(requireNotNull(payload), now) else null
        return position ?: invalid(REFUSED)
    }

    private fun positionOf(
        payload: ByteArray,
        now: Instant,
    ): String? {
        val fields = String(payload, Charsets.UTF_8).split('\n', limit = FIELDS)
        val issued = fields.getOrNull(0)?.toLongOrNull()?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }
        val ref = fields.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val fresh = issued != null && !issued.isAfter(now) && !issued.plus(MAX_AGE).isBefore(now)
        return if (ref != null && fresh) ref else null
    }

    private fun currentKey(): MacKey =
        runCatching { macKey(PeriodListCursor.CURRENT_SECRET) }.getOrNull() ?: run {
            LOG.error(
                "the cursor key secret '{}' is missing, blank or shorter than 32 bytes",
                PeriodListCursor.CURRENT_SECRET,
            )
            throw ApiRefusal(ClosedErrorCode.INTERNAL_ERROR)
        }

    private fun previousKey(): MacKey? = runCatching { macKey(PeriodListCursor.PREVIOUS_SECRET) }.getOrNull()

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
        scope: PackDeltaScope,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        listOf(
            LAYOUT.toByteArray(),
            scope.entityRef.toByteArray(),
            scope.userId.toByteArray(),
            scope.packIdentifier.toByteArray(),
            scope.version.toByteArray(),
            SORT.toByteArray(),
            payload,
        ).forEach {
            data.writeInt(it.size)
            data.write(it)
        }
        return out.toByteArray()
    }

    companion object {
        const val LAYOUT = "pd1"
        const val SORT = "periodRef:asc"
        const val REFUSED = "cursor is not valid"
        val MAX_AGE: Duration = Duration.ofHours(24)
        private const val PARTS = 3
        private const val FIELDS = 2
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
        private val LOG: Logger = LoggerFactory.getLogger(PackDeltaCursor::class.java)
    }
}
