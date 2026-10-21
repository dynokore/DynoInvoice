package com.dynokore.gst.gatewayauthz.revocation

/**
 * The kind of a getRevocationList entry (INV-API-4001). The wire enum is extensible (API-09): a kind this build does
 * not know maps to [UNKNOWN], which is kept on the list but matches no token.
 */
enum class RevocationKind {
    PRINCIPAL,
    SUBJECT,
    TOKEN,
    UNKNOWN,
    ;

    companion object {
        private val known = entries.filter { it != UNKNOWN }.associateBy { it.name }

        fun fromWire(value: String): RevocationKind = known[value] ?: UNKNOWN
    }
}
