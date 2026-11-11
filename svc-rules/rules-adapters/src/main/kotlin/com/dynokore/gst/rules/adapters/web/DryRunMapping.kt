package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.persistence.JooqMappingPackStore
import com.dynokore.gst.rules.adapters.persistence.ArmedPackLookup
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.DRY_RUN_DELTA
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.application.DryRunDeltaStore
import com.dynokore.gst.rules.application.DryRunOutcome
import com.dynokore.gst.rules.application.DryRunSampleRequest
import com.dynokore.gst.rules.application.DryRunStart
import com.dynokore.gst.rules.application.DryRunState
import com.dynokore.gst.rules.application.DryRunWindow
import com.dynokore.gst.rules.application.HistoricalSamplePort
import com.dynokore.gst.rules.application.LastCompletePeriodPort
import com.dynokore.gst.rules.application.PackEvaluationPort
import com.dynokore.gst.rules.application.PeriodCalendarUnavailableException
import com.dynokore.gst.rules.application.RunDryRun
import com.dynokore.gst.rules.application.RunDryRunCommand
import com.dynokore.gst.rules.application.SamplePage
import com.dynokore.gst.rules.application.SampleRequestPort
import com.dynokore.gst.rules.application.StoredDryRun
import com.dynokore.gst.rules.contract.model.DryRunChangeTypeTotal
import com.dynokore.gst.rules.contract.model.DryRunPeriodDelta
import com.dynokore.gst.rules.contract.model.DryRunRequest
import com.dynokore.gst.rules.contract.model.DryRunResult
import com.dynokore.gst.rules.contract.model.DryRunResultSampleInner
import com.dynokore.gst.rules.contract.model.DryRunStraddlingCreditNote
import com.dynokore.gst.rules.contract.model.DryRunSupplyTypeTotal
import com.dynokore.gst.rules.contract.model.ErrorEnvelope
import com.dynokore.gst.rules.contract.model.OperationState
import com.dynokore.gst.rules.contract.model.RulesDryRunStatus
import com.dynokore.gst.rules.domain.dryrun.DeltaListing
import com.dynokore.gst.rules.domain.dryrun.DryRunDelta
import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.time.OffsetDateTime

/** How far a `dryRun` got within the synchronous window (API-17): finished, or running on at its status resource. */
sealed interface DryRunStarted {
    val dryRunId: String

    data class Finished(
        override val dryRunId: String,
    ) : DryRunStarted

    data class Running(
        override val dryRunId: String,
    ) : DryRunStarted
}

/** The stored run with the columns of the status resource that [StoredDryRun] does not carry. */
data class DryRunRecord(
    val stored: StoredDryRun,
    val startedAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
)

/**
 * The `dryRun` and `getDryRun` calls (INV-T-4974; INV-REQ-772, INV-REQ-1303, INV-REQ-1352; INV-API-3473).
 *
 * The window of a call comes from the request body, or, for a call without a body, from the one call of
 * [LastCompletePeriodPort] before the command transaction opens (the period calendar is tenancy-svc's; rules-svc
 * computes no period). The command transaction writes the run row and `PackDryRunSampleRequested` together; the run
 * itself continues on the bounded background executor, in the background lane of the pool, and is waited for
 * [syncWait] only. Nothing canonical or submission is written.
 */
