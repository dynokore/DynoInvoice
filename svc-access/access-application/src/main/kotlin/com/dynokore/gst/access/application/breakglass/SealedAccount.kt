package com.dynokore.gst.access.application.breakglass

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

/**
 * A sealing credential in plaintext, held only long enough to put it in one 200 body. [toString] hides the value so a
 * log line or an assertion message cannot leak it (INV-AC-8899).
 */
class SealingCredential(
    val value: String,
) {
    override fun toString(): String = "SealingCredential(redacted)"
}

/** The source of new sealing credentials: the platform secure random in production, a double in a test. */
fun interface SealingCredentials {
    /** A new credential: at least [SealedAccountRules.MIN_BYTES] random bytes, base64url without padding. */
    fun generate(): String
}

/** The sealed break-glass account of the bound tenant; only the digest of the credential is stored (INV-AC-8899). */
interface BreakGlassAccounts {
    /** Writes the account row with [verifier] unless the bound tenant has one; whether a row was written. */
    fun insertIfAbsent(verifier: String): Boolean

    /** The stored verifier of the bound tenant, or null when the tenant has no account row. */
    fun verifier(): String?

    /** Replaces the stored verifier of the existing row; false when the tenant has no row. */
    fun replaceVerifier(verifier: String): Boolean
}

/** The rules every sealing credential and verifier follows (INV-REQ-897, INV-AC-8899). */
object SealedAccountRules {
    const val MIN_BYTES = 32
    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")

    /** The verifier of [credential]: the lower-case hex SHA-256 digest of its UTF-8 bytes. */
    fun digestOf(credential: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(credential.toByteArray(StandardCharsets.UTF_8)),
        )

    /** Throws unless [credential] is base64url without padding and decodes to at least [MIN_BYTES] bytes. */
    fun requireWellFormed(credential: String) {
        check(BASE64URL.matches(credential)) { "a sealing credential must be base64url without padding" }
        val bytes = Base64.getUrlDecoder().decode(credential).size
        check(bytes >= MIN_BYTES) { "a sealing credential must be at least $MIN_BYTES random bytes" }
    }

    /** Constant-time equality of two verifiers. */
    fun sameVerifier(
        stored: String,
        presented: String,
    ): Boolean =
        MessageDigest.isEqual(
            stored.toByteArray(StandardCharsets.UTF_8),
            presented.toByteArray(StandardCharsets.UTF_8),
        )
}

/** Creates, rotates and checks the sealing credential of the bound tenant's account. */
class SealedAccountKeeper(
    private val accounts: BreakGlassAccounts,
    private val credentials: SealingCredentials,
) {
    /** Creates the account of the bound tenant with a new credential; null (nothing changed) when one exists. */
    fun createInitial(): SealingCredential? {
        val credential = fresh()
        return if (accounts.insertIfAbsent(
                SealedAccountRules.digestOf(credential),
            )
        ) {
            SealingCredential(credential)
        } else {
            null
        }
    }

    /** Replaces the credential; null (nothing changed) when the tenant has no account row. */
    fun rotate(): SealingCredential? {
        val credential = fresh()
        return if (accounts.replaceVerifier(
                SealedAccountRules.digestOf(credential),
            )
        ) {
            SealingCredential(credential)
        } else {
            null
        }
    }

    /** Whether [presented] is the current credential; false when the tenant has no account row (fail closed). */
    fun matches(presented: String): Boolean {
        val stored = accounts.verifier() ?: return false
        return SealedAccountRules.sameVerifier(stored, SealedAccountRules.digestOf(presented))
    }

    private fun fresh(): String = credentials.generate().also { SealedAccountRules.requireWellFormed(it) }
}
