package com.dynokore.gst.access.domain.principal

import com.dynokore.gst.access.domain.grant.TenantRef

/**
 * The six machine principal types of INV-REQ-584, named as the access contract names them. A principal has exactly one
 * type and no credential combines two. The five other members of the contract enum (MACHINE_CLIENT, SCHEDULED_JOB,
 * AGENT, SUPPORT_TOOL, INTEGRATION) have no published permission set, so [ofContract] answers null for them and for any
 * other text (fail closed).
 */
enum class PrincipalType {
    INGEST,
    FILING_WORKFLOW,
    AP_CALLBACK,
    REPORTING,
    MONITORING,
    CERTIFICATION,
    ;

    companion object {
        fun ofContract(name: String): PrincipalType? = entries.singleOrNull { it.name == name }
    }
}

enum class PrincipalStatus { ACTIVE, REVOKED }

/** A service principal: one tenant, one type, one scope (INV-REQ-867). Revocation is immediate and final. */
data class ServicePrincipal(
    val ref: String,
    val tenant: TenantRef,
    val type: PrincipalType,
    val scope: String,
    val callingSystem: String?,
    val status: PrincipalStatus,
    val version: Long,
) {
    init {
        require(ref.isNotBlank() && ref.length <= MAX_REF) { "a principal reference is required, at most $MAX_REF" }
        require(scope.isNotBlank() && scope.length <= MAX_SCOPE) { "a scope is required, at most $MAX_SCOPE" }
        require(callingSystem == null || callingSystem.length in 1..MAX_CALLING_SYSTEM) {
            "callingSystem is 1 to $MAX_CALLING_SYSTEM characters"
        }
        require(type != PrincipalType.FILING_WORKFLOW || callingSystem != null) {
            "a FILING_WORKFLOW principal names its callingSystem (INV-REQ-587)"
        }
    }

    fun revoked(): ServicePrincipal =
        if (status == PrincipalStatus.REVOKED) this else copy(status = PrincipalStatus.REVOKED, version = version + 1)

    private companion object {
        const val MAX_REF = 64
        const val MAX_SCOPE = 128
        const val MAX_CALLING_SYSTEM = 64
    }
}