@Suppress("LongParameterList") // the composition root names the collaborators of the calls (TPL-18)
class DryRunCalls(
    private val runs: ObjectProvider<RunDryRun>,
    private val store: DryRunDeltaStore,
    private val requests: SampleRequestPort,
    private val periods: LastCompletePeriodPort,
    private val packs: JooqMappingPackStore,
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val executor: ExecutorService,
    private val capacity: Semaphore,
    private val sampleWait: Duration,
    private val syncWait: Duration,
) {
    private val log = LoggerFactory.getLogger(DryRunCalls::class.java)

    /** Starts the dry run of [packRef] for [window] (null: the last complete period) and waits [syncWait] for it. */
    fun start(
        packRef: String,
        window: DryRunWindow?,
    ): DryRunStarted {
        val run = runs.ifAvailable ?: throw unavailable("the historical sample is not available: no sample adapter")
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        val tenant = tenants.current()
        val target = transactions.inTransaction { targetOf(packRef, tenant) }
        val resolved = window ?: lastCompletePeriod(tenant, target.entityRef)
        if (!capacity.tryAcquire()) throw unavailable("the dry-run capacity of the service is in use")
        val future =
            try {
                val dryRunId = UUID.randomUUID().toString()
                val command = commandOf(dryRunId, tenant, target, resolved, actor)
                transactions.inTransaction {
                    store.begin(command.start)
                    requests.request(
                        DryRunSampleRequest(
                            dryRunId,
                            tenant,
                            target.entityRef,
                            resolved,
                            target.currentVersion,
                            target.candidateVersion,
                        ),
                    )
                }
                dryRunId to executor.submit { background(run, command, actor) }
            } catch (e: RuntimeException) {
                capacity.release()
                throw e
            }
        return wait(future)
    }

    private fun wait(started: Pair<String, Future<*>>): DryRunStarted =
        try {
            started.second.get(syncWait.toMillis(), TimeUnit.MILLISECONDS)
            DryRunStarted.Finished(started.first)
        } catch (_: TimeoutException) {
            DryRunStarted.Running(started.first)
        }

    private fun background(
        run: RunDryRun,
        command: RunDryRunCommand,
        actor: String,
    ) {
        try {
            tenants.bind(command.tenant, actor) {
                PoolLanes.inLane(PoolLane.BACKGROUND) {
                    try {
                        val outcome = run.run(command)
                        if (outcome is DryRunOutcome.Unavailable) log.warn("dry run {} ended unavailable", command.dryRunId)
                    } catch (e: RuntimeException) {
                        log.error("dry run {} failed: {}", command.dryRunId, e.javaClass.simpleName)
                        transactions.inTransaction { store.unavailable(command.dryRunId, "run failed: ${e.javaClass.simpleName}") }
                    }
                }
            }
        } finally {
            capacity.release()
        }
    }

    private class Target(
        val entityRef: String,
        val candidateVersion: String,
        val currentVersion: String,
    )

    private fun targetOf(
        packRef: String,
        tenant: TenantId,
    ): Target {
        val pack = packs.find(packRef) ?: throw checkNotNull(MappingPackMapping.refusalOf(com.dynokore.gst.rules.application.MappingPackResult.NotFound))
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val current =
            ArmedPackLookup(dsl).resolve(PackType(PACK_TYPE), tenant, today)
                ?: throw MappingRefusal(
                    CONFLICT,
                    ClosedErrorCode.STALE_AGGREGATE.code,
                    "Conflict with the recorded state",
                    "no mapping pack is in force to compare the candidate with",
                )
        return Target(pack.entityRef, pack.label, current.manifest.version.value)
    }

    private fun lastCompletePeriod(
        tenant: TenantId,
        entityRef: String,
    ): DryRunWindow {
        val period =
            try {
                periods.lastCompletePeriod(tenant, entityRef)
            } catch (e: PeriodCalendarUnavailableException) {
                throw unavailable("the period calendar is unavailable: ${e.reason}").also { it.addSuppressed(e) }
            } ?: throw unavailable("the entity has no complete period yet")
        return DryRunWindow(period, period, 1)
    }

    private fun commandOf(
        dryRunId: String,
        tenant: TenantId,
        target: Target,
        window: DryRunWindow,
        actor: String,
    ) = RunDryRunCommand(
        DryRunStart(
            dryRunId,
            tenant,
            PACK_TYPE,
            target.currentVersion,
            target.candidateVersion,
            actor,
            clock.instant(),
        ),
        window,
        sampleWait,
    )

    /** The stored run of [packRef] that the bound user started; null for any other call (INV-API-3473). */
    fun find(
        packRef: String,
        dryRunId: String,
    ): DryRunRecord? {
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        val id = runCatching { UUID.fromString(dryRunId) }.getOrNull() ?: return null
        val stored = store.find(dryRunId) ?: return null
        if (stored.startedBy != actor) return null
        val row =
            dsl
                .select(DRY_RUN_DELTA.STARTED_AT, DRY_RUN_DELTA.COMPLETED_AT)
                .from(DRY_RUN_DELTA)
                .join(PACK_REGISTRY)
                .on(
                    PACK_REGISTRY.TENANT_ID
                        .eq(DRY_RUN_DELTA.TENANT_ID)
                        .and(PACK_REGISTRY.PACK_TYPE.eq(DRY_RUN_DELTA.PACK_TYPE))
                        .and(PACK_REGISTRY.PACK_VERSION.eq(DRY_RUN_DELTA.PACK_VERSION)),
                ).where(DRY_RUN_DELTA.ID.eq(id))
                .and(PACK_REGISTRY.PACK_ID.eq(packRef))
                .fetchOne() ?: return null
        val startedAt = checkNotNull(row.get(DRY_RUN_DELTA.STARTED_AT)) { "dry_run_delta.started_at is NOT NULL" }
        return DryRunRecord(stored, startedAt, row.get(DRY_RUN_DELTA.COMPLETED_AT))
    }

    private companion object {
        const val PACK_TYPE = "MAPPING"
        const val CONFLICT = 409
    }
}

