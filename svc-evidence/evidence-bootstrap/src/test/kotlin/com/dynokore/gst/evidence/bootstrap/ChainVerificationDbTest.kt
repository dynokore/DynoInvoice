package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.jobs.ChainVerificationJobBody
import com.dynokore.gst.evidence.adapters.jobs.VerificationEntitySource
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqAppliedStore
import com.dynokore.gst.evidence.adapters.persistence.JooqChainReads
import com.dynokore.gst.evidence.adapters.persistence.JooqChainTails
import com.dynokore.gst.evidence.adapters.persistence.JooqDigestReads
import com.dynokore.gst.evidence.adapters.persistence.JooqIntegrityStore
import com.dynokore.gst.evidence.adapters.persistence.JooqPeriodDigestStore
import com.dynokore.gst.evidence.adapters.persistence.ObjectStoreDigestCopies
import com.dynokore.gst.evidence.adapters.persistence.ObjectStoreDigestObjects
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.IntegrityFailure.Companion.INTEGRITY_FAILURE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.IntegrityRun.Companion.INTEGRITY_RUN
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.Outbox.Companion.OUTBOX
import com.dynokore.gst.evidence.application.chain.AppliedRecord
import com.dynokore.gst.evidence.application.digest.FiledReturn
import com.dynokore.gst.evidence.application.digest.MaxRetention
import com.dynokore.gst.evidence.application.digest.PeriodDigestRecorder
import com.dynokore.gst.evidence.application.integrity.ChainVerification
import com.dynokore.gst.evidence.application.integrity.ChainVerificationJob
import com.dynokore.gst.evidence.application.integrity.ChainVerificationRecorder
import com.dynokore.gst.evidence.application.integrity.IntegrityRunRow
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQuery
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.application.integrity.VerificationSources
import com.dynokore.gst.evidence.domain.chain.ChainKey
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.gst.evidence.domain.integrity.IntegrityState
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.gst.evidence.domain.integrity.evidenceType
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.evidencestore.ChainVerificationResult
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.evidencestore.EvidenceType
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.RetentionPeriod
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.jobs.FixedDeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRunContext
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.serviceruntime.jobs.JobTrigger
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.context.ApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef
import software.amazon.awssdk.services.s3.model.NoSuchKeyException as S3NoSuchKey

/**
 * INV-AC-7255 (its last step), INV-AC-7256 and INV-AC-12970 over PostgreSQL, through the TPL-18 context (P109): the
 * job body of `evidence-chain-verification` runs against fixed chains, a period digest and an in-memory object store.
 * Chains are altered outside the service with a superuser connection, as an attacker with database access would. The
 * key of INV-AC-12970 is in no row of ac-keys.tsv, so the status test does not carry it in its name.
 */
class ChainVerificationDbTest {
    private val actor = "svc:evidence-svc"
    private val entity = "ENT-3F2A9C"
    private val lookAlike = "ENT-3F2A9D"
    private val period = "2026-Q1"
    private val filedAt = Instant.parse("2026-04-15T08:00:00Z")
    private val json = JsonMapper.builder().build()

    private class Objects : ObjectStore {
        val stored = mutableMapOf<String, ByteArray>()
        var readFailure: RuntimeException? = null

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            stored[key.value] = bytes
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray {
            readFailure?.let { throw it }
            return stored[key.value] ?: throw S3NoSuchKey.builder().message("no such key").build()
        }
    }

    private inner class Boundary(
        val context: ApplicationContext,
        val version: EvidenceDb.Version,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val outbox: OutboxWriter = context.getBean(OutboxWriter::class.java)
        val objects = Objects()
        val ledgers: Map<RecordKind, EvidenceLedger> =
            RecordKind.entries.associateWith { JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, it.table) }

        fun verification(ledgers: Map<RecordKind, EvidenceLedger> = this.ledgers) =
            ChainVerification(
                { t, e, p, kind -> ledgers.getValue(kind).verifyChain(t, EntityRef(e), PeriodRef(p)) },
                VerificationSources(JooqChainReads(dsl), JooqDigestReads(dsl), ObjectStoreDigestCopies(objects)),
            )

        fun recorder(verification: ChainVerification = verification()) =
            ChainVerificationRecorder(verification, JooqChainReads(dsl), JooqIntegrityStore(dsl), outbox)

        fun body(recorder: ChainVerificationRecorder = recorder()) =
            ChainVerificationJobBody("evidence-svc", scope, recorder)

