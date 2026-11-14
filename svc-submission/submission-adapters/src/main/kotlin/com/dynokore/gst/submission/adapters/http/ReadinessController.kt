package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.application.drain.DrainPeriod
import com.dynokore.gst.submission.application.drain.DrainRequest
import com.dynokore.gst.submission.application.drain.DrainResult
import com.dynokore.gst.submission.application.drain.PreviewDrain
import com.dynokore.gst.submission.application.drain.PreviewResult
import com.dynokore.gst.submission.application.drain.number
import com.dynokore.gst.submission.application.readiness.GetLatestReadinessCheck
import com.dynokore.gst.submission.application.readiness.LatestCheckResult
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.contract.api.ReadinessApi
import com.dynokore.gst.submission.contract.model.DrainResultDrainedByTierInner
import com.dynokore.gst.submission.contract.model.DrainResultResidualItemsInner
import com.dynokore.gst.submission.contract.model.IrreversibleImpact
import com.dynokore.gst.submission.contract.model.OutcomeValues
import com.dynokore.gst.submission.contract.model.ReadinessCheckRecord
import com.dynokore.gst.submission.contract.model.ReadinessTier
import com.dynokore.gst.submission.domain.drain.Tier
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.identity.MachineTokenFilter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.ZoneOffset
import java.util.UUID

/** ARC-19, CS-14: the one place a [ComplianceRefusal] of [ReadinessController] becomes the API-20 envelope. */
@RestControllerAdvice(assignableTypes = [ReadinessController::class])
class ReadinessRefusalAdvice {
    @ExceptionHandler(ComplianceRefusal::class)
    fun refuse(refusal: ComplianceRefusal): ResponseEntity<String> {
        val problem =
            Problems.closed(
                refusal.code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
            )
        val headers = HttpHeaders()
        headers.set(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }
}

/**
 * The readiness and filing operations of the submission contract (TPL-08). Every operation answers 501 until the
 * task named for it in `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * `getLatestReadinessCheck` (INV-REQ-500, INV-T-21141): the acting user is the verified principal only; the entity and
 * period come from the path; the refusals are the closed API-20 envelope of [ReadinessRefusalAdvice]
 * (SRT-001, SRT-004, SRT-005).
 */
@RestController
open class ReadinessController(
    private val latest: GetLatestReadinessCheck,
    private val http: ObjectProvider<HttpServletRequest>,
    private val preview: PreviewDrain,
    private val drain: DrainPeriod,
) : ReadinessApi {
    /** INV-REQ-1012: the count and total value of the drain; the use case decides the permission (never a role). */
    override fun previewDrainPeriod(
        entityRef: String,
        periodRef: String,
    ): ResponseEntity<IrreversibleImpact> {
        val request = drainRequest(entityRef, periodRef)
        return when (val result = preview.run(request, deadline())) {
            is PreviewResult.Previewed -> {
                ResponseEntity.ok(IrreversibleImpact(result.documentCount, result.totalAmount))
            }

            is PreviewResult.Forbidden -> {
                throw ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            is PreviewResult.Unavailable -> {
                throw ComplianceRefusal(ClosedErrorCode.SERVICE_NOT_READY)
            }
        }
    }

    /** INV-REQ-554, INV-REQ-555: the drain of one entity and period for a person or the machine principal. */
    override fun drainPeriod(
        entityRef: String,
        periodRef: String,
    ): ResponseEntity<com.dynokore.gst.submission.contract.model.DrainResult> {
        val request = drainRequest(entityRef, periodRef)
        return when (val result = drain.run(request, deadline())) {
            is DrainResult.Drained -> ResponseEntity.ok(drained(result))
            is DrainResult.Forbidden -> throw ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
            is DrainResult.Unavailable -> throw ComplianceRefusal(ClosedErrorCode.SERVICE_NOT_READY)
        }
    }

    private fun deadline() = CurrentDeadline.current() ?: error("no call deadline is bound")

    /** The verified principal of the request: the machine attribute first, else the end user; neither is refused. */
    private fun drainRequest(
        entityRef: String,
        periodRef: String,
    ): DrainRequest {
        val request = http.getObject()
        val machine = request.getAttribute(MachineTokenFilter.PRINCIPAL_ATTRIBUTE) as? Principal
        if (machine != null) {
            val tenant: TenantId =
                machine.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
            check(machine.kind == PrincipalKind.MACHINE) { "the machine attribute holds a non-machine principal" }
            return DrainRequest(tenant, machine, entityRef, periodRef)
        }
        val caller = caller()
        val tenant = caller.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
        return DrainRequest(tenant, Principal(caller.actor, PrincipalKind.HUMAN), entityRef, periodRef)
    }

    private fun tierCode(tier: Tier) =
        when (tier) {
            Tier.WINDOW_OPEN -> DrainResultDrainedByTierInner.TierCode.SAFETY_MARGIN
            Tier.DRAINED_PERIOD -> DrainResultDrainedByTierInner.TierCode.PERIOD_BACKLOG
            Tier.ROUTINE -> DrainResultDrainedByTierInner.TierCode.ROUTINE
        }

    private fun drained(result: DrainResult.Drained) =
        com.dynokore.gst.submission.contract.model.DrainResult(
            entityRef = result.entityRef,
            periodRef = result.periodRef,
            drainedCount = result.drainedCount,
            remaining = result.remaining.map { ReadinessTier(tier = it.tier.number, blockedCount = it.blockedCount) },
            completedAt = result.completedAt.atOffset(ZoneOffset.UTC),
            drainedByTier =
                Tier.entries.map {
                    DrainResultDrainedByTierInner(
                        it.number,
                        tierCode(it),
                        result.drainedByTier[it] ?: 0,
                    )
                },
            residualItems =
                result.residualItems.map {
                    DrainResultResidualItemsInner(
                        UUID.fromString(it.docUuid),
                        it.version,
                        it.tier.number,
                        it.reasonCode,
                    )
                },
            checkId = result.checkId,
        )

    override fun getLatestReadinessCheck(
        entityRef: String,
        periodRef: String,
    ): ResponseEntity<ReadinessCheckRecord> {
        val caller = caller()
        return when (val result = latest.execute(caller.actor, entityRef, periodRef)) {
            is LatestCheckResult.Found -> ResponseEntity.ok(record(result.check))
            LatestCheckResult.NotFound -> throw ComplianceRefusal(ClosedErrorCode.NOT_FOUND)
            LatestCheckResult.Forbidden -> throw ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
    }

    /** The verified principal bound to a tenant; fail closed otherwise (as ComplianceMetricsController does). */
    private fun caller(): EndUserPrincipal {
        val principal =
            http.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
        return principal?.takeIf { it.tenant != null } ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
    }

    private fun record(check: StoredReadinessCheck) =
        ReadinessCheckRecord(
            checkId = check.checkId,
            verdict = ReadinessCheckRecord.Verdict.valueOf(check.verdict.name),
            checkedAt = check.checkedAt.atOffset(ZoneOffset.UTC),
            approver = check.approver,
            outcomeValues = check.outcomeValues?.let { MAPPER.readValue<OutcomeValues>(it) },
        )

    private companion object {
        val MAPPER = jacksonObjectMapper()
    }
}
