package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.adapters.persistence.OverrideTimeSource
import com.dynokore.gst.submission.adapters.persistence.OverrideTimes
import com.dynokore.gst.submission.application.drain.DrainPeriod
import com.dynokore.gst.submission.application.drain.DrainRequest
import com.dynokore.gst.submission.application.drain.DrainResult
import com.dynokore.gst.submission.application.drain.PreviewDrain
import com.dynokore.gst.submission.application.drain.PreviewResult
import com.dynokore.gst.submission.application.drain.number
import com.dynokore.gst.submission.application.filing.ConfirmFiling
import com.dynokore.gst.submission.application.filing.ConfirmFilingResult
import com.dynokore.gst.submission.application.gate.ApproveOverride
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.gate.OverrideEntry
import com.dynokore.gst.submission.application.gate.OverrideResult
import com.dynokore.gst.submission.application.gate.OverrideStore
import com.dynokore.gst.submission.application.gate.ReadOverrides
import com.dynokore.gst.submission.application.gate.RequestOverride
import com.dynokore.gst.submission.application.gate.StoredOverride
import com.dynokore.gst.submission.application.readiness.GetLatestReadinessCheck
import com.dynokore.gst.submission.application.readiness.LatestCheckResult
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.contract.api.ReadinessApi
import com.dynokore.gst.submission.contract.model.ConfirmFilingRequest
import com.dynokore.gst.submission.contract.model.DrainResultDrainedByTierInner
import com.dynokore.gst.submission.contract.model.DrainResultResidualItemsInner
import com.dynokore.gst.submission.contract.model.FilingConfirmation
import com.dynokore.gst.submission.contract.model.FilingGateOverride
import com.dynokore.gst.submission.contract.model.FilingGateOverrideEntry
import com.dynokore.gst.submission.contract.model.FilingGateOverrideList
import com.dynokore.gst.submission.contract.model.FilingGateOverrideRequest
import com.dynokore.gst.submission.contract.model.IrreversibleImpact
import com.dynokore.gst.submission.contract.model.OutcomeValues
import com.dynokore.gst.submission.contract.model.PendingFilingGateOverride
import com.dynokore.gst.submission.contract.model.ReadinessCheckRecord
import com.dynokore.gst.submission.contract.model.ReadinessTier
import com.dynokore.gst.submission.domain.drain.Tier
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.gate.OverrideStatus
import com.dynokore.platform.outboxandqueue.EntityRef
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
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.jooq.exception.DataAccessException
import org.postgresql.util.PSQLException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import com.dynokore.gst.submission.application.filing.ConfirmFilingRequest as ConfirmFilingCall

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

/** The three use cases of the filing-gate override with what the controller reads around them (INV-T-10714). */
class FilingGateOverrides(
    val request: RequestOverride,
    val approve: ApproveOverride,
    val read: ReadOverrides,
    val store: OverrideStore,
    val checks: LatestChecks,
    val times: OverrideTimeSource,
)

/**
 * The readiness and filing operations of the submission contract (TPL-08). Every operation answers 501 until the
 * task named for it in `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * `getLatestReadinessCheck` (INV-REQ-500, INV-T-21141): the acting user is the verified principal only; the entity and
 * period come from the path; the refusals are the closed API-20 envelope of [ReadinessRefusalAdvice]
 * (SRT-001, SRT-004, SRT-005).
 */
