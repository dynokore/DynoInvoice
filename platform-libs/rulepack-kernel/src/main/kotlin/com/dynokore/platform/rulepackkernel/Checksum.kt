package com.dynokore.platform.rulepackkernel

/**
 * A SHA-256 digest, lower-case hex (64 characters) — the form the pack
 * artefact's `checksums` member and [PackManifest.contentChecksums] both use
 * (STK-13's artefact format: "manifest, checksums, detached CMS signature,
 * contents").
 */
@JvmInline
value class Checksum(val hex: String) {
    init {
        require(HEX_SHA256.matches(hex)) { "Checksum must be 64 lower-case hex characters (SHA-256), was '$hex'" }
    }

    override fun toString(): String = hex

    companion object {
        private val HEX_SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
