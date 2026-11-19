package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.PeriodErrorCode
import com.dynokore.platform.serviceruntime.http.ProblemResponse
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

/** The scoped permission key of `attestWorkPaper` (platform-contracts `catalogue/role-permissions`, kind WRITE). */
const val ATTEST_PERMISSION = "reconciliation.attest"

/** Decides whether [userId] holds [permission] for [entityRef] now, for the bound tenant. */
fun interface AttestAuthority {
    fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ): Boolean
}

/**
 * [AttestAuthority] over `AuthorityReplica.decide` (INV-Q-10027, INV-T-24459). One scoped permission is decided per
 * request; no role name is read. Only a LOCAL or FALLBACK decision that allows grants: a DEGRADED answer, a lag
 * beyond the bound, no replica and a revoked person all deny (deny by default, ASVS V4.1.1). An exception
 * propagates, so nothing is stored after a failed decision (fail closed).
 */
class AuthorityReplicaAttestAuthority internal constructor(
    private val decide: (AuthorizationRequest, Deadline) -> AuthorityDecision,
    private val tenants: TenantContext,
) : AttestAuthority {
    constructor(replica: AuthorityReplica, tenants: TenantContext) : this(replica::decide, tenants)

    override fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ): Boolean {
        val deadline = CurrentDeadline.current() ?: error("no call deadline is bound")
        val decision =
            decide(
                AuthorizationRequest(tenants.current(), Principal(userId, PrincipalKind.HUMAN), entityRef, permission),
                deadline,
            )
        return decision.allowed && decision.source in GRANTING
    }

    private companion object {
        val GRANTING = setOf(DecisionSource.LOCAL, DecisionSource.FALLBACK)
    }
}

/** The refusals of `attestWorkPaper`; no token is SRT-003, as in `getComplianceMetrics`. */
enum class AttestationRefusalCode {
    SEPARATION_OF_DUTIES,
    UNEXPLAINED_VARIANCE,
    ACCESS_DENIED,
    NOT_FOUND,
    UNAUTHENTICATED,
}

class AttestationRefusal(
    val code: AttestationRefusalCode,
) : RuntimeException(code.name)

/** ARC-19, CS-14: the one place an [AttestationRefusal] becomes the API-20 envelope, by the builder of its family. */
@RestControllerAdvice(assignableTypes = [RunsController::class])
class AttestationRefusalAdvice {
    @ExceptionHandler(AttestationRefusal::class)
    fun refuse(refusal: AttestationRefusal): ResponseEntity<String> {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val problem: ProblemResponse =
            when (refusal.code) {
                AttestationRefusalCode.SEPARATION_OF_DUTIES -> {
                    Problems.renderPeriod(PeriodErrorCode.ATTESTATION_SEPARATION_OF_DUTIES, correlation)
                }

                AttestationRefusalCode.UNEXPLAINED_VARIANCE -> {
                    Problems.renderPeriod(PeriodErrorCode.ATTESTATION_UNEXPLAINED_RESIDUAL, correlation)
                }

                AttestationRefusalCode.ACCESS_DENIED -> {
                    Problems.render(ClosedErrorCode.ACCESS_DENIED, correlation)
                }

                AttestationRefusalCode.NOT_FOUND -> {
                    Problems.render(ClosedErrorCode.NOT_FOUND, correlation)
                }

                AttestationRefusalCode.UNAUTHENTICATED -> {
                    Problems.render(ClosedErrorCode.UNAUTHENTICATED, correlation)
                }
            }
        val headers = HttpHeaders()
        problem.headers.forEach { (name, value) -> headers.set(name, value) }
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }
}
