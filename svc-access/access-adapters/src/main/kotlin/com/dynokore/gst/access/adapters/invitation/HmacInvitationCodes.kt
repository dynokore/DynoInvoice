package com.dynokore.gst.access.adapters.invitation

import com.dynokore.gst.access.application.invitation.InvitationCodes
import com.dynokore.gst.access.application.invitation.InvitationKeyUnavailable
import com.dynokore.gst.access.application.invitation.InvitationRefs
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The one-time code of an invitation (INV-Q-14261 A; NIST SP 800-57, SP 800-107): the unpadded base64url text of
 * HMAC-SHA-256 over `access-invitation-code:v1:<tenant>:<invitationRef>`, keyed by the secret
 * `access/invitation-code-key/<keyVersion>` read through the SecretStore at every use. The key is at least 32 bytes; a
 * shorter, missing or unreadable key is [InvitationKeyUnavailable]. The digest kept in place of the code is the
 * SHA-256 of the code as lower-case hex, the form `NimbusCredentialMaterial.verifierOf` produces.
 */
class HmacInvitationCodes(
    private val secrets: SecretStore,
) : InvitationCodes {
    override fun codeOf(
        tenant: TenantRef,
        invitationRef: String,
        keyVersion: String,
    ): String {
        val key =
            try {
                secrets.read(SecretRef(KEY_PATH_PREFIX + keyVersion)).reveal().toByteArray(StandardCharsets.UTF_8)
            } catch (_: Exception) {
                throw InvitationKeyUnavailable()
            }
        if (key.size < MIN_KEY_BYTES) throw InvitationKeyUnavailable()
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }
        val bytes = mac.doFinal("$CONTEXT${tenant.value}:$invitationRef".toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun digestOf(code: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.toByteArray(StandardCharsets.UTF_8)))

    companion object {
        const val KEY_PATH_PREFIX = "access/invitation-code-key/"
        const val CONTEXT = "access-invitation-code:v1:"
        private const val ALGORITHM = "HmacSHA256"
        private const val MIN_KEY_BYTES = 32
    }
}

/** `INVN-` and 128 random bits as unpadded base64url (22 characters): 27 characters, at most 48. */
class RandomInvitationRefs(
    private val random: SecureRandom = SecureRandom(),
) : InvitationRefs {
    override fun next(): String {
        val bytes = ByteArray(RANDOM_BYTES)
        random.nextBytes(bytes)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val PREFIX = "INVN-"
        const val RANDOM_BYTES = 16
    }
}
