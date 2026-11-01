package com.dynokore.gst.access.adapters.breakglass

import com.dynokore.gst.access.application.breakglass.SealedAccountRules
import com.dynokore.gst.access.application.breakglass.SealingCredentials
import java.security.SecureRandom
import java.util.Base64

/** New sealing credentials from the platform secure random: 32 bytes, base64url without padding (INV-AC-8899). */
class SecureRandomSealingCredentials(
    private val random: SecureRandom = SecureRandom(),
) : SealingCredentials {
    override fun generate(): String {
        val bytes = ByteArray(SealedAccountRules.MIN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
