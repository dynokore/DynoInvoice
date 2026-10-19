package com.dynokore.gst.access.adapters.http

import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import jakarta.servlet.http.HttpServletRequest

/**
 * Whether the caller of a grant command may manage grants of the bound tenant (`x-operation.role: tenant.admin`, scope
 * tenant). The owning service re-checks writes with the propagated user token (INV-REQ-871, API-25).
 */
fun interface GrantManagerCheck {
    fun mayManageGrants(): Boolean

    companion object {
        /**
         * Fails closed: no caller may manage grants. Used until the permission catalogue names the permission it asks.
         */
        val DENY_ALL = GrantManagerCheck { false }
    }
}

/**
 * Whether the caller is the API gateway's service account: the only caller of getRevocationList and
 * getVerificationKeys.
 */
fun interface GatewayCaller {
    fun isGateway(): Boolean
}

/**
 * Re-verifies the request's workload token and compares its subject with the configured gateway service account, by
 * equality.
 */
class WorkloadGatewayCaller(
    private val request: HttpServletRequest,
    private val verifier: WorkloadTokenVerifier,
    private val gatewaySubject: String,
) : GatewayCaller {
    init {
        require(gatewaySubject.isNotBlank()) { "the gateway service account must be configured" }
    }

    override fun isGateway(): Boolean {
        val header = request.getHeader(WorkloadToken.HEADER_NAME)?.takeIf { it.isNotBlank() } ?: return false
        return (verifier.verify(WorkloadToken(header)) as? WorkloadVerification.Verified)?.subject == gatewaySubject
    }
}
