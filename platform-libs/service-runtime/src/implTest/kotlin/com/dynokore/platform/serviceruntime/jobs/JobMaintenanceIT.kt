package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E1
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E2
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SCHEMA
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SERVICE
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.TENANT_A
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.TENANT_B
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.job
import com.dynokore.platform.serviceruntime.testing.PG_CLASS
import com.dynokore.platform.serviceruntime.testing.PG_CLASS_NAME
import com.dynokore.platform.serviceruntime.testing.PG_CLASS_NAMESPACE
import com.dynokore.platform.serviceruntime.testing.PG_CLASS_OID
import com.dynokore.platform.serviceruntime.testing.PG_NAMESPACE
import com.dynokore.platform.serviceruntime.testing.PG_NAMESPACE_NAME
import com.dynokore.platform.serviceruntime.testing.PG_NAMESPACE_OID
import com.dynokore.platform.serviceruntime.testing.PG_PROC
import com.dynokore.platform.serviceruntime.testing.PG_PROC_CONFIG
import com.dynokore.platform.serviceruntime.testing.PG_PROC_DEFINER
import com.dynokore.platform.serviceruntime.testing.PG_PROC_NAME
import com.dynokore.platform.serviceruntime.testing.PG_PROC_NAMESPACE
import com.dynokore.platform.serviceruntime.testing.PG_PROC_OID
import com.dynokore.platform.serviceruntime.testing.PG_PROC_OWNER
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.types.DayToSecond
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Job-table maintenance without a worker role (coordinator ruling INV-Q-4346 (A); INV-ADR-3968 item 6) on PostgreSQL
 * 16, 17 and 18: the abandoned-run sweeper reads the `<svc>_sys`-owned IN_PROGRESS function (coordinator ruling
 * INV-Q-5115) and finishes each run under its own tenant, and the purge is the sys-owned definer that reads the
 * migration-written retention row, both as the runtime login; the runtime login's direct access sees only the bound
 * tenant's rows and removes nothing, and with no retention row the purge deletes nothing and the job runtime refuses to
 * start. Database access is TransactionRunner and jOOQ only (INV-Q-5119).
 */
