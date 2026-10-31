package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.persistence.ArchiveVolumeEntitySource
import com.dynokore.gst.evidence.adapters.persistence.JooqArchiveVolumeLedger
import com.dynokore.gst.evidence.adapters.persistence.JooqStoredContentBytes
import com.dynokore.gst.evidence.adapters.persistence.ObjectStoreBytes
import com.dynokore.gst.evidence.application.archive.ArchiveVolumePublisher
import com.dynokore.gst.evidence.application.archive.ArchiveVolumeSource
import com.dynokore.gst.evidence.application.archive.RetainedArchiveVolume
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectKeyPrefix
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
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
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor

/**
 * The daily archive-volume job over a real database and a fake [ObjectStore] (P109), on the TPL-18 context:
 * the registered definition, the measurement, the run, the trace ids and INV-AC-9905.
 */
class ArchiveVolumeJobTest {
    private val actor = "svc:evidence-svc"
    private val instant = Instant.parse("2026-10-29T01:30:05Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private val firing = Instant.parse("2026-10-29T01:30:00Z")
    private val mapper = JsonMapper.builder().build()

    private class Objects : ObjectStore {
        val sizes = mutableMapOf<String, Int>()
        var refuse = false

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef = ObjectRef(key, retainUntil).also { sizes[key.value] = bytes.size }

        override fun get(key: ObjectKey): ByteArray = ByteArray(sizes.getValue(key.value))

        override fun bytesUnder(prefix: ObjectKeyPrefix): Long {
            if (refuse) throw UnsupportedOperationException("bytesUnder")
            return sizes.filterKeys { it.startsWith(prefix.value) }.values.sumOf { it.toLong() }
        }
    }

    private inner class Boundary(
        val context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val objects = Objects()
        val source: ArchiveVolumeSource = RetainedArchiveVolume(JooqStoredContentBytes(dsl), ObjectStoreBytes(objects))
        private val catalogue = EventCatalogue(EvidenceEvents.entries)
        private val publisher =
            ArchiveVolumePublisher(
                JooqArchiveVolumeLedger(dsl),
                JdbcOutboxWriter(dsl, EvidenceDb.SCHEMA, "evidence-svc", "evidence-svc-0", catalogue, clock),
                clock,
            )
        private var runIds = 0
        val body =
            ArchiveVolumeJobBody("evidence-svc", scope, source, publisher) {
                UUID.fromString("00000000-0000-0000-0000-${(++runIds).toString().padStart(12, '0')}")
            }

        fun runJob(
            tenant: UUID,
            entity: String,
            trigger: JobTrigger = JobTrigger.SCHEDULED,
            attempt: Int = 1,
            scheduledFor: Instant = firing,
        ) = body.run(
            JobRunContext(
                ArchiveVolumeJob.DEFINITION,
                JobTarget.Entity(TenantId(tenant), EntityRef(entity)),
                trigger,
                scheduledFor,
                attempt,
            ) { _, _ -> false },
        )

        fun <T> inTenant(
            tenant: UUID,
            block: () -> T,
        ): T = scope.bind(TenantId(tenant), actor) { runner.inTransaction { block() } }

        fun listed(tenant: UUID): Set<String> =
            ArchiveVolumeEntitySource(scope, dsl)
                .entities(ArchiveVolumeJob.DEFINITION)
                .filter { it.tenant.value == tenant }
                .map { it.entity.value }
                .toSet()
    }

    private fun run(
        dir: Path,
        block: Boundary.() -> Unit,
    ) {
        EvidenceContext.online(dir).run { context -> Boundary(context).block() }
    }

    // ---- seeding as the superuser ----

    private fun <T> superuser(block: (Connection) -> T): T = EvidenceDb.superuser().use(block)

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun seedTrace(
        tenant: UUID,
        entity: String,
        length: Int,
    ) = superuser {
        it.exec(
            "insert into evidence.decision_trace (id, tenant_id, entity_ref, period_ref, seq, evidence_type, " +
                "content, content_hash, recorded_at) values (gen_random_uuid(), '$tenant', '$entity', '2026-Q3', " +
                "(select coalesce(max(seq), 0) + 1 from evidence.decision_trace where tenant_id = '$tenant' and " +
                "entity_ref = '$entity'), 'DECISION_TRACE', convert_to(repeat('a', $length), 'UTF8'), 'h', now())",
        )
    }

    private fun deleteContent(
        tenant: UUID,
        entity: String,
    ) = superuser {
        it.exec("set session_replication_role = replica")
        it.exec("delete from evidence.decision_trace where tenant_id = '$tenant' and entity_ref = '$entity'")
    }

    private fun Boundary.putObject(
        tenant: UUID,
        entity: String,
        length: Int,
    ) = objects.sizes.put(
        ObjectStoreBytes.prefix(TenantId(tenant), entity).value + "2026-Q3/${objects.sizes.size + 1}.json",
        length,
    )

    private data class Row(
        val tenant: String,
        val entity: String,
        val correlation: String,
        val causation: String,
        val data: String,
    )

    private fun outbox(tenant: UUID): List<Row> =
        superuser { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select tenant_id, entity_ref, correlation_id, causation_id, data::text from evidence.outbox " +
                            "where tenant_id = '$tenant' order by seq",
                    ).use { r ->
                        generateSequence {
                            if (r.next()) {
                                Row(
                                    r.getString(1),
                                    r.getString(2),
                                    r.getString(3),
                                    r.getString(4),
                                    r.getString(5),
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
            }
        }

    private fun ledgerRows(tenant: UUID): Int =
        superuser { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select count(*) from evidence.archive_volume_published where tenant_id = '$tenant'",
                    ).use {
                        it.next()
                        it.getInt(1)
                    }
            }
        }

    private fun bytesOf(row: Row): Long = mapper.readTree(row.data).get("bytes").asLong()

    // ---- the job ----

    private val declared =
        JobDefinition(
            name = "archive-volume-measurement",
            ownerService = "evidence-svc",
            entityScope = JobEntityScope.ENTITY,
            schedule = JobSchedule("0 30 1 * * *", ZoneOffset.UTC),
            criticality = JobCriticality.STANDARD,
            tolerance = Duration.ofHours(6),
            lockAtMostFor = Duration.ofHours(2),
        )

    @Test
    fun `the definition equals the declared values and each look-alike differs`() {
        ArchiveVolumeJob.DEFINITION shouldBe declared
        (ArchiveVolumeJob.DEFINITION == declared.copy(name = "archive-volume-measure")) shouldBe false
        (
            ArchiveVolumeJob.DEFINITION ==
                declared.copy(schedule = JobSchedule("0 30 1 * * 1"))
        ) shouldBe false
        (ArchiveVolumeJob.DEFINITION == declared.copy(lockAtMostFor = Duration.ofHours(3))) shouldBe false
        (ArchiveVolumeJob.DEFINITION == declared.copy(tolerance = Duration.ofHours(7))) shouldBe false
    }

    @Test
    fun `the service context wires the volume source and the publisher`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.online(dir).run { context ->
            context.getBean(ArchiveVolumeSource::class.java)
            context.getBean(ArchiveVolumePublisher::class.java)
        }
    }