/** The window of a call as the request body states it (INV-REQ-1303); null for a call without a body. */
object DryRunWindows {
    fun of(request: DryRunRequest?): DryRunWindow? {
        request ?: return null
        val first = request.firstPeriodRef
        val last = request.lastPeriodRef
        val count = request.periodCount
        if (first.isBlank() || last.isBlank() || count < 1) throw badRequest()
        return DryRunWindow(first, last, count)
    }

    private fun badRequest() =
        checkNotNull(
            MappingPackMapping.refusalOf(
                com.dynokore.gst.rules.application.MappingPackResult
                    .Refused(com.dynokore.gst.rules.application.RefusalReason.INPUT_INVALID),
            ),
        )
}

/**
 * The ports of the run on the background thread: each call is its own transaction of the bound tenant, and the
 * `begin` of the run is a no-op because the command transaction already stored the run row with the sample request.
 */
class BackgroundDryRunStore(
    private val delegate: DryRunDeltaStore,
    private val transactions: TransactionRunner,
) : DryRunDeltaStore by delegate {
    override fun begin(start: DryRunStart) = Unit

    override fun complete(
        dryRunId: String,
        completedAt: Instant,
        delta: com.dynokore.gst.rules.domain.dryrun.DryRunDelta,
    ) = transactions.inTransaction { delegate.complete(dryRunId, completedAt, delta) }

    override fun unavailable(
        dryRunId: String,
        reason: String,
    ) = transactions.inTransaction { delegate.unavailable(dryRunId, reason) }

    override fun find(dryRunId: String): StoredDryRun? = transactions.inTransaction { delegate.find(dryRunId) }
}

class BackgroundSamplePort(
    private val delegate: HistoricalSamplePort,
    private val transactions: TransactionRunner,
) : HistoricalSamplePort {
    override fun pages(
        dryRunId: String,
        window: DryRunWindow,
        wait: Duration,
    ): List<SamplePage> = transactions.inTransaction { delegate.pages(dryRunId, window, wait) }

    override fun release(dryRunId: String) = transactions.inTransaction { delegate.release(dryRunId) }
}

class BackgroundEvaluation(
    private val delegate: PackEvaluationPort,
    private val transactions: TransactionRunner,
) : PackEvaluationPort {
    override fun evaluate(
        tenant: TenantId,
        packType: String,
        packVersion: String,
        document: SampledDocument,
    ): DocumentOutcome = transactions.inTransaction { delegate.evaluate(tenant, packType, packVersion, document) }

    override fun effectiveFrom(
        tenant: TenantId,
        packType: String,
        packVersion: String,
    ): LocalDate = transactions.inTransaction { delegate.effectiveFrom(tenant, packType, packVersion) }
}

/** The state of a stored run as the status resource names it (API-17: RUNNING, SUCCEEDED, FAILED). */
object DryRunStates {
    fun of(state: DryRunState): String =
        when (state) {
            DryRunState.RUNNING -> "RUNNING"
            DryRunState.COMPLETE -> "SUCCEEDED"
            DryRunState.SAMPLE_UNAVAILABLE -> "FAILED"
        }
}

/** The stored run as the contract types state it (INV-REQ-772; API-17, API-20). */
object DryRunMapping {
    private val SUPPLY_ORDER = DryRunSupplyTypeTotal.SupplyType.entries.map { it.value }
    private val ZERO = java.math.BigDecimal("0.00")