@RestController
@Suppress("LongParameterList", "TooManyFunctions") // readiness, drain, filing and override operations
open class ReadinessController(
    private val latest: GetLatestReadinessCheck,
    private val http: ObjectProvider<HttpServletRequest>,
    private val preview: PreviewDrain,
    private val drain: DrainPeriod,
    private val confirm: ConfirmFiling,
    private val transactions: TransactionRunner,
    private val businessZone: ZoneId,
    private val overrides: FilingGateOverrides,
) : ReadinessApi {
    /**
     * INV-REQ-914, INV-REQ-917 (INV-AC-20947): the filing fact over HTTP. The use case decides the permission for every
     * caller kind; this binding converts the instant to the business-zone date and renders the dates back with the
     * start of the day, so a repeat answers its body unchanged. One transaction wraps the call (the store needs it).
     */
    override fun confirmFiling(
        entityRef: String,
        periodRef: String,
        confirmFilingRequest: ConfirmFilingRequest,
    ): ResponseEntity<FilingConfirmation> {
        val caller = filingCaller()
        if (confirmFilingRequest.irasAcknowledgementReference.isBlank()) {
            throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        }
        val call =
            ConfirmFilingCall(
                tenant = caller.first,
                principal = caller.second,
                entityRef = entityRef,
                periodRef = periodRef,
                filedAt = FilingBinding.toBusinessDate(confirmFilingRequest.filedAt, businessZone),
                acknowledgementRef = confirmFilingRequest.irasAcknowledgementReference,
                portalRef = confirmFilingRequest.portalReference,
                correction = confirmFilingRequest.correction ?: false,
            )
        val result = transactions.inTransaction { confirm.handle(call, deadline()) }
        if (result !is ConfirmFilingResult.Recorded) throw FilingBinding.refusalOf(result)
        val portal = confirmFilingRequest.portalReference
        return ResponseEntity.ok(FilingBinding.confirmation(call, portal, result, businessZone))
    }

    /**
     * INV-REQ-72 (INV-AC-20023): the first approval. One tenant transaction holds the store write and its audit entry.
     * A repeat for the same (entity, period, check) fails the insert on `filing_override_one_per_check`; only that
     * failure rolls the transaction back and reports the recorded override unchanged in a new transaction.
     */
    override fun overrideFilingGate(
        entityRef: String,
        periodRef: String,
        filingGateOverrideRequest: FilingGateOverrideRequest,
    ): ResponseEntity<FilingGateOverride> {
        val caller = overrideCaller(entityRef)
        val checkId = filingGateOverrideRequest.checkId
        val result =
            try {
                transactions.inTransaction {
                    overrides.request.execute(
                        caller.actor,
                        entityRef,
                        periodRef,
                        checkId,
                        filingGateOverrideRequest.reason,
                    )
                }
            } catch (e: DataAccessException) {
                if (!isRepeatKey(e)) throw e
                transactions.inTransaction { recorded(entityRef, periodRef, checkId) }
            }
        return when (result) {
            is OverrideResult.Released -> created(result.entry)
            is OverrideResult.AwaitsSecond -> accepted(result.entry)
            else -> throw overrideRefusal(result)
        }
    }

    /** INV-REQ-72: the second approval; the check ID names the override of the period. */
    override fun approveFilingGateOverride(
        entityRef: String,
        periodRef: String,
        checkId: String,
    ): ResponseEntity<FilingGateOverride> {
        val caller = overrideCaller(entityRef)
        val result =
            transactions.inTransaction {
                // An unknown check has no id: the use case answers 403 to a caller without the permission, else 404.
                val id =
                    overrides.store
                        .entries(entityRef, periodRef)
                        .firstOrNull { it.checkId == checkId }
                        ?.id
                        .orEmpty()
                overrides.approve.execute(caller.actor, entityRef, periodRef, id)
            }
        return (result as? OverrideResult.Released)?.let { created(it.entry) } ?: throw overrideRefusal(result)
    }

    /** INV-REQ-72: the overrides of one period, a page by check ID (API-18). */
    override fun getFilingGateOverrides(
        entityRef: String,
        periodRef: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<FilingGateOverrideList> {
        val caller = overrideCaller(entityRef)
        if (limit !in 1..MAX_LIMIT) throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        val after = cursor?.let { OverrideCursor.decode(it) }
        val (entries, instants) =
            transactions.inTransaction {
                val result = overrides.read.execute(caller.actor, entityRef, periodRef)
                val listed = (result as? OverrideResult.Listed)?.entries ?: throw overrideRefusal(result)
                listed to overrides.times.ofPeriod(entityRef, periodRef)
            }
        val remaining = entries.sortedBy { it.override.checkId }.filter { after == null || it.override.checkId > after }
        val page = remaining.take(limit)
        val next = if (remaining.size > limit) OverrideCursor.encode(page.last().override.checkId) else null
        return ResponseEntity.ok(FilingGateOverrideList(page.map { entryOf(it, timesOf(it.override, instants)) }, next))
    }

    /** The recorded override of a repeated request: released, or still pending. */
    private fun recorded(
        entityRef: String,
        periodRef: String,
        checkId: String,
    ): OverrideResult {
        val row =
            overrides.store.entries(entityRef, periodRef).firstOrNull { it.checkId == checkId }
                ?: return OverrideResult.NotFound
        val entry = OverrideEntry(row, row.status)
        return if (row.status == OverrideStatus.RELEASED) {
            OverrideResult.Released(entry)
        } else {
            OverrideResult.AwaitsSecond(entry)
        }
    }

    /** Only the unique violation of the one-per-check index is a repeat; any other failure propagates. */
    private fun isRepeatKey(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }
            .filterIsInstance<PSQLException>()
            .any { it.sqlState == UNIQUE_VIOLATION && it.serverErrorMessage?.constraint == REPEAT_KEY_INDEX }

    /** The instants of a stored override; a missing audit entry fails the read, no instant is invented. */
    private fun timesOf(
        row: StoredOverride,
        instants: Map<String, OverrideTimes>,
    ): OverrideTimes {
        val at = checkNotNull(instants[row.checkId]) { "the override has no requested audit entry" }
        check(row.status != OverrideStatus.RELEASED || at.releasedAt != null) {
            "the released override has no released audit entry"
        }
        return at
    }

    private fun created(entry: OverrideEntry): ResponseEntity<FilingGateOverride> {
        val row = entry.override
        val (instants, latest) =
            transactions.inTransaction {
                overrides.times.ofPeriod(row.entityRef, row.periodRef) to
                    overrides.checks.latest(row.entityRef, row.periodRef)
            }
        val at = timesOf(row, instants)
        val verdict = latest?.takeIf { it.checkId == row.checkId }?.verdict ?: CheckVerdict.NOT_READY
        return ResponseEntity.status(HttpStatus.CREATED).body(
            FilingGateOverride(
                entityRef = row.entityRef,
                periodRef = row.periodRef,
                checkId = row.checkId,
                verdict = FilingGateOverride.Verdict.valueOf(verdict.name),
                reason = row.reason,
                overriddenBy = row.releasedBy ?: row.requestedBy,
                overriddenAt = utc(at.releasedAt ?: at.requestedAt),
            ),
        )
    }

    /** 202 carries a [PendingFilingGateOverride]; the generated interface types the body as the 201 schema only. */
    @Suppress("UNCHECKED_CAST")
    private fun accepted(entry: OverrideEntry): ResponseEntity<FilingGateOverride> {
        val row = entry.override
        val at = timesOf(row, transactions.inTransaction { overrides.times.ofPeriod(row.entityRef, row.periodRef) })
        val body =
            PendingFilingGateOverride(
                entityRef = row.entityRef,
                periodRef = row.periodRef,
                checkId = row.checkId,
                reason = row.reason,
                requestedBy = row.requestedBy,
                requestedAt = utc(at.requestedAt),
                status = PendingFilingGateOverride.Status.PENDING,
            )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body) as ResponseEntity<FilingGateOverride>
    }

    /** API-24: an entityRef outside the token's current entity set is SRT-005 (the contract). */
    private fun overrideCaller(entityRef: String): EndUserPrincipal {
        val caller = caller()
        if (caller.entitySetCurrent && !caller.covers(EntityRef(entityRef))) {
            throw ComplianceRefusal(ClosedErrorCode.NOT_FOUND)
        }
        return caller
    }

    // A second approval that still awaits another approver has no answer in the contract: nothing is released, 403.
    private fun overrideRefusal(result: OverrideResult): ComplianceRefusal =
        ComplianceRefusal(
            when (result) {
                OverrideResult.BadRequest -> ClosedErrorCode.CONTRACT_VIOLATION
                OverrideResult.NotFound -> ClosedErrorCode.NOT_FOUND
                OverrideResult.Conflict -> ClosedErrorCode.STALE_AGGREGATE
                else -> ClosedErrorCode.ACCESS_DENIED
            },
        )

    /** The tenant and principal of the request: the machine attribute first, else the end user as a person. */
    private fun filingCaller(): Pair<TenantId, Principal> {
        val request = http.getObject()
        val machine = request.getAttribute(MachineTokenFilter.PRINCIPAL_ATTRIBUTE) as? Principal
        if (machine != null) {
            check(machine.kind == PrincipalKind.MACHINE) { "the machine attribute holds a non-machine principal" }
            val tenant = machine.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
            return tenant to machine
        }
        val caller = caller()
        val tenant = caller.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
        return tenant to Principal(caller.actor, PrincipalKind.HUMAN)
    }

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
        const val MAX_LIMIT = 500
        const val UNIQUE_VIOLATION = "23505"
        const val REPEAT_KEY_INDEX = "filing_override_one_per_check"
    }
}

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

private fun entryOf(
    entry: OverrideEntry,
    at: OverrideTimes,
): FilingGateOverrideEntry {
    val row = entry.override
    return FilingGateOverrideEntry(
        checkId = row.checkId,
        reason = row.reason,
        status = FilingGateOverrideEntry.Status.valueOf(entry.status.name),
        requestedBy = row.requestedBy,
        requestedAt = utc(at.requestedAt),
        releasedBy = row.releasedBy,
        releasedAt = at.releasedAt?.let { utc(it) },
    )
}

/** The cursor of the list is the last check ID of the page, URL-safe Base64 (API-18); no offset and no total. */
private object OverrideCursor {
    fun encode(checkId: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(checkId.toByteArray(StandardCharsets.UTF_8))

    fun decode(cursor: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
}