    private val denyAll =
        object : JobCommandAuthorizer {
            override fun mayCommandEntity(
                actor: String,
                tenant: TenantId,
                entity: EntityRef,
                command: JobCommand,
            ) = false

            override fun isPlatformOperator(
                actor: String,
                command: JobCommand,
            ) = false
        }

    private fun Boundary.newRuntime() =
        JobRuntime(
            "evidence-svc",
            EvidenceDb.SCHEMA,
            scope,
            dsl,
            context.getBean(OutboxWriter::class.java),
            context.getBean(AuditWriter::class.java),
            denyAll,
            FixedDeadlineSource(),
            clock,
            Executor { it.run() },
        )

    @Test
    fun `the service registers exactly the declared job through the configuration`(
        @TempDir dir: Path,
    ) = run(dir) {
        val runtime = newRuntime()
        ArchiveVolumeJobConfiguration.registerArchiveVolumeJob(
            runtime,
            scope,
            dsl,
            source,
            ArchiveVolumePublisher(
                JooqArchiveVolumeLedger(dsl),
                JdbcOutboxWriter(
                    dsl,
                    EvidenceDb.SCHEMA,
                    "evidence-svc",
                    "evidence-svc-0",
                    EventCatalogue(EvidenceEvents.entries),
                    clock,
                ),
                clock,
            ),
        )
        runtime.registered() shouldBe listOf(declared)
        val registered = runtime.registered().single()
        registered.name shouldBe "archive-volume-measurement"
        registered.ownerService shouldBe "evidence-svc"
        registered.entityScope shouldBe JobEntityScope.ENTITY
        registered.schedule.cron shouldBe "0 30 1 * * *"
        registered.schedule.zone shouldBe ZoneOffset.UTC
        registered.criticality shouldBe JobCriticality.STANDARD
        registered.tolerance shouldBe Duration.ofHours(6)
        registered.lockAtMostFor shouldBe Duration.ofHours(2)
        // probe: a look-alike definition is not the registered one
        (registered == declared.copy(name = "archive-volume-measure")) shouldBe false
    }