        /** Runs the job body for one entity the way the job runtime does: no binding, no transaction around it. */
        fun runJob(
            tenant: TenantId,
            forEntity: String = entity,
            body: ChainVerificationJobBody = body(),
        ) = body.run(
            JobRunContext(
                ChainVerificationJob.DEFINITION,
                JobTarget.Entity(tenant, EventEntityRef(forEntity)),
                JobTrigger.SCHEDULED,
                Instant.now(),
                1,
            ) { _, _ -> false },
        )

        fun <T> tx(
            tenant: TenantId,
            block: () -> T,
        ): T = scope.bind(tenant, actor) { runner.inTransaction { block() } }

        /** Appends [count] records to a chain and marks each as applied, as the record consumers do. */
        fun fill(
            tenant: TenantId,
            kind: RecordKind,
            type: EvidenceType,
            count: Int,
            forEntity: String = entity,
        ) = tx(tenant) {
            repeat(count) {
                val record =
                    ledgers
                        .getValue(kind)
                        .append(tenant, EntityRef(forEntity), PeriodRef(period), type, """{"n":$it}""".toByteArray())
                JooqAppliedStore(dsl).insert(
                    tenant,
                    AppliedRecord(
                        "ev-${kind.table}-$forEntity-$it",
                        kind,
                        ChainKey(forEntity, period),
                        record.sequence,
                        Instant.now(),
                    ),
                )
            }
        }

        /** The chains of the first acceptance criterion: 3 payload, [acks] acknowledgement and 3 trace records. */
        fun chains(
            tenant: TenantId,
            acks: Int = 2,
            forEntity: String = entity,
        ) {
            fill(tenant, RecordKind.EVIDENCE_PAYLOAD, EvidenceType.PAYLOAD, 3, forEntity)
            fill(tenant, RecordKind.ACKNOWLEDGEMENT, EvidenceType.ACKNOWLEDGEMENT, acks, forEntity)
            fill(tenant, RecordKind.DECISION_TRACE, EvidenceType.DECISION_TRACE, 3, forEntity)
        }

        fun digest(
            tenant: TenantId,
            forEntity: String = entity,
        ) = tx(tenant) {
            PeriodDigestRecorder(
                JooqChainTails(dsl),
                JooqPeriodDigestStore(dsl),
                ObjectStoreDigestObjects(objects),
                MaxRetention { RetentionPeriod(10) },
            ).record(tenant, FiledReturn("ret-$forEntity", forEntity, period, 1, filedAt))
        }

        /** Alters stored rows outside the service: the append-only trigger of [table] is off for the statement. */
        fun tamper(
            table: String,
            statement: String,
        ) = version.superuser().use { c ->
            c.createStatement().use {
                it.execute("alter table evidence.$table disable trigger user")
                it.execute(statement)
                it.execute("alter table evidence.$table enable trigger user")
            }
        }

        fun runs(tenant: TenantId) = tx(tenant) { dsl.fetch(INTEGRITY_RUN).sortedBy { it.seq } }

        fun failures(tenant: TenantId) = tx(tenant) { dsl.fetch(INTEGRITY_FAILURE).sortedBy { it.seq } }

        fun events(tenant: TenantId) =
            tx(tenant) { dsl.fetch(OUTBOX).filter { it.type!!.endsWith("EvidenceIntegrityFailed.v1") } }

