package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E1
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.E2
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SCHEMA
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SERVICE
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.TENANT_A
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.TENANT_B
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.job
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Job-table maintenance without a worker role (coordinator ruling INV-Q-4346 (A); INV-ADR-3968 item 6) on PostgreSQL
 * 16, 17 and 18: the abandoned-run sweeper reads the `<svc>_sys`-owned IN_PROGRESS view and finishes each run under
 * its own tenant, and the purge is the sys-owned definer that reads the migration-written retention row, both as the
 * runtime login; the runtime login's direct cross-tenant SELECT and DELETE see and remove nothing, and with no
 * retention row the purge deletes nothing and the job runtime refuses to start.
 */
class JobMaintenanceIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the sweeper and the purge work across tenants as the runtime login, the direct access reaches nothing`(image: String) {
        JobsHarness(image).use { h ->
            val replica = h.replica("svc-1", MutableClock(Instant.now()))
            val sweep = job("maintenance-sweep")
            val purge = job("maintenance-purge")
            val entities = JobEntitySource { listOf(JobTarget.Entity(TENANT_A, E1), JobTarget.Entity(TENANT_B, E2)) }
            replica.runtime.register(sweep, entities) { }
            replica.runtime.register(purge, entities) { }
            replica.runtime.checkRetention()

            // abandoned runs of two tenants, an hour old (past lockAtMostFor), and finished, paused and recent rows
            listOf(TENANT_A to "E-001", TENANT_B to "E-002").forEach { (tenant, entity) ->
                insertRun(h, tenant.value, sweep.name, entity, "IN_PROGRESS", "1 hour")
                insertRun(h, tenant.value, purge.name, entity, "SUCCEEDED", "31 days")
                insertPause(h, tenant.value, purge.name, entity, ended = true, age = "31 days")
            }
            insertRun(h, TENANT_A.value, purge.name, "E-004", "SUCCEEDED", "1 day")
            insertPause(h, TENANT_A.value, purge.name, "E-005", ended = false, age = "1 day")

            // the runtime login with no tenant bound: its direct cross-tenant SELECT and DELETE reach no row
            h.fixture.runtimeDataSource().connection.use { runtime ->
                for (table in listOf("job_run", "job_pause")) {
                    runtime.prepareStatement("select count(*) from $SCHEMA.$table").use { s ->
                        s.executeQuery().use {
                            it.next()
                            it.getLong(1)
                        }
                    } shouldBe 0L
                    // the runtime login holds no DELETE on the job tables at all: refused, nothing removed
                    assertThrows<SQLException> {
                        runtime.prepareStatement("delete from $SCHEMA.$table").use { it.executeUpdate() }
                    }.sqlState shouldBe "42501"
                }
                // and it cannot read the retention row: only the sys role can (INV-Q-4346)
                assertThrows<SQLException> {
                    runtime.prepareStatement("select job_window from $SCHEMA.${JobSchema.JOB_RETENTION}").use { it.executeQuery() }
                }.sqlState shouldBe "42501"
            }
            count(h, "job_run", purge.name) shouldBe 3L
            count(h, "job_pause", purge.name) shouldBe 3L

            // the sweeper finishes each tenant's abandoned run under that tenant, as the job's own principal
            h.fixture.captureActors("$SCHEMA.outbox")
            replica.runtime.sweepAbandoned() shouldBe 2
            h.runs(sweep.name).map { it["status"] to it["reason"] }.toSet() shouldBe setOf("FAILED" to JobRuntime.ABANDONED)
            h.runs(sweep.name).map { it["tenant_id"] }.toSet() shouldBe setOf(TENANT_A.value, TENANT_B.value)
            h.fixture.capturedActors("$SCHEMA.outbox").toSet() shouldBe setOf("svc:$SERVICE/job:${sweep.name}")

            // an old run still IN_PROGRESS (not yet swept) is never purged
            insertRun(h, TENANT_A.value, purge.name, "E-003", "IN_PROGRESS", "31 days")

            // the purge deletes, across tenants, only finished runs and ended pauses older than the row's window
            replica.runtime.purge() shouldBe 4
            h.rows("select entity_ref, status from $SCHEMA.job_run where job = ? order by entity_ref", purge.name)
                .map { it["entity_ref"] to it["status"] } shouldBe listOf("E-003" to "IN_PROGRESS", "E-004" to "SUCCEEDED")
            h.rows("select entity_ref from $SCHEMA.job_pause where job = ?", purge.name).map { it["entity_ref"] } shouldBe listOf("E-005")

            // a retention that differs from the row stops the job runtime's start
            val longer = h.replica("svc-2", MutableClock(Instant.now()), retention = Duration.ofDays(40))
            assertThrows<IllegalStateException> { longer.runtime.announce() }

            // with no retention row the purge deletes nothing, and the job runtime refuses to start
            insertRun(h, TENANT_B.value, purge.name, "E-006", "SUCCEEDED", "400 days")
            h.fixture.run("delete from $SCHEMA.${JobSchema.JOB_RETENTION}")
            replica.runtime.purge() shouldBe 0
            count(h, "job_run", purge.name) shouldBe 3L
            assertThrows<IllegalStateException> { replica.runtime.announce() }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the view and the purge are the sys role's, granted to the runtime login only`(image: String) {
        JobsHarness(image).use { h ->
            val functions =
                h.rows(
                    "select p.proname as name, pg_get_userbyid(p.proowner) as owner, p.prosecdef as definer, " +
                        "p.proconfig::text as config, has_function_privilege('public', p.oid, 'execute') as public_exec, " +
                        "has_function_privilege(?, p.oid, 'execute') as runtime_exec " +
                        "from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = ? and p.proname in (?, ?) " +
                        "order by p.proname",
                    OutboxPostgresFixture.APP,
                    SCHEMA,
                    JobSchema.RETENTION_WINDOW,
                    JobSchema.PURGE_EXPIRED,
                )
            functions.map { it["name"] } shouldBe listOf(JobSchema.RETENTION_WINDOW, JobSchema.PURGE_EXPIRED)
            functions.forEach {
                it["owner"] shouldBe OutboxPostgresFixture.SYS
                it["definer"] shouldBe true
                it["config"] shouldBe "{\"search_path=pg_catalog, $SCHEMA, pg_temp\"}"
                it["public_exec"] shouldBe false
                it["runtime_exec"] shouldBe true
            }
            val view =
                h.rows(
                    "select pg_get_userbyid(c.relowner) as owner, has_table_privilege(?, c.oid, 'select') as runtime_select, " +
                        "has_table_privilege('public', c.oid, 'select') as public_select " +
                        "from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = ? and c.relname = ?",
                    OutboxPostgresFixture.APP,
                    SCHEMA,
                    JobSchema.IN_PROGRESS_VIEW,
                ).single()
            view shouldBe mapOf("owner" to OutboxPostgresFixture.SYS, "runtime_select" to true, "public_select" to false)
            h.rows(
                "select attname from pg_attribute a join pg_class c on c.oid = a.attrelid join pg_namespace n on n.oid = c.relnamespace " +
                    "where n.nspname = ? and c.relname = ? and a.attnum > 0 order by a.attnum",
                SCHEMA,
                JobSchema.IN_PROGRESS_VIEW,
            ).map { it["attname"] } shouldBe listOf("tenant_id", "job", "entity_ref", "tick", "trigger", "attempt", "started_at")
            // the retention table: platform-scoped, SELECT to the sys role only, a positive window
            h.rows("select obj_description(?::regclass, 'pg_class') as comment", "$SCHEMA.${JobSchema.JOB_RETENTION}")
                .single()["comment"] shouldBe "platform-scoped: no tenant data"
            h.rows(
                "select has_table_privilege(?, ?, 'select') as runtime, has_table_privilege(?, ?, 'select') as sys",
                OutboxPostgresFixture.APP,
                "$SCHEMA.${JobSchema.JOB_RETENTION}",
                OutboxPostgresFixture.SYS,
                "$SCHEMA.${JobSchema.JOB_RETENTION}",
            ).single() shouldBe mapOf("runtime" to false, "sys" to true)
            assertThrows<SQLException> { h.fixture.run("update $SCHEMA.${JobSchema.JOB_RETENTION} set job_window = interval '0'") }
            // the purge refuses a batch outside its bounds
            h.fixture.runtimeDataSource().connection.use { runtime ->
                for (batch in listOf(0, JobSchema.MAX_PURGE_BATCH + 1)) {
                    assertThrows<SQLException> {
                        runtime.prepareStatement("select $SCHEMA.${JobSchema.PURGE_EXPIRED}($batch)").use { it.executeQuery() }
                    }.sqlState shouldBe "22023"
                }
            }
        }
    }

    private fun insertRun(
        h: JobsHarness,
        tenant: UUID,
        job: String,
        entity: String,
        status: String,
        age: String,
    ) = h.fixture.runAsAdmin(
        "insert into $SCHEMA.job_run (tenant_id, service, job, entity_ref, tick, trigger, attempt, status, started_at, finished_at) " +
            "values ('$tenant', '$SERVICE', '$job', '$entity', now() - interval '$age', 'SCHEDULED', 1, '$status', " +
            "now() - interval '$age', case when '$status' = 'IN_PROGRESS' then null else now() - interval '$age' end)",
    )

    private fun insertPause(
        h: JobsHarness,
        tenant: UUID,
        job: String,
        entity: String,
        ended: Boolean,
        age: String,
    ) = h.fixture.runAsAdmin(
        "insert into $SCHEMA.job_pause (tenant_id, service, job, entity_ref, paused_at, paused_until, ended_at) " +
            "values ('$tenant', '$SERVICE', '$job', '$entity', now() - interval '$age' - interval '1 hour', " +
            (if (ended) "now() - interval '$age', now() - interval '$age')" else "now() + interval '1 day', null)"),
    )

    private fun count(
        h: JobsHarness,
        table: String,
        job: String,
    ) = h.rows("select count(*) as n from $SCHEMA.$table where job = ?", job).single()["n"]
}
