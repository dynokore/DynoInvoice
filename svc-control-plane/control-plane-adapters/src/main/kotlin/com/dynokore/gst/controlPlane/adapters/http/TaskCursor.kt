package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.application.accreditation.TaskCursorKey
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The opaque, integrity-protected cursor of listAccreditationTasks (API-18): the sort key of the last item and an
 * HMAC-SHA256 over it, bound to the principal, the status filter and the sort. A cursor that was altered or made for
 * another query does not decode.
 */
class TaskCursor(
    private val source: () -> ByteArray,
) {
    /**
     * The HMAC key, read from the source at every list call and never cached (INV-Q-15432 A, STK-29): a missing
     * secret or a value shorter than 32 bytes throws, which the advice answers as 500.
     */
    fun key(): ByteArray =
        source().also { check(it.size >= MIN_KEY_BYTES) { "the cursor key is at least $MIN_KEY_BYTES bytes" } }

    fun encode(
        key: ByteArray,
        position: TaskCursorKey,
        principal: String,
        status: String?,
    ): String {
        val body = "${position.openedAt}|${position.taskRef}"
        val encoded = URL.encodeToString(body.toByteArray(Charsets.UTF_8))
        return encoded + "." + URL.encodeToString(mac(key, body, principal, status))
    }

    /** The position, or null when [cursor] is malformed, altered or bound to another principal, filter or sort. */
    fun decode(
        key: ByteArray,
        cursor: String,
        principal: String,
        status: String?,
    ): TaskCursorKey? =
        runCatching {
            val (encoded, tag) = cursor.split('.').also { require(it.size == 2) }
            val body = String(URL_DECODE.decode(encoded), Charsets.UTF_8)
            require(MessageDigest.isEqual(mac(key, body, principal, status), URL_DECODE.decode(tag)))
            val (at, ref) = body.split('|', limit = 2).also { require(it.size == 2) }
            TaskCursorKey(Instant.parse(at), ref)
        }.getOrNull()

    private fun mac(
        key: ByteArray,
        body: String,
        principal: String,
        status: String?,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            // the length prefixes keep one field from shifting into the next
            val bound = listOf(SORT, principal, status.orEmpty(), body).joinToString("") { "${it.length}:$it" }
            doFinal(bound.toByteArray(Charsets.UTF_8))
        }

    private companion object {
        const val MIN_KEY_BYTES = 32
        const val SORT = "openedAt-desc,taskRef-asc"
        val URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val URL_DECODE: Base64.Decoder = Base64.getUrlDecoder()
    }
}
