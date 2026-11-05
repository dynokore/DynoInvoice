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
        /** Fails closed: no caller may manage grants. Never bound in production; [LiveGrantManagerCheck] is. */
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

/** Who made a call to an installation operation: the installer's workload, another identity, or nobody. */
sealed interface InstallerIdentity {
    data class Installer(
        val subject: String,
    ) : InstallerIdentity

    /** Authenticated as something other than the installer, an end user included: 403 SRT-004. */
    data object Other : InstallerIdentity

    /** No token, or a workload token that does not verify: 401 SRT-003. */
    data object Absent : InstallerIdentity
}

fun interface InstallerCaller {
    fun identify(): InstallerIdentity
}

/**
 * Re-verifies the request's workload token and compares its subject, by equality, with the subjects configured as
 * `deployment.operator` at INSTALLATION scope (`access.installation-roles`, INV-Q-8789 A; `x-operation.role` of
 * recordInstallationApproverFact). An end-user token is never the installer's.
 */
class WorkloadInstallerCaller(
    private val request: HttpServletRequest,
    private val verifier: WorkloadTokenVerifier,
    private val installers: Set<String>,
) : InstallerCaller {
    override fun identify(): InstallerIdentity {
        val header = request.getHeader(WorkloadToken.HEADER_NAME)?.takeIf { it.isNotBlank() }
        if (header == null) {
            val endUser = request.getHeader("Authorization")?.isNotBlank() == true
            return if (endUser) InstallerIdentity.Other else InstallerIdentity.Absent
        }
        val verified = verifier.verify(WorkloadToken(header)) as? WorkloadVerification.Verified
        return when {
            verified == null -> InstallerIdentity.Absent
            verified.subject in installers -> InstallerIdentity.Installer(verified.subject)
            else -> InstallerIdentity.Other
        }
    }
}