    @Test
    fun `the entity source lists only the real pairs and the reserved tenant-wide entity gets no row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = UUID.randomUUID()
        val tenantWide = EntityRef.TENANT_WIDE.value
        val lookAlike = "00000000-0000-0000-0000-000000000001"
        seedTrace(tenant, "E1", 10)
        seedTrace(tenant, tenantWide, 20)
        seedTrace(tenant, lookAlike, 30)
        listed(tenant) shouldBe setOf("E1", lookAlike)
        listed(tenant).forEach { runJob(tenant, it) }
        outbox(tenant).map { it.entity }.toSet() shouldBe setOf("E1", lookAlike)
        outbox(tenant).any { it.entity == tenantWide } shouldBe false
        superuser { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select count(*) from evidence.archive_volume_published where tenant_id = '$tenant' " +
                            "and entity_ref = '$tenantWide'",
                    ).use {
                        it.next()
                        it.getInt(1)
                    }
            }
        } shouldBe 0
    }

    @Test
    fun `the production catalogue holds exactly one ArchiveVolumeMeasured entry with the declared values`(
        @TempDir dir: Path,
    ) {
        val expected = "gst.evidence.archive-volume-measured.v1"
        val entries = EvidenceEvents.entries.filter { it.eventType == ArchiveVolumePublisher.EVENT_TYPE }
        entries.size shouldBe 1
        entries.single().topic shouldBe expected
        entries.single().producers shouldBe setOf("evidence-svc")
        entries.single().contentTopic shouldBe false
        entries.single().recordTopic shouldBe false
        entries.single().retainedWhileResolvable shouldBe false
        EvidenceContext.online(dir).run { context ->
            context
                .getBean(EventCatalogue::class.java)
                .entries()
                .count { it.eventType == ArchiveVolumePublisher.EVENT_TYPE } shouldBe 1
        }
    }

    // ---- the measurement ----

    @Test
    fun `rows of 100 and 200 bytes and one object of 1000 give 1300 and nothing else is added`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = UUID.randomUUID()
        val other = UUID.randomUUID()
        seedTrace(tenant, "E-1", 100)
        seedTrace(tenant, "E-1", 200)
        putObject(tenant, "E-1", 1000)
        seedTrace(tenant, "E-2", 7000)
        putObject(tenant, "E-2", 7000)
        seedTrace(other, "E-1", 5000)
        putObject(other, "E-1", 5000)
        inTenant(tenant) { source.bytesRetained(TenantId(tenant), "E-1") } shouldBe 1300L
        // a tenant that is not the bound tenant is never read (row-level security)
        inTenant(other) { source.bytesRetained(TenantId(tenant), "E-1") } shouldBe 1000L
    }

    // ---- the run ----

    @Test
    fun `an object store that refuses fails the run and leaves no ledger row and no outbox row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = UUID.randomUUID()
        seedTrace(tenant, "E-1", 100)
        objects.refuse = true
        shouldThrow<UnsupportedOperationException> { runJob(tenant, "E-1") }
        ledgerRows(tenant) shouldBe 0
        outbox(tenant) shouldBe emptyList()
    }

    @Test
    fun `after all content is deleted one run publishes zero and the next run no longer lists the entity`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = UUID.randomUUID()
        seedTrace(tenant, "E-1", 100)
        putObject(tenant, "E-1", 1000)
        runJob(tenant, "E-1")
        deleteContent(tenant, "E-1")
        objects.sizes.clear()
        listed(tenant) shouldBe setOf("E-1")
        runJob(tenant, "E-1")
        listed(tenant) shouldBe emptySet()
        outbox(tenant).map(::bytesOf) shouldBe listOf(1100L, 0L)
    }

    @Test
    fun `a second run of the same entity on the same day with an unchanged value still publishes`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = UUID.randomUUID()
        seedTrace(tenant, "E-1", 100)
        runJob(tenant, "E-1")
        runJob(tenant, "E-1")
        outbox(tenant).map(::bytesOf) shouldBe listOf(100L, 100L)
    }

    // ---- the trace ids (INV-Q-12736 A) ----

    private fun Boundary.traced(
        trigger: JobTrigger = JobTrigger.SCHEDULED,
        attempt: Int = 1,
    ): Row {
        val tenant = UUID.randomUUID()
        seedTrace(tenant, "E-1", 10)
        runJob(tenant, "E-1", trigger, attempt)
        return outbox(tenant).single()
    }

    private fun causation(
        tenant: String,
        entity: String,
        trigger: String,
        name: String = "archive-volume-measurement",
    ) = "$name:$tenant:$entity:$trigger:2026-10-29T01:30:00Z"

    @Test
    fun `the causation id names the job, the tenant, the entity, the trigger and the firing`(
        @TempDir dir: Path,
    ) = run(dir) {
        val row = traced()
        row.causation shouldBe causation(row.tenant, "E-1", "SCHEDULED")
        row.correlation shouldBe "job-run:00000000-0000-0000-0000-000000000001"
    }

    @Test
    fun `the correlation id is not the causation id and carries the job-run prefix and a canonical UUID`(
        @TempDir dir: Path,
    ) = run(dir) {
        val row = traced()
        (row.correlation == row.causation) shouldBe false
        row.correlation shouldStartWith "job-run:"
        UUID.fromString(row.correlation.removePrefix("job-run:")).toString() shouldBe
            row.correlation.removePrefix("job-run:")
    }

    @Test
    fun `a manual run gives the same text with MANUAL`(
        @TempDir dir: Path,
    ) = run(dir) {
        val row = traced(JobTrigger.MANUAL)
        row.causation shouldBe causation(row.tenant, "E-1", "MANUAL")
    }

    @Test
    fun `an attempt 2 gives the causation id of attempt 1 and the attempt is not in the text`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = UUID.randomUUID()
        seedTrace(tenant, "E-1", 10)
        runJob(tenant, "E-1", attempt = 1)
        runJob(tenant, "E-1", attempt = 2)
        val rows = outbox(tenant)
        rows[1].causation shouldBe rows[0].causation
        rows.forEach { (it.causation.contains("attempt", ignoreCase = true)) shouldBe false }
    }

    @Test
    fun `the job name archive-volume-measure is not in the causation id`(
        @TempDir dir: Path,
    ) = run(dir) {
        val row = traced()
        row.causation shouldStartWith "archive-volume-measurement:"
        (row.causation == causation(row.tenant, "E-1", "SCHEDULED", name = "archive-volume-measure")) shouldBe false
    }

    @Test
    fun `two entities of one firing get different correlation ids and different causation ids`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = UUID.randomUUID()
        seedTrace(tenant, "E-1", 10)
        seedTrace(tenant, "E-2", 10)
        runJob(tenant, "E-1")
        runJob(tenant, "E-2")
        val rows = outbox(tenant)
        (rows[0].correlation == rows[1].correlation) shouldBe false
        (rows[0].causation == rows[1].causation) shouldBe false
    }

    // ---- INV-AC-9905 ----

    private val schemas =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }

    private fun schemaText(): String {
        val dir = checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" }
        val file = File(dir, "schemas/archive-volume-measured-payload.schema.json")
        check(file.isFile) { "schema file does not exist: $file" }
        return file.readText()
    }

    @Test
    fun `INV-AC-9905 the job measures each entity and publishes zero once after the deletion`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = UUID.randomUUID()
        val other = UUID.randomUUID()
        seedTrace(tenant, "E-1", 100)
        putObject(tenant, "E-1", 1000)
        seedTrace(tenant, "E-2", 300)
        seedTrace(other, "E-1", 5000)
        putObject(other, "E-1", 5000)
        listed(tenant) shouldBe setOf("E-1", "E-2")
        listed(tenant).forEach { runJob(tenant, it) }
        val first = outbox(tenant)
        first.map { it.entity to bytesOf(it) }.toSet() shouldBe setOf("E-1" to 1100L, "E-2" to 300L)
        first.forEach {
            val payload = mapper.readTree(it.data)
            payload.get("tenantRef").asString() shouldBe tenant.toString()
            payload.get("measuredAt").asString() shouldBe "2026-10-29T01:30:05Z"
            schemas.getSchema(schemaText()).validate(payload).map { e -> e.toString() } shouldBe emptyList()
        }
        outbox(other) shouldBe emptyList()
        deleteContent(tenant, "E-1")
        objects.sizes.keys.removeIf { it.contains("/$tenant/E-1/") }
        listed(tenant).forEach { runJob(tenant, it) }
        outbox(tenant).filter { it.entity == "E-1" }.map(::bytesOf) shouldBe listOf(1100L, 0L)
        listed(tenant) shouldBe setOf("E-2")
        listed(tenant).forEach { runJob(tenant, it) }
        outbox(tenant).count { it.entity == "E-1" } shouldBe 2
    }
}
