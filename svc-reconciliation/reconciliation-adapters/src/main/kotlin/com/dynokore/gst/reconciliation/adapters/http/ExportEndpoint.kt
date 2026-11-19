package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.gst.reconciliation.adapters.export.UnsupportedExportTextException
import com.dynokore.gst.reconciliation.application.run.GetRun
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportFormat
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportOutcome
import com.dynokore.gst.reconciliation.application.workpaper.export.ExportWorkPaper
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.ProblemResponse
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
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
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.util.UUID

/** The scoped permission key of `exportWorkPaper` (platform-contracts `catalogue/role-permissions`, kind READ). */
const val EXPORT_PERMISSION = "reconciliation.export"

/** Decides whether [userId] holds [permission] for [entityRef] now, for the bound tenant. */
fun interface ExportAuthority {
    fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ): Boolean
}

/**
 * [ExportAuthority] over `AuthorityReplica.decide` (INV-Q-10027, INV-T-24486). One scoped permission is decided per
 * request; no role name is read. Only a LOCAL or FALLBACK decision that allows grants: a DEGRADED answer, a lag
 * beyond the bound, no replica and a revoked person all deny (deny by default, ASVS V4.1.1). An exception
 * propagates, so nothing is delivered after a failed decision (fail closed).
 */
class AuthorityReplicaExportAuthority internal constructor(
    private val decide: (AuthorizationRequest, Deadline) -> AuthorityDecision,
    private val tenants: TenantContext,
) : ExportAuthority {
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

/**
 * The refusals of `exportWorkPaper`: no token SRT-003, denied SRT-004, no such run SRT-005, policy replica SRT-013,
 * text the PDF font cannot draw SRT-010.
 */
enum class ExportRefusal(
    val code: ClosedErrorCode,
) {
    UNAUTHENTICATED(ClosedErrorCode.UNAUTHENTICATED),
    ACCESS_DENIED(ClosedErrorCode.ACCESS_DENIED),
    NOT_FOUND(ClosedErrorCode.NOT_FOUND),
    NOT_READY(ClosedErrorCode.SERVICE_NOT_READY),
    INTERNAL(ClosedErrorCode.INTERNAL_ERROR),
}

/** ARC-19, CS-14: an [ExportRefusal] as the API-20 envelope, by the builder of its family. */
@Suppress("UNCHECKED_CAST")
fun <T : Any> exportRefusal(refusal: ExportRefusal): ResponseEntity<T> {
    val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
    val problem: ProblemResponse = Problems.render(refusal.code, correlation)
    val headers = HttpHeaders()
    problem.headers.forEach { (name, value) -> headers.set(name, value) }
    return ResponseEntity.status(problem.status).headers(headers).body(problem.body) as ResponseEntity<T>
}

/** The audit scope of an exported run: `run:<run id, lower-case canonical>/format:<format name>`. */
fun exportAuditScope(
    runId: UUID,
    format: String,
): String = "run:" + runId.toString().lowercase() + "/format:" + format

/** What [ExportDelivery] decided: an answer, or a step the controller must answer by the generated operation. */
sealed interface ExportStep {
    data class Answer(
        val response: ResponseEntity<Resource>,
    ) : ExportStep

    data object UnknownFormat : ExportStep
}

/**
 * The fail-closed order of `exportWorkPaper` after the principal is read (INV-T-24486): the run's entity (unknown run
 * or an entity outside the caller's current set: 404, no decision), the scoped permission (denied: 403, whatever the
 * format), then [ExportWorkPaper] for either format. The audit entry is written only for a delivered export, through
 * `AuditedOperations.read`, so the bytes are handed back only once it is written; a failed write propagates.
 */
class ExportDelivery(
    private val getRun: GetRun,
    private val transactions: TransactionRunner,
    private val tenants: TenantContext,
    private val export: ExportWorkPaper,
    private val authority: ExportAuthority,
    private val audited: AuditedOperations,
) {
    fun deliver(
        caller: EndUserPrincipal,
        runId: UUID,
        format: String,
    ): ExportStep {
        val tenant = tenants.current()
        val entityRef = visibleEntity(caller, tenant.toString(), runId)
        return when {
            entityRef == null -> {
                refused(ExportRefusal.NOT_FOUND)
            }

            !authority.holds(caller.actor, EXPORT_PERMISSION, entityRef) -> {
                refused(ExportRefusal.ACCESS_DENIED)
            }

            else -> {
                ExportFormat.entries
                    .firstOrNull { it.name == format }
                    ?.let { ExportStep.Answer(export(caller.actor, tenant, entityRef, runId, it)) }
                    ?: ExportStep.UnknownFormat
            }
        }
    }

    private fun refused(refusal: ExportRefusal) = ExportStep.Answer(exportRefusal(refusal))

    /** The run's entity when the caller may see it; unknown run or entity outside a current set: null (API-24). */
    private fun visibleEntity(
        caller: EndUserPrincipal,
        tenant: String,
        runId: UUID,
    ): String? {
        val entityRef = transactions.inTransaction { getRun.execute(tenant, runId.toString()) }?.run?.entityRef
        val visible = entityRef != null && (!caller.entitySetCurrent || caller.covers(EntityRef(entityRef)))
        return entityRef.takeIf { visible }
    }

    private fun export(
        actor: String,
        tenant: TenantId,
        entityRef: String,
        runId: UUID,
        format: ExportFormat,
    ): ResponseEntity<Resource> {
        val id = runId.toString().lowercase()
        val outcome =
            try {
                transactions.inTransaction { export.execute(tenant.toString(), id, format) }
            } catch (_: UnsupportedExportTextException) {
                // The message names a field and a row only; nothing of the export is logged or returned (ASVS V7).
                return exportRefusal(ExportRefusal.INTERNAL)
            }
        return when (outcome) {
            ExportOutcome.RunNotFound -> {
                exportRefusal(ExportRefusal.NOT_FOUND)
            }

            ExportOutcome.RelaxedControlsNotCurrent -> {
                exportRefusal(ExportRefusal.NOT_READY)
            }

            is ExportOutcome.Exported -> {
                audited.read(
                    AuditEntry(actor, "exportWorkPaper", exportAuditScope(runId, format.name)),
                    context(tenant, entityRef, id),
                ) {
                    ResponseEntity
                        .ok()
                        .contentType(MediaType.parseMediaType(outcome.rendered.contentType))
                        .body(ByteArrayResource(outcome.rendered.bytes))
                }
            }
        }
    }

    private fun context(
        tenant: TenantId,
        entityRef: String,
        runId: String,
    ): EventContext {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        return EventContext(
            tenant = tenant,
            entity = EntityRef(entityRef),
            docUuid = null,
            transactionDate = null,
            aggregateKey = runId,
            aggregateVersion = null,
            correlation = CorrelationId(correlation),
            causation = CausationId(causation),
        )
    }
}
