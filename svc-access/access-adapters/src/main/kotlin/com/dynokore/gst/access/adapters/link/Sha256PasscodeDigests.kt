package com.dynokore.gst.access.adapters.link

import com.dynokore.gst.access.application.link.PasscodeDigests
import com.dynokore.gst.access.domain.link.LinkRef
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The digest kept in place of a one-time passcode: the lower-case hex SHA-256 of `<linkRef>:<passcode>`, so that the
 * same six digits on two links give two digests. The entry is compared in constant time ([MessageDigest.isEqual]).
 */
class Sha256PasscodeDigests : PasscodeDigests {
    override fun digestOf(
        ref: LinkRef,
        passcode: String,
    ): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest("${ref.value}:$passcode".toByteArray(StandardCharsets.UTF_8)),
        )

    override fun matches(
        ref: LinkRef,
        passcode: String,
        digest: String,
    ): Boolean =
        MessageDigest.isEqual(
            digestOf(ref, passcode).toByteArray(StandardCharsets.UTF_8),
            digest.toByteArray(StandardCharsets.UTF_8),
        )
}