        fun status(
            tenant: TenantId,
            forEntity: String = entity,
        ) = tx(tenant) {
            IntegrityStatusQueryHandler(JooqIntegrityStore(dsl)).handle(tenant, IntegrityStatusQuery(forEntity, period))
        }
    }

    private fun run(
        dir: Path,
        version: EvidenceDb.Version = EvidenceDb.Version.PG18,
        block: Boundary.(TenantId) -> Unit,
    ) {
        EvidenceContext.online(dir).withPropertyValues("spring.datasource.url=${version.jdbcUrl}").run { context ->
            Boundary(context, version).block(TenantId(UUID.randomUUID()))
        }
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `INV-AC-7255 intact chains and a digest give one VERIFIED run of chain length 8 with the digest`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) { tenant ->
        chains(tenant)
        val digest = digest(tenant)
        runJob(tenant)
        val row = runs(tenant).single()
        row.result shouldBe "VERIFIED"
        row.chainLength shouldBe 8L
        row.periodDigest shouldBe digest.digest
        row.periodRef shouldBe period
        failures(tenant).size shouldBe 0
        events(tenant).size shouldBe 0
        val status = status(tenant)
        status.result shouldBe IntegrityState.VERIFIED
        status.chainLength shouldBe 8L
        status.periodDigest shouldBe digest.digest
        status.lastRunAt shouldBe row.runAt!!.toInstant()
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `INV-AC-7256 an altered acknowledgement record opens one failure and one event, a repeat adds nothing`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) { tenant ->
        chains(tenant, acks = 3)
        digest(tenant)
        tamper("acknowledgement", "update evidence.acknowledgement set content = '\\x7b7d' where seq = 1")
        runJob(tenant)

        val failure = failures(tenant).single()
        failure.kind shouldBe "BROKEN_CHAIN"
        failure.failingSeq shouldBe 1L
        failure.evidenceType shouldBe "ACKNOWLEDGEMENT"
        failure.entityRef shouldBe entity
        failure.periodRef shouldBe period
        val recorded =
            tx(tenant) {
                ledgers
                    .getValue(RecordKind.ACKNOWLEDGEMENT)
                    .findByEntityAndPeriod(tenant, EntityRef(entity), PeriodRef(period))
            }.map { it.recordedAt }
        failure.rangeFrom!!.toInstant() shouldBe recorded.min()
        failure.rangeTo!!.toInstant() shouldBe recorded.max()
        val run = runs(tenant).single()
        run.result shouldBe "FAILED"

        val event = events(tenant).single()
        val data = json.readTree(event.data!!.data())
        data["range"]["from"].asString() shouldBe recorded.min().toString()
        data["range"]["to"].asString() shouldBe recorded.max().toString()
        val record = data["failingRecord"]
        record["entityRef"].asString() shouldBe entity
        record["periodRef"].asString() shouldBe period
        record["evidenceType"].asString() shouldBe "ACKNOWLEDGEMENT"
        record["sequence"].asLong() shouldBe 1L
        record["kind"].asString() shouldBe "BROKEN_CHAIN"
        data["failingRecord"].propertyNames().asSequence().toSet() shouldBe
            setOf("entityRef", "periodRef", "evidenceType", "sequence", "kind")

        runJob(tenant) // again
        failures(tenant).size shouldBe 1
        events(tenant).size shouldBe 1
        runs(tenant).map { it.result } shouldBe listOf("FAILED", "FAILED")
        failures(tenant).single().seq shouldBe failure.seq // never rewritten
        status(tenant).result shouldBe IntegrityState.FAILED
    }

    @Test
    fun `a look-alike entity stays VERIFIED and the same failure in another tenant opens its own row`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val other = TenantId(UUID.randomUUID())
        listOf(tenant, other).forEach {
            chains(it, acks = 3)
            chains(it, acks = 3, forEntity = lookAlike)
        }
        tamper(
            "acknowledgement",
            "update evidence.acknowledgement set content = '\\x7b7d' where seq = 1 and entity_ref = '$entity'",
        )
        runJob(tenant)
        runJob(tenant, lookAlike)
        runJob(other)
        runJob(other, lookAlike)
        failures(tenant).map { it.entityRef } shouldBe listOf(entity)
        failures(other).map { it.entityRef } shouldBe listOf(entity)
        runs(tenant).map { it.entityRef to it.result } shouldBe listOf(entity to "FAILED", lookAlike to "VERIFIED")
        runs(other).map { it.entityRef to it.result } shouldBe listOf(entity to "FAILED", lookAlike to "VERIFIED")
        events(tenant).size shouldBe 1
        events(other).size shouldBe 1
        status(tenant, lookAlike).result shouldBe IntegrityState.VERIFIED
    }

    @Test
    fun `a recorded tail hash that differs from the record at length minus 1 is a DIGEST_MISMATCH`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        chains(tenant)
        digest(tenant)
        tamper(
            "period_digest_chain",
            "update evidence.period_digest_chain set tail_hash = repeat('a', 64) where evidence_type = 'PAYLOAD'",
        )
        runJob(tenant)
        failures(tenant).any {
            it.kind == "DIGEST_MISMATCH" && it.evidenceType == "PAYLOAD" && it.failingSeq == null
        } shouldBe true
        // the altered row also differs from the object copy, which the copy check reports on its own
        failures(tenant).map { it.kind }.toSet() shouldBe setOf("DIGEST_MISMATCH", "RETAINED_COPY_MISMATCH")
        runs(tenant).single().result shouldBe "FAILED"
        events(tenant).size shouldBe failures(tenant).size
    }

    @Test
    fun `a chain shorter than the recorded length is a DIGEST_MISMATCH`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        chains(tenant)
        digest(tenant)
        tamper("acknowledgement", "delete from evidence.acknowledgement where seq = 1")
        runJob(tenant)
        failures(tenant).map { it.kind to it.evidenceType } shouldBe listOf("DIGEST_MISMATCH" to "ACKNOWLEDGEMENT")
        runs(tenant).single().result shouldBe "FAILED"
    }

    @Test
    fun `a missing object copy is a RETAINED_COPY_MISMATCH`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        chains(tenant)
        digest(tenant)
        objects.stored.clear()
        runJob(tenant)
        val rows = failures(tenant)
        rows.map { it.kind } shouldBe List(3) { "RETAINED_COPY_MISMATCH" }
        rows.map { it.evidenceType!! }.sorted() shouldBe listOf("ACKNOWLEDGEMENT", "DECISION_TRACE", "PAYLOAD")
        rows.all { it.failingSeq == null } shouldBe true
        rows.forEach {
            val kind = RecordKind.entries.single { k -> k.evidenceType == it.evidenceType }
            val recorded =
                tx(tenant) {
                    ledgers.getValue(kind).findByEntityAndPeriod(tenant, EntityRef(entity), PeriodRef(period))
                }.map { r -> r.recordedAt }
            it.rangeFrom!!.toInstant() shouldBe recorded.min()
            it.rangeTo!!.toInstant() shouldBe recorded.max()
        }
        runs(tenant).single().result shouldBe "FAILED"
        events(tenant).size shouldBe 3
    }

    @Test
    fun `an altered object copy is a RETAINED_COPY_MISMATCH`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        chains(tenant)
        digest(tenant)
        objects.stored.keys.forEach { objects.stored[it] = "altered".toByteArray() }
        runJob(tenant)
        failures(tenant).map { it.kind }.toSet() shouldBe setOf("RETAINED_COPY_MISMATCH")
        runs(tenant).single().result shouldBe "FAILED"
    }

    @Test
    fun `a chain that cannot be read fails the run and is never VERIFIED`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        chains(tenant)
        digest(tenant)
        val unreadable =
            ledgers.mapValues { (kind, ledger) ->
                if (kind == RecordKind.ACKNOWLEDGEMENT) {
                    object : EvidenceLedger by ledger {
                        override fun verifyChain(
                            tenant: TenantId,
                            entity: EntityRef,
                            period: PeriodRef,
                        ): ChainVerificationResult = error("the chain cannot be read")
                    }
                } else {
                    ledger
                }
            }
        runJob(tenant) // an earlier intact run is VERIFIED
        assertThrows<IllegalStateException> { runJob(tenant, body = body(recorder(verification(unreadable)))) }
        runs(tenant).map { Triple(it.result, it.cause, it.chainLength) } shouldBe
            listOf(Triple("VERIFIED", "VERIFICATION", 8L), Triple("FAILED", "UNREADABLE", 0L))
        failures(tenant).size shouldBe 0
        events(tenant).size shouldBe 0
        val status = status(tenant)
        status.result shouldBe IntegrityState.FAILED
        status.chainLength shouldBe 0L
        runJob(tenant) // a later successful run appends a VERIFIED row
        runs(tenant).map { it.result } shouldBe listOf("VERIFIED", "FAILED", "VERIFIED")
        status(tenant).result shouldBe IntegrityState.VERIFIED
    }

    @Test
    fun `an object copy that cannot be read fails the run and is never VERIFIED`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        chains(tenant)
        digest(tenant)
        runJob(tenant)
        objects.readFailure = IllegalStateException("store down")
        assertThrows<IllegalStateException> { runJob(tenant) }
        runs(tenant).map { Triple(it.result, it.cause, it.chainLength) } shouldBe
            listOf(Triple("VERIFIED", "VERIFICATION", 8L), Triple("FAILED", "UNREADABLE", 0L))
        failures(tenant).size shouldBe 0
        events(tenant).size shouldBe 0
        status(tenant).result shouldBe IntegrityState.FAILED
        objects.readFailure = null
        runJob(tenant)
        runs(tenant).map { it.result } shouldBe listOf("VERIFIED", "FAILED", "VERIFIED")
    }

    @Test
    fun `the status is the latest run, a failed run is shown as failed, no run is NEVER_RUN`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val digest = "sha256:" + "a".repeat(64)
        val verified = Instant.parse("2026-05-01T01:00:00Z")
        val failed = Instant.parse("2026-05-02T01:00:00Z")

        fun row(
            forEntity: String,
            at: Instant,
            result: RunResult,
        ) = IntegrityRunRow(
            forEntity,
            period,
            UUID.randomUUID().toString(),
            at,
            result,
            RunCause.VERIFICATION,
            8,
            digest,
        )
        tx(tenant) {
            val store = JooqIntegrityStore(dsl)
            store.insertRun(tenant, row("E-FAILED", verified, RunResult.VERIFIED))
            store.insertRun(tenant, row("E-FAILED", failed, RunResult.FAILED))
            store.insertRun(tenant, row("E-VERIFIED", verified, RunResult.VERIFIED))
        }
        val first = status(tenant, "E-FAILED")
        first.result shouldBe IntegrityState.FAILED
        first.lastRunAt shouldBe failed
        first.chainLength shouldBe 8L
        first.periodDigest shouldBe digest
        val second = status(tenant, "E-VERIFIED")
        second.result shouldBe IntegrityState.VERIFIED
        second.lastRunAt shouldBe verified
        second.chainLength shouldBe 8L
        second.periodDigest shouldBe digest
        val third = status(tenant, "E-NONE")
        third.result shouldBe IntegrityState.NEVER_RUN
        third.lastRunAt shouldBe null
        third.chainLength shouldBe 0L
        third.periodDigest shouldBe null
    }

    @Test
    fun `the entity source lists the pairs through the sys function and leaves the reserved tenant-wide entity out`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        chains(tenant)
        chains(tenant, forEntity = EventEntityRef.TENANT_WIDE.value)
        val listed = VerificationEntitySource(scope, dsl).entities(ChainVerificationJob.DEFINITION)
        (JobTarget.Entity(tenant, EventEntityRef(entity)) in listed) shouldBe true
        listed.none { it.entity.isTenantWide } shouldBe true
        // probe: the look-alike entity of the same tenant is an ordinary entity
        val lookAlikeEntity = "00000000-0000-0000-0000-000000000001"
        chains(tenant, forEntity = lookAlikeEntity)
        (
            JobTarget.Entity(tenant, EventEntityRef(lookAlikeEntity)) in
                VerificationEntitySource(scope, dsl).entities(ChainVerificationJob.DEFINITION)
        ) shouldBe true
    }

    @Test
    fun `the service context wires the verification and registers exactly the declared job`(
        @TempDir dir: Path,
    ) = run(dir) { _ ->
        context.getBean(ChainVerification::class.java)
        context.getBean(IntegrityStatusQueryHandler::class.java)
        val clock = Clock.systemUTC()
        val authorizer =
            object : JobCommandAuthorizer {
                override fun mayCommandEntity(
                    actor: String,
                    tenant: TenantId,
                    entity: com.dynokore.platform.outboxandqueue.EntityRef,
                    command: JobCommand,
                ) = false

                override fun isPlatformOperator(
                    actor: String,
                    command: JobCommand,
                ) = false
            }

        fun runtime() =
            JobRuntime(
                "evidence-svc",
                ChainWiring.SCHEMA,
                scope,
                dsl,
                outbox,
                context.getBean(AuditWriter::class.java),
                authorizer,
                FixedDeadlineSource(),
                clock,
                Executor { it.run() },
            )
        val runtime = runtime()
        ChainVerificationConfiguration.registerChainVerificationJob(runtime, scope, dsl, recorder())
        runtime.registered() shouldBe
            listOf(
                JobDefinition(
                    name = "evidence-chain-verification",
                    ownerService = "evidence-svc",
                    entityScope = JobEntityScope.ENTITY,
                    schedule = JobSchedule("0 0 1 * * *", ZoneOffset.UTC),
                    criticality = JobCriticality.STANDARD,
                    tolerance = Duration.ofHours(6),
                    lockAtMostFor = Duration.ofHours(2),
                ),
            )
        val registered = runtime.registered().single()
        registered.name shouldBe "evidence-chain-verification"
        registered.ownerService shouldBe "evidence-svc"
        registered.entityScope shouldBe JobEntityScope.ENTITY
        registered.schedule.cron shouldBe "0 0 1 * * *"
        registered.schedule.zone shouldBe ZoneOffset.UTC
        registered.criticality shouldBe JobCriticality.STANDARD
        registered.tolerance shouldBe Duration.ofHours(6)
        registered.lockAtMostFor shouldBe Duration.ofHours(2)

        // probe: the five-field cron is refused when the job registers
        assertThrows<IllegalArgumentException> {
            runtime().register(
                ChainVerificationJob.DEFINITION.copy(schedule = JobSchedule("0 1 * * *", ZoneOffset.UTC)),
                VerificationEntitySource(scope, dsl),
                body(),
            )
        }
        // probe: another zone is not the declared definition
        val singapore = JobSchedule("0 0 1 * * *", ZoneId.of("Asia/Singapore"))
        (ChainVerificationJob.DEFINITION.copy(schedule = singapore) == registered) shouldBe false
    }
}
