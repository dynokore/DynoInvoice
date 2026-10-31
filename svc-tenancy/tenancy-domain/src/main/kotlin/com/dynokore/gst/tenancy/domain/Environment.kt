package com.dynokore.gst.tenancy.domain

/**
 * The environment of a tenant, a workspace or a credential: written exactly as the contract writes it
 * (INV-REQ-502).
 */
enum class Environment {
    PRODUCTION,
    SANDBOX,
    ;

    companion object {
        /**
         * The environment named by [tag], by exact equality; a missing, blank, differently cased or unknown
         * tag is none.
         */
        fun ofTag(tag: String?): Environment? = entries.firstOrNull { it.name == tag }
    }
}

/** Why a credential was refused (INV-REQ-1126). */
enum class CredentialRefusal { UNTAGGED, TAG_DIFFERS }

/**
 * Credential isolation (INV-REQ-1126, INV-REQ-502): a credential is accepted by a target only when its own environment
 * tag equals the target's environment. The credential's name is never an input, so it cannot be misread as one.
 */
object CredentialEnvironmentRule {
    /** Null when [tag] is accepted by [target], else the reason. A credential with no recognised tag is refused. */
    fun refusal(
        target: Environment,
        tag: String?,
    ): CredentialRefusal? =
        when (Environment.ofTag(tag)) {
            null -> CredentialRefusal.UNTAGGED
            target -> null
            else -> CredentialRefusal.TAG_DIFFERS
        }
}