class JobMaintenanceIT {
    private val runTable = DSL.table(DSL.name(SCHEMA, JobSchema.JOB_RUN))
    private val pauseTable = DSL.table(DSL.name(SCHEMA, JobSchema.JOB_PAUSE))
    private val retentionTable = DSL.table(DSL.name(SCHEMA, JobSchema.JOB_RETENTION))
    private val tenantId = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
    private val serviceCol = DSL.field(DSL.name("service"), SQLDataType.CLOB)
    private val jobCol = DSL.field(DSL.name("job"), SQLDataType.CLOB)
    private val entityRef = DSL.field(DSL.name("entity_ref"), SQLDataType.CLOB)
    private val tick = DSL.field(DSL.name("tick"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val trigger = DSL.field(DSL.name("trigger"), SQLDataType.CLOB)
    private val attempt = DSL.field(DSL.name("attempt"), SQLDataType.INTEGER)
    private val status = DSL.field(DSL.name("status"), SQLDataType.CLOB)
    private val startedAt = DSL.field(DSL.name("started_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val finishedAt = DSL.field(DSL.name("finished_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val pausedAt = DSL.field(DSL.name("paused_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val pausedUntil = DSL.field(DSL.name("paused_until"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val endedAt = DSL.field(DSL.name("ended_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the sweeper and the purge work across tenants as the runtime login, the direct access reaches nothing`(
        image: String,
    ) {
        JobsHarness(image).use { h ->
            val replica = h.replica("svc-1", MutableClock(Instant.now()))
            val sweep = job("maintenance-sweep")
            val purge = job("maintenance-purge")
            val entities = JobEntitySource { listOf(JobTarget.Entity(TENANT_A, E1), JobTarget.Entity(TENANT_B, E2)) }
            replica.runtime.register(sweep, entities) { }
            replica.runtime.register(purge, entities) { }
            replica.runtime.checkRetention()

            seedRows(h, sweep.name, purge.name)

            assertRuntimeLoginIsBoundToA(h, purge.name)

            // the sweeper finishes each tenant's abandoned run under that tenant, as the job's own principal
            h.captureActors("$SCHEMA.outbox")
            replica.runtime.sweepAbandoned() shouldBe 2
            h.runs(sweep.name).map { it["status"] to it["reason"] }.toSet() shouldBe
                setOf("FAILED" to JobRuntime.ABANDONED)
            h.runs(sweep.name).map { it["tenant_id"] }.toSet() shouldBe setOf(TENANT_A.value, TENANT_B.value)
            h.capturedActors("$SCHEMA.outbox").toSet() shouldBe setOf("svc:$SERVICE/job:${sweep.name}")

            // an old run still IN_PROGRESS (not yet swept) is never purged
            insertRun(h, Row(TENANT_A, purge.name, "E-003"), "IN_PROGRESS", Duration.ofDays(31))

            // the purge deletes, across tenants, only finished runs and ended pauses older than the row's window
            replica.runtime.purge() shouldBe 4
            h
                .rows("$SCHEMA.job_run", "job" to purge.name)
                .sortedBy { it["entity_ref"] as String }
                .map { it["entity_ref"] to it["status"] } shouldBe
                listOf("E-003" to "IN_PROGRESS", "E-004" to "SUCCEEDED")
            h.rows("$SCHEMA.job_pause", "job" to purge.name).map { it["entity_ref"] } shouldBe listOf("E-005")

            assertRetentionRowGuards(h, replica, purge.name)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the functions and the purge are the sys role's, granted to the runtime login only`(image: String) {
        JobsHarness(image).use { h ->
            assertSysFunctions(h)

            // the sweeper's function returns only the seven columns, ordered by started_at, and refuses a bad batch
            val replica = h.replica("svc-1", MutableClock(Instant.now()))
            assertSweeperFunction(h, replica)

            assertRetentionTable(h, replica)

            assertPurgeBatchBounds(replica)
        }
    }

    private fun seedRows(
        h: JobsHarness,
        sweepJob: String,
        purgeJob: String,
    ) {
        // abandoned runs of two tenants, an hour old (past lockAtMostFor), and finished, paused and recent rows
        listOf(TENANT_A to "E-001", TENANT_B to "E-002").forEach { (tenant, entity) ->
            insertRun(h, Row(tenant, sweepJob, entity), "IN_PROGRESS", Duration.ofHours(1))
            insertRun(h, Row(tenant, purgeJob, entity), "SUCCEEDED", Duration.ofDays(31))
            insertPause(h, Row(tenant, purgeJob, entity), ended = true, age = Duration.ofDays(31))
        }
        insertRun(h, Row(TENANT_A, purgeJob, "E-004"), "SUCCEEDED", Duration.ofDays(1))
        insertPause(h, Row(TENANT_A, purgeJob, "E-005"), ended = false, age = Duration.ofDays(1))
    }

    private fun assertRuntimeLoginIsBoundToA(
        h: JobsHarness,
        purgeJob: String,
    ) {
        // the runtime login, bound to tenant A: its direct SELECT reaches only A's rows (never B's), it holds no
        // DELETE on the job tables at all (refused, nothing removed), and it cannot read the retention row
        val runtime = h.replica("svc-probe", MutableClock(Instant.now()))
        for (table in listOf(runTable, pauseTable)) {
            val seen =
                runtime.asTenant(TENANT_A) {
                    runtime.tx.inTransaction {
                        runtime.dsl
                            .select(tenantId)
                            .from(table)
                            .fetch(tenantId)
                            .toSet()
                    }
                }
            seen shouldBe setOf(TENANT_A.value)
            sqlStateOf(runtime) {
                runtime.asTenant(TENANT_A) { runtime.tx.inTransaction { runtime.dsl.deleteFrom(table).execute() } }
            } shouldBe "42501"
        }
        sqlStateOf(runtime) {
            runtime.asPlatform {
                runtime.tx.inTransaction {
                    runtime.dsl
                        .select(
                            retentionWindow(),
                        ).from(retentionTable)
                        .fetch()
                }
            }
        } shouldBe "42501"
        count(h, runTable, purgeJob) shouldBe 3
        count(h, pauseTable, purgeJob) shouldBe 3
    }

    private fun assertRetentionRowGuards(
        h: JobsHarness,
        replica: JobsHarness.Replica,
        purgeJob: String,
    ) {
        // a retention that differs from the row stops the job runtime's start
        val longer =
            h.replica(
                "svc-2",
                MutableClock(Instant.now()),
                extras = JobsHarness.ReplicaExtras(retention = Duration.ofDays(40)),
            )
        assertThrows<IllegalStateException> { longer.runtime.announce() }

        // with no retention row the purge deletes nothing, and the job runtime refuses to start
        insertRun(h, Row(TENANT_B, purgeJob, "E-006"), "SUCCEEDED", Duration.ofDays(400))
        h.owner.asPlatform { deleteFrom(retentionTable).execute() }
        replica.runtime.purge() shouldBe 0
        count(h, runTable, purgeJob) shouldBe 3
        assertThrows<IllegalStateException> { replica.runtime.announce() }
    }

    private fun functionRows(
        h: JobsHarness,
        names: List<String>,
    ): List<List<Any?>> =
        h.admin.asPlatform {
            val namespace =
                select(
                    PG_NAMESPACE_OID,
                ).from(PG_NAMESPACE).where(PG_NAMESPACE_NAME.eq(SCHEMA)).fetchOne(PG_NAMESPACE_OID)
            select(
                PG_PROC_NAME,
                DSL.function(DSL.name("pg_get_userbyid"), SQLDataType.VARCHAR, PG_PROC_OWNER),
                PG_PROC_DEFINER,
                PG_PROC_CONFIG.cast(SQLDataType.CLOB),
                DSL.function(
                    DSL.name("has_function_privilege"),
                    SQLDataType.BOOLEAN,
                    DSL.`val`("public"),
                    PG_PROC_OID,
                    DSL.`val`("execute"),
                ),
                DSL.function(
                    DSL.name("has_function_privilege"),
                    SQLDataType.BOOLEAN,
                    DSL.`val`(OutboxPostgresFixture.APP),
                    PG_PROC_OID,
                    DSL.`val`("execute"),
                ),
            ).from(PG_PROC)
                .where(PG_PROC_NAMESPACE.eq(namespace).and(PG_PROC_NAME.`in`(names)))
                .orderBy(PG_PROC_NAME)
                .fetch()
                .map { it.intoList() }
        }

    private fun assertSysFunctions(h: JobsHarness) {
        val names = listOf(JobSchema.IN_PROGRESS_RUNS, JobSchema.RETENTION_WINDOW, JobSchema.PURGE_EXPIRED).sorted()
        val functions = functionRows(h, names)
        functions.map { it[0] } shouldBe names
        functions.forEach {
            it[1] shouldBe OutboxPostgresFixture.SYS
            it[2] shouldBe true
            it[3] shouldBe "{\"search_path=pg_catalog, $SCHEMA, pg_temp\"}"
            it[4] shouldBe false
            it[5] shouldBe true
        }
    }

    private fun assertSweeperFunction(
        h: JobsHarness,
        replica: JobsHarness.Replica,
    ) {
        insertRun(h, Row(TENANT_A, "shape", "E-001"), "IN_PROGRESS", Duration.ofHours(1))
        insertRun(h, Row(TENANT_B, "shape", "E-002"), "IN_PROGRESS", Duration.ofHours(2))
        val json = inProgress(replica, 500)
        val runs = ObjectMapper().readTree(json)
        runs.size() shouldBe 2
        runs.values().forEach {
            it
                .propertyNames()
                .asSequence()
                .toList()
                .sorted() shouldBe
                listOf("attempt", "entity_ref", "job", "started_at", "tenant_id", "tick", "trigger")
        }
        runs.values().map { it.get("entity_ref").asString() } shouldBe listOf("E-002", "E-001")
        for (batch in listOf(0, JobSchema.MAX_SWEEP_BATCH + 1)) {
            sqlStateOf(replica) { inProgress(replica, batch) } shouldBe "22023"
        }
    }

    private fun retentionTableFacts(h: JobsHarness): List<Any?> =
        h.admin.asPlatform {
            val namespace =
                select(
                    PG_NAMESPACE_OID,
                ).from(PG_NAMESPACE).where(PG_NAMESPACE_NAME.eq(SCHEMA)).fetchOne(PG_NAMESPACE_OID)
            select(
                DSL.function(
                    DSL.name("obj_description"),
                    SQLDataType.CLOB,
                    PG_CLASS_OID,
                    DSL.`val`("pg_class"),
                ),
                DSL.function(
                    DSL.name("has_table_privilege"),
                    SQLDataType.BOOLEAN,
                    DSL.`val`(OutboxPostgresFixture.APP),
                    PG_CLASS_OID,
                    DSL.`val`("select"),
                ),
                DSL.function(
                    DSL.name("has_table_privilege"),
                    SQLDataType.BOOLEAN,
                    DSL.`val`(OutboxPostgresFixture.SYS),
                    PG_CLASS_OID,
                    DSL.`val`("select"),
                ),
            ).from(PG_CLASS)
                .where(PG_CLASS_NAMESPACE.eq(namespace).and(PG_CLASS_NAME.eq(JobSchema.JOB_RETENTION)))
                .fetchOne()!!
                .intoList()
        }

    private fun assertRetentionTable(
        h: JobsHarness,
        replica: JobsHarness.Replica,
    ) {
        // the retention table: platform-scoped, SELECT to the sys role only
        val retention = retentionTableFacts(h)
        retention shouldBe listOf("platform-scoped: no tenant data", false, true)
        sqlStateOf(replica) {
            h.owner.asPlatform {
                update(
                    retentionTable,
                ).set(
                    DSL.field(DSL.name("job_window"), SQLDataType.INTERVALDAYTOSECOND),
                    DayToSecond.valueOf(Duration.ZERO),
                ).execute()
            }
        } shouldBe "23514"
    }

    private fun assertPurgeBatchBounds(replica: JobsHarness.Replica) {
        // the purge refuses a batch outside its bounds
        for (batch in listOf(0, JobSchema.MAX_PURGE_BATCH + 1)) {
            sqlStateOf(replica) {
                replica.asPlatform {
                    replica.tx.inTransaction {
                        replica.dsl
                            .select(
                                DSL.function(
                                    DSL.name(SCHEMA, JobSchema.PURGE_EXPIRED),
                                    SQLDataType.BIGINT,
                                    DSL.`val`(batch),
                                ),
                            ).fetch()
                    }
                }
            } shouldBe "22023"
        }
    }

    private fun JobsHarness.Replica.asPlatform(block: () -> Unit) = tenants.bindPlatform("test-operator", block)

    private fun inProgress(
        replica: JobsHarness.Replica,
        batch: Int,
    ): String =
        replica.tenants.bindPlatform("test-operator") {
            replica.tx.inTransaction {
                replica.dsl
                    .select(
                        DSL.function(DSL.name(SCHEMA, JobSchema.IN_PROGRESS_RUNS), SQLDataType.JSONB, DSL.`val`(batch)),
                    ).fetchOne()!!
                    .value1()
                    .data()
            }
        }

    private fun retentionWindow(): Field<*> = DSL.field(DSL.name("job_window"), SQLDataType.INTERVALDAYTOSECOND)

    /** The SQLSTATE of the database error [block] raises (the runner reads it through the cause chain). */
    private fun sqlStateOf(
        replica: JobsHarness.Replica,
        block: () -> Any?,
    ): String? = assertThrows<DataAccessException> { block() }.let { replica.tx.sqlStateOf(it) }

    /** One job row's identity: the tenant, the job and the entity it is for. */
    private data class Row(
        val tenant: TenantId,
        val job: String,
        val entity: String,
    )

    private fun insertRun(
        h: JobsHarness,
        row: Row,
        status: String,
        age: Duration,
    ) {
        val (tenant, job, entity) = row
        val at = OffsetDateTime.now().minus(age)
        h.owner.asTenant(tenant) {
            insertInto(runTable)
                .columns(
                    tenantId,
                    serviceCol,
                    jobCol,
                    entityRef,
                    tick,
                    trigger,
                    attempt,
                    this@JobMaintenanceIT.status,
                    startedAt,
                    finishedAt,
                ).values(
                    tenant.value,
                    SERVICE,
                    job,
                    entity,
                    at,
                    "SCHEDULED",
                    1,
                    status,
                    at,
                    if (status ==
                        "IN_PROGRESS"
                    ) {
                        null
                    } else {
                        at
                    },
                ).execute()
        }
    }

    private fun insertPause(
        h: JobsHarness,
        row: Row,
        ended: Boolean,
        age: Duration,
    ) {
        val (tenant, job, entity) = row
        val now = OffsetDateTime.now()
        val pausedFrom = now.minus(age).minusHours(1)
        h.owner.asTenant(tenant) {
            insertInto(pauseTable)
                .columns(tenantId, serviceCol, jobCol, entityRef, pausedAt, pausedUntil, endedAt)
                .values(
                    tenant.value,
                    SERVICE,
                    job,
                    entity,
                    pausedFrom,
                    if (ended) now.minus(age) else now.plusDays(1),
                    if (ended) now.minus(age) else null,
                ).execute()
        }
    }

    private fun count(
        h: JobsHarness,
        table: org.jooq.Table<*>,
        job: String,
    ): Int =
        listOf(TENANT_A, TENANT_B).sumOf { tenant ->
            h.owner.asTenant(tenant) { fetchCount(table, jobCol.eq(job)) }
        } + h.owner.asPlatform { fetchCount(table, jobCol.eq(job)) }

    @Suppress("unused")
    private val ignoredUuid: UUID? = null
}
