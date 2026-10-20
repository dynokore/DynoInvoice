package com.dynokore.platform.tenancykernel

import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The value of `app.binding_sig` (INV-REQ-854 b7, owner ruling INV-Q-4121; layout in README.md "Signed binding"):
 * `<tenant>.<actor base64url>.<expiry seconds>.<mac hex>`, the MAC being HMAC-SHA256 over the first three fields as
 * transmitted. With an entity set the value has a fifth field before the MAC (INV-T-7520, INV-Q-7453):
 * `<tenant>.<actor>.<expiry>.<entities>.<mac>`. `bound_tenant()`, `bound_actor()` and `bound_entities()` verify it;
 * this is the signing half.
 */
object BindingSignature {
    private const val MILLIS_PER_SECOND = 1000L

    private const val ALGORITHM = "HmacSHA256"

    private const val EMPTY_SET_FIELD = "-"

    /**
     * The signed value for [tenant] and [actor], valid until [expiryEpochSeconds]. With [entities] null it is the
     * four-field form with no set; otherwise the five-field form, whose set field is [entityField] (an empty set is a
     * set).
     */
    fun sign(
        key: ByteArray,
        tenant: TenantId,
        actor: String,
        expiryEpochSeconds: Long,
        entities: Set<UUID>? = null,
    ): String {
        val entityField = entities?.let { entityField(it) }
        require(key.isNotEmpty()) { "The binding key is empty." }
        require(
            expiryEpochSeconds in 1..MAX_EXPIRY,
        ) { "The expiry $expiryEpochSeconds is outside 1..$MAX_EXPIRY seconds." }
        val actorField = Base64.getUrlEncoder().withoutPadding().encodeToString(actor.toByteArray(Charsets.UTF_8))
        val text = "$tenant.$actorField.$expiryEpochSeconds" + (entityField?.let { ".$it" } ?: "")
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return text + "." +
            mac.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    /**
     * The entity field of a set: `-` when empty, else the lower-case UUIDs in ascending order of their text, each once,
     * joined by `,` (README.md "Signed binding").
     */
    fun entityField(entities: Set<UUID>): String =
        if (entities.isEmpty()) {
            EMPTY_SET_FIELD
        } else {
            entities
                .map { it.toString() }
                .distinct()
                .sorted()
                .joinToString(",")
        }

    /** The latest expiry (whole seconds, rounded down) that does not pass [start] plus [timeout]. */
    fun expiryFor(
        start: Instant,
        timeout: Duration,
    ): Long = Math.floorDiv(start.toEpochMilli() + timeout.toMillis(), MILLIS_PER_SECOND)

    /** True when [expiryEpochSeconds] is no later than [start] plus [timeout]; equality is allowed. */
    fun withinTimeout(
        expiryEpochSeconds: Long,
        start: Instant,
        timeout: Duration,
    ): Boolean = expiryEpochSeconds * MILLIS_PER_SECOND <= start.toEpochMilli() + timeout.toMillis()

    /** Twelve decimal digits, the longest expiry the database functions accept. */
    private const val MAX_EXPIRY = 999_999_999_999L
}