    /** The `result` of a SUCCEEDED run; null for a run that is still RUNNING or has FAILED. */
    fun resultOf(
        packRef: String,
        record: DryRunRecord,
    ): DryRunResult? {
        val delta = record.stored.delta ?: return null
        return DryRunResult(
            packRef = packRef,
            changedCount = delta.changedCount,
            sample = delta.sample.map(::sampleOf),
            changeTypeTotals =
                delta.changeTypeTotals.map {
                    DryRunChangeTypeTotal(
                        DryRunChangeTypeTotal.ChangeType.forValue(it.changeType.name),
                        it.documentCount,
                        java.math.BigDecimal(it.taxableValue),
                        java.math.BigDecimal(it.gstValue),
                        it.valuesAbsentCount,
                    )
                },
            supplyTypeTotals = supplyTotals(delta),
            byPeriod =
                delta.byPeriod.map {
                    DryRunPeriodDelta(
                        it.periodRef,
                        it.documentCount,
                        java.math.BigDecimal(it.taxableValue),
                        java.math.BigDecimal(it.gstValue),
                        it.valuesAbsentCount,
                    )
                },
            straddlingCreditNotes =
                delta.straddlingCreditNotes.map {
                    DryRunStraddlingCreditNote(
                        UUID.fromString(it.docUuid),
                        it.transactionDate,
                        checkNotNull(it.precedingDocumentDate) { "a straddling credit note has a preceding date" },
                        it.oldCategoryCode,
                        it.newCategoryCode,
                        java.math.BigDecimal(it.taxableValue),
                        java.math.BigDecimal(it.gstValue),
                        it.sgdValuesAbsent,
                    )
                },
        )
    }

    private fun sampleOf(listing: DeltaListing) =
        DryRunResultSampleInner(
            UUID.fromString(listing.docUuid),
            listing.oldCategoryCode,
            listing.newCategoryCode,
            java.math.BigDecimal(listing.taxableValue),
            java.math.BigDecimal(listing.gstValue),
            listing.supplyType?.let(DryRunResultSampleInner.SupplyType::forValue),
            listing.sgdValuesAbsent,
        )

    /**
     * One item for each supply type with a changed document, in the contract order (INV-Q-10703 B'); a document
     * flagged `sgdValuesAbsent` counts in `documentCount` and `valuesAbsentCount` and in neither sum. A changed
     * document with no supply type, or one the contract enum does not list, cannot be counted, so it is a fault and
     * the status read fails closed instead of answering totals that do not add up to `changedCount`.
     */
    private fun supplyTotals(delta: DryRunDelta): List<DryRunSupplyTypeTotal> =
        delta.documents
            .groupBy { checkNotNull(it.supplyType) { "changed document ${it.docUuid} has no supply type" } }
            .toSortedMap(compareBy({ SUPPLY_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
            .map { (type, docs) ->
                val counted = docs.filterNot { it.sgdValuesAbsent }
                DryRunSupplyTypeTotal(
                    DryRunSupplyTypeTotal.SupplyType.forValue(type),
                    docs.size,
                    counted.fold(ZERO) { a, d -> a + java.math.BigDecimal(d.taxableValue) },
                    counted.fold(ZERO) { a, d -> a + java.math.BigDecimal(d.gstValue) },
                    docs.count { it.sgdValuesAbsent },
                )
            }

    /** The status resource of [record]; a run that ended without a sample is FAILED with the 503 SRT-013 envelope. */
    fun statusOf(
        packRef: String,
        record: DryRunRecord,
        correlationId: String,
    ): RulesDryRunStatus {
        val stored = record.stored
        val code = ClosedErrorCode.SERVICE_NOT_READY
        val error =
            if (stored.state == DryRunState.SAMPLE_UNAVAILABLE) {
                ErrorEnvelope(
                    type = java.net.URI("https://errors.invoice.internal/${code.code}"),
                    title = "A dependency is unavailable",
                    status = code.httpStatus,
                    code = code.code,
                    correlationId = correlationId,
                    detail = stored.reason,
                )
            } else {
                null
            }
        return RulesDryRunStatus(
            dryRunId = stored.dryRunId,
            packRef = packRef,
            state = OperationState.forValue(DryRunStates.of(stored.state)),
            startedAt = record.startedAt,
            completedAt = record.completedAt,
            result = resultOf(packRef, record),
            error = error,
        )
    }
}
