package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.persistence.ArmedPackLookup
import com.dynokore.gst.rules.adapters.persistence.JooqMappingPackStore
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.records.PackRegistryRecord
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.DRY_RUN_DELTA
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.application.DryRunDeltaStore
import com.dynokore.gst.rules.application.DryRunOutcome
import com.dynokore.gst.rules.application.DryRunPermissionPort
import com.dynokore.gst.rules.application.DryRunSampleRequest
import com.dynokore.gst.rules.application.DryRunStart
import com.dynokore.gst.rules.application.DryRunState
import com.dynokore.gst.rules.application.DryRunWindow
import com.dynokore.gst.rules.application.HistoricalSamplePort
import com.dynokore.gst.rules.application.LastCompletePeriodPort
import com.dynokore.gst.rules.application.NamedPackVersions
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
import com.dynokore.gst.rules.domain.dryrun.ChangeTypeTotal
import com.dynokore.gst.rules.domain.dryrun.DeltaListing
import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.DryRunComputation
import com.dynokore.gst.rules.domain.dryrun.DryRunDelta
import com.dynokore.gst.rules.domain.dryrun.PeriodDelta
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

/** The dry-run capacity is in use (503 SRT-013 with `Retry-After`); the controller maps it, nothing is written. */
class DryRunCapacityInUse(
    val retryAfterSeconds: Long,
) : RuntimeException("the dry-run capacity of the service is in use")

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
    private val permission: DryRunPermissionPort,
    private val runs: ObjectProvider<RunDryRun>,
    private val store: DryRunDeltaStore,
    private val requests: SampleRequestPort,
    periods: LastCompletePeriodPort,
    structure: ObjectProvider<StructureReplica>,
    packs: JooqMappingPackStore,
    dsl: DSLContext,
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val executor: ExecutorService,
    private val capacity: Semaphore,
    private val sampleWait: Duration,
    private val syncWait: Duration,
    retryAfter: Duration,
) {
    /** `rules.dry-run.retry-after` in whole seconds (rounded up): the `Retry-After` of the 202 and of the 503. */
    val retryAfterSeconds: Long = (retryAfter.toMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND

    private val log = LoggerFactory.getLogger(DryRunCalls::class.java)
    private val targets = DryRunTargets(packs, dsl, clock)
    private val planner = DryRunPlanner(periods, structure)
    private val dates = DryRunDates(dsl, tenants)

    /** Starts the dry run of [packRef] for [window] (null: the last complete period) and waits [syncWait] for it. */
    fun start(
        packRef: String,
        window: DryRunWindow?,
    ): DryRunStarted {
        // the scoped permission `pack.dry-run` is the first statement: before the sample adapter, the pack lookup
        // (so an unknown ref answers like a known one), the capacity and any write (INV-AC-22796)
        val actor = permitted()
        val run = runs.ifAvailable ?: throw unavailable("the historical sample is not available: no sample adapter")
        val tenant = tenants.current()
        val target = transactions.inTransaction { targets.of(packRef, tenant) }
        val plan = planner.plan(tenant, target.entityRef, window)
        if (plan.samples.isEmpty()) return unavailableRun(tenant, target, actor)
        if (!capacity.tryAcquire()) {
            throw DryRunCapacityInUse(retryAfterSeconds)
        }
        var submitted = false
        try {
            val dryRunId = UUID.randomUUID().toString()
            val command = commandOf(dryRunId, tenant, target, plan.window, actor)
            store(command, target, plan.samples)
            val future = executor.submit { background(run, command, actor) }
            submitted = true
            return wait(dryRunId to future)
        } finally {
            if (!submitted) capacity.release()
        }
    }

    /** The run of a tenant with no entity: stored and ended SAMPLE_UNAVAILABLE at once, never a complete report. */
    private fun unavailableRun(
        tenant: TenantId,
        target: DryRunTarget,
        actor: String,
    ): DryRunStarted {
        val dryRunId = UUID.randomUUID().toString()
        val command = commandOf(dryRunId, tenant, target, DryRunPlanner.EMPTY_WINDOW, actor)
        transactions.inTransaction {
            store.begin(command.start)
            store.unavailable(dryRunId, "sample unavailable: the tenant holds no entity")
        }
        return DryRunStarted.Finished(dryRunId)
    }

    /** The command transaction: the run row and the sample requests commit together, or neither exists. */
    private fun store(
        command: RunDryRunCommand,
        target: DryRunTarget,
        samples: List<EntitySample>,
    ) = transactions.inTransaction {
        store.begin(command.start)
        samples.forEach {
            requests.request(
                DryRunSampleRequest(
                    command.dryRunId,
                    command.tenant,
                    it.entityRef,
                    it.window,
                    target.currentVersion,
                    target.candidateVersion,
                ),
            )
        }
    }

    private fun wait(started: Pair<String, Future<*>>): DryRunStarted =
        try {
            started.second.get(syncWait.toMillis(), TimeUnit.MILLISECONDS)
            DryRunStarted.Finished(started.first)
        } catch (_: TimeoutException) {
            DryRunStarted.Running(started.first)
        }

    /**
     * The last-resort barrier of the background thread: whatever the run throws, the run row must not stay RUNNING, so
     * the fault ends it SAMPLE_UNAVAILABLE naming its class (fail closed) and is logged without its message.
     */
    @Suppress("TooGenericExceptionCaught") // the barrier of a background thread: nothing may escape and leave RUNNING
    private fun background(
        run: RunDryRun,
        command: RunDryRunCommand,
        actor: String,
    ) {
        try {
            tenants.bind(command.tenant, actor) {
                PoolLanes.inLane(PoolLane.BACKGROUND) {
                    try {
                        run.run(command)
                    } catch (e: RuntimeException) {
                        log.error("dry run {} failed: {}", command.dryRunId, e.javaClass.simpleName)
                        val reason = "run failed: ${e.javaClass.simpleName}"
                        transactions.inTransaction { store.unavailable(command.dryRunId, reason) }
                    }
                }
            }
        } finally {
            capacity.release()
        }
    }

    /** The acting user ID, once `pack.dry-run` is decided for it; a caller without the key is refused 403 SRT-004. */
    private fun permitted(): String {
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        if (!permission.mayDryRun(actor)) throw refusedDryRun()
        return actor
    }

    /** The 403 SRT-004 of a caller without the key; the detail names no person and no pack. */
    private fun refusedDryRun(): MappingRefusal {
        val denied = ClosedErrorCode.ACCESS_DENIED
        return MappingRefusal(denied.httpStatus, denied.code, denied.title, "the dry run is not permitted")
    }

    private fun commandOf(
        dryRunId: String,
        tenant: TenantId,
        target: DryRunTarget,
        window: DryRunWindow,
        actor: String,
    ) = RunDryRunCommand(
        DryRunStart(
            dryRunId,
            tenant,
            target.packType,
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
        val id = runCatching { UUID.fromString(dryRunId) }.getOrNull()
        val stored = id?.let { store.find(dryRunId) }?.takeIf { it.startedBy == actor }
        val row = stored?.let { dates.of(id, packRef) }
        return row?.let {
            val startedAt = checkNotNull(it.get(DRY_RUN_DELTA.STARTED_AT)) { "dry_run_delta.started_at is NOT NULL" }
            DryRunRecord(checkNotNull(stored), startedAt, it.get(DRY_RUN_DELTA.COMPLETED_AT))
        }
    }
}

/** A regulatory `packRef`: `<packType>@<version>`, split at the first `@`, both parts non-blank (INV-Q-23902). */
class RegulatoryRef private constructor(
    val packType: String,
    val version: String,
) {
    companion object {
        fun parse(packRef: String): RegulatoryRef? {
            val at = packRef.indexOf('@')
            if (at < 0) return null
            val type = packRef.substring(0, at)
            val version = packRef.substring(at + 1)
            return if (type.isBlank() || version.isBlank()) null else RegulatoryRef(type, version)
        }
    }
}

/** What a call samples and compares: [entityRef] is the one entity of a mapping pack, null for a regulatory pack. */
class DryRunTarget(
    val packType: String,
    val entityRef: String?,
    val candidateVersion: String,
    val currentVersion: String,
)

/**
 * The pack a call names. A mapping pack of the bound tenant (INV-T-4974) or a staged regulatory pack
 * `<packType>@<version>` (INV-T-20275, INV-Q-23902); both at once is 409 SRT-008, neither is 404.
 */
class DryRunTargets(
    private val packs: JooqMappingPackStore,
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    fun of(
        packRef: String,
        tenant: TenantId,
    ): DryRunTarget {
        val mapping = packs.find(packRef)
        val regulatory = RegulatoryRef.parse(packRef)?.let { registryRow(it) }
        if (mapping != null && regulatory != null) {
            throw conflict("the pack reference names a mapping pack and a regulatory pack")
        }
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        return when {
            mapping != null -> forMapping(mapping, tenant, today)
            regulatory != null -> forRegulatory(regulatory, today)
            else -> throw notFound()
        }
    }

    private fun forMapping(
        mapping: com.dynokore.gst.rules.application.MappingPackDraft,
        tenant: TenantId,
        today: LocalDate,
    ): DryRunTarget {
        val current = inForce(MAPPING, tenant, today, "no mapping pack is in force to compare the candidate with")
        return DryRunTarget(MAPPING, mapping.entityRef, mapping.label, current)
    }

    private fun forRegulatory(
        row: PackRegistryRecord,
        today: LocalDate,
    ): DryRunTarget {
        if (row.status != STAGED) throw conflict("the regulatory pack is not staged")
        val type = checkNotNull(row.packType) { "pack_registry.pack_type is NOT NULL" }
        val label = checkNotNull(row.packVersion) { "pack_registry.pack_version is NOT NULL" }
        val detail = "no regulatory pack is in force to compare the candidate with"
        return DryRunTarget(type, null, label, inForce(type, null, today, detail))
    }

    /** The version in force for [type]; [tenant] null reads the platform ARMED row only. */
    private fun inForce(
        type: String,
        tenant: TenantId?,
        today: LocalDate,
        detail: String,
    ): String =
        ArmedPackLookup(dsl)
            .resolve(PackType(type), tenant, today)
            ?.manifest
            ?.version
            ?.value
            ?: throw conflict(detail)

    private fun conflict(detail: String) =
        MappingRefusal(CONFLICT, ClosedErrorCode.STALE_AGGREGATE.code, "Conflict with the recorded state", detail)

    private fun notFound() =
        checkNotNull(MappingPackMapping.refusalOf(com.dynokore.gst.rules.application.MappingPackResult.NotFound))

    /** The platform REGULATORY registry row whose type and version equal the parts of [ref], case-sensitively. */
    private fun registryRow(ref: RegulatoryRef) =
        dsl
            .selectFrom(PACK_REGISTRY)
            .where(PACK_REGISTRY.PACK_TYPE.eq(ref.packType))
            .and(PACK_REGISTRY.PACK_VERSION.eq(ref.version))
            .and(PACK_REGISTRY.TENANT_ID.eq(TenantId.PLATFORM.value))
            .and(PACK_REGISTRY.LAYER.eq(REGULATORY))
            .fetchOne()

    private companion object {
        const val MAPPING = "MAPPING"
        const val CONFLICT = 409
        const val STAGED = "STAGED"
        const val REGULATORY = "REGULATORY"
    }
}

/** One entity of the sample and the window asked for it. */
class EntitySample(
    val entityRef: String,
    val window: DryRunWindow,
)

/** The entities to sample and the window of the run; no entity at all is an empty plan (fail closed). */
class DryRunPlan(
    val samples: List<EntitySample>,
    val window: DryRunWindow,
)

/**
 * A mapping pack samples the one entity it names. A regulatory pack samples exactly the entities the structure replica
 * holds for the bound tenant (owner ruling 2026-11-16): one request per entity, each with the window of that entity.
 * Every period calendar is read here, before any write, so a refusal 503 leaves no run row.
 */
class DryRunPlanner(
    private val periods: LastCompletePeriodPort,
    private val structure: ObjectProvider<StructureReplica>,
) {
    fun plan(
        tenant: TenantId,
        mappingEntity: String?,
        window: DryRunWindow?,
    ): DryRunPlan {
        if (mappingEntity != null) {
            val resolved = window ?: lastCompletePeriod(tenant, mappingEntity)
            return DryRunPlan(listOf(EntitySample(mappingEntity, resolved)), resolved)
        }
        val replica = structure.ifAvailable ?: throw unavailable("the entity structure is not available")
        val samples =
            replica
                .entitiesOf(tenant)
                .keys
                .sorted()
                .map { EntitySample(it, window ?: lastCompletePeriod(tenant, it)) }
        return DryRunPlan(samples, samples.firstOrNull()?.window ?: EMPTY_WINDOW)
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

    companion object {
        val EMPTY_WINDOW = DryRunWindow("", "", 0)
    }
}

/** The two dates of a run, read for the pack reference of the call. */
class DryRunDates(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) {
    /**
     * A regulatory reference: the run belongs to the starting tenant and the registry row to the platform tenant, so
     * there is no join; the run's own type and version must equal the parts of the reference. A mapping reference:
     * only when the run belongs to the pack [packRef] of the bound tenant.
     */
    fun of(
        id: UUID,
        packRef: String,
    ) = RegulatoryRef.parse(packRef)?.let { regulatory(id, it) } ?: mapping(id, packRef)

    private fun regulatory(
        id: UUID,
        ref: RegulatoryRef,
    ) = dsl
        .select(DRY_RUN_DELTA.STARTED_AT, DRY_RUN_DELTA.COMPLETED_AT)
        .from(DRY_RUN_DELTA)
        .where(DRY_RUN_DELTA.ID.eq(id))
        .and(DRY_RUN_DELTA.TENANT_ID.eq(tenants.current().value))
        .and(DRY_RUN_DELTA.PACK_TYPE.eq(ref.packType))
        .and(DRY_RUN_DELTA.PACK_VERSION.eq(ref.version))
        .fetchOne()

    private fun mapping(
        id: UUID,
        packRef: String,
    ) = dsl
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
        .fetchOne()
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
            changeTypeTotals = delta.changeTypeTotals.map(::changeTypeOf),
            supplyTypeTotals = supplyTotals(delta),
            byPeriod = delta.byPeriod.map(::periodOf),
            straddlingCreditNotes = delta.straddlingCreditNotes.map(::straddlingOf),
        )
    }

    private fun changeTypeOf(total: ChangeTypeTotal) =
        DryRunChangeTypeTotal(
            DryRunChangeTypeTotal.ChangeType.forValue(total.changeType.name),
            total.documentCount,
            java.math.BigDecimal(total.taxableValue),
            java.math.BigDecimal(total.gstValue),
            total.valuesAbsentCount,
        )

    private fun periodOf(period: PeriodDelta) =
        DryRunPeriodDelta(
            period.periodRef,
            period.documentCount,
            java.math.BigDecimal(period.taxableValue),
            java.math.BigDecimal(period.gstValue),
            period.valuesAbsentCount,
        )

    private fun straddlingOf(note: DeltaListing) =
        DryRunStraddlingCreditNote(
            UUID.fromString(note.docUuid),
            note.transactionDate,
            checkNotNull(note.precedingDocumentDate) { "a straddling credit note has a preceding date" },
            note.oldCategoryCode,
            note.newCategoryCode,
            java.math.BigDecimal(note.taxableValue),
            java.math.BigDecimal(note.gstValue),
            note.sgdValuesAbsent,
        )

    private fun sampleOf(listing: DeltaListing) =
        DryRunResultSampleInner(
            UUID.fromString(listing.docUuid),
            listing.oldCategoryCode,
            listing.newCategoryCode,
            java.math.BigDecimal(listing.taxableValue),
            java.math.BigDecimal(listing.gstValue),
            listing.supplyType,
            listing.sgdValuesAbsent,
        )

    /** The totals come from the domain (INV-Q-10703 B'); the supply type string is passed through unchanged. */
    private fun supplyTotals(delta: DryRunDelta): List<DryRunSupplyTypeTotal> =
        DryRunComputation.supplyTypeTotals(delta.documents).map {
            DryRunSupplyTypeTotal(
                it.supplyType,
                it.documentCount,
                java.math.BigDecimal(it.taxableValue),
                java.math.BigDecimal(it.gstValue),
                it.valuesAbsentCount,
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

/** [NamedPackVersions] over `rules.pack_registry`: the tenant's own version or the platform's, any status. */
class RegistryNamedPackVersions(
    private val dsl: DSLContext,
) : NamedPackVersions {
    override fun find(
        tenant: TenantId,
        packType: PackType,
        label: String,
    ): PackVersion? {
        val row =
            dsl
                .selectFrom(PACK_REGISTRY)
                .where(PACK_REGISTRY.PACK_TYPE.eq(packType.value))
                .and(PACK_REGISTRY.PACK_VERSION.eq(label))
                .and(PACK_REGISTRY.TENANT_ID.`in`(listOf(tenant.value, TenantId.PLATFORM.value)))
                .orderBy(DSL.`when`(PACK_REGISTRY.TENANT_ID.eq(TenantId.PLATFORM.value), 1).otherwise(0))
                .limit(1)
                .fetchOne() ?: return null
        val owner = checkNotNull(row.tenantId)
        return PackVersion(
            PackManifest(
                packId = PackId(checkNotNull(row.packId)),
                packType = packType,
                version = PackVersionLabel(checkNotNull(row.packVersion)),
                effectiveFrom = row.effectiveFrom ?: LocalDate.MAX,
                effectiveTo = row.effectiveTo,
                minEngineVersion = checkNotNull(row.minEngineVersion),
            ),
            tenant = if (owner == TenantId.PLATFORM.value) null else TenantId(owner),
        )
    }
}

private const val MILLIS_PER_SECOND = 1000L
