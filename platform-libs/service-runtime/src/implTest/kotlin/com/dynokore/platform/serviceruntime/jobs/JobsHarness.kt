package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.testing.CATALOG_TABLES
import com.dynokore.platform.serviceruntime.testing.TestDatabase
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TestPoolFixture
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.types.DayToSecond
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executor
import javax.sql.DataSource

/** A settable clock shared by the replicas of a test. */
class MutableClock(
    var now: Instant,
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/** Grants per actor: the entities it may command, and whether it holds the platform operator role. */
class FakeAuthorizer(
    private val entityGrants: Map<String, Set<EntityRef>> = emptyMap(),
    private val operators: Set<String> = emptySet(),
) : JobCommandAuthorizer {
    override fun mayCommandEntity(
        actor: String,
        tenant: TenantId,
        entity: EntityRef,
        command: JobCommand,
    ) = entity in entityGrants[actor].orEmpty()

    override fun isPlatformOperator(
        actor: String,
        command: JobCommand,
    ) = actor in operators
}

/** A deadline source whose due dates the test moves (INV-Q-2946: due dates can move earlier). */
class MovableDeadlines(
    var margin: Duration = Duration.ofHours(1),
) : DeadlineSource {
    val due = mutableMapOf<JobTarget, Instant>()

    override fun nearestDueDate(
        job: JobDefinition,
        target: JobTarget,
    ) = due[target]

    override fun safetyMargin(
        job: JobDefinition,
        target: JobTarget,
    ) = margin
}

/**
 * One PostgreSQL container (17 or 18) holding the fixture service `submission-svc`: outbox-and-queue's outbox,
 * the job tables of [JobSchema] and their job-retention row, and outbox-and-queue's fixture roles (the runtime
 * login [OutboxPostgresFixture.APP] and the sys role [OutboxPostgresFixture.SYS]). Each [replica] is one
 * service instance with its own transaction runner, outbox writer (instance id) and [JobRuntime]. The service's
 * tables are created by the container's init script (INV-Q-5119); the tests reach the database only through
 * [TestDatabase] (TransactionRunner and jOOQ).
 *
 * @param extraDdl state tables the test adds, created with the service (as the owner role).
 * @param extraTables those tables (and any other the test reads) by `schema.table`, with their class.
 */
class JobsHarness
    @JvmOverloads
    constructor(
        image: String,
        extraDdl: String = "",
        extraTables: Map<String, TableClass> = emptyMap(),
        /** Tables whose inserts record the bound actor (see [capturedActors]); they must exist after [extraDdl]. */
        captureTables: List<String> = listOf("$SCHEMA.outbox", "$SCHEMA.job_run"),
        /** More provisioning SQL, run last as the owner role (a trigger on an object the harness created). */
        extraInitSql: String = "",
    ) : AutoCloseable {
        val fixture =
            OutboxPostgresFixture(
                image,
                OutboxPostgresFixture.serviceInitSql(
                    SCHEMA,
                    JobSchema.ddl(SCHEMA, OutboxPostgresFixture.APP, OutboxPostgresFixture.SYS) + "\n" + extraDdl,
                ) + OutboxPostgresFixture.captureActorsInitSql(captureTables) + "\n" + extraInitSql,
            )

        /** The tables the tests declare to tenancy-jooq: the job and outbox tables, the libraries' platform tables. */
        val tables: Map<String, TableClass> =
            (
                listOf(
                    "outbox",
                    JobSchema.JOB_RUN,
                    JobSchema.JOB_PAUSE,
                ).associate { "$SCHEMA.$it" to TableClass.TENANT } +
                    ("public.captured_actor" to TableClass.PLATFORM_SCOPED) + CATALOG_TABLES +
                    (JobSchema.platformScopedTables(SCHEMA) + OutboxSchema.platformScopedTables(SCHEMA))
                        .associateWith { TableClass.PLATFORM_SCOPED }
            ) + extraTables

        /** Reads and writes over the owner, runtime and admin logins, each through its own TransactionRunner. */
        val owner: TestDatabase.Access
        val admin: TestDatabase.Access
        private val db = TestDatabase(fixture.jdbcUrl(), tables)

        init {
            owner = db.login(OutboxPostgresFixture.OWNER, OutboxPostgresFixture.OWNER_PASSWORD)
            admin = db.login(fixture.adminUser(), fixture.adminPassword())
            writeRetention(JobRuntime.DEFAULT_RETENTION)
        }

        /** The migration runner's job-retention row (INV-Q-4346), written as the owner under the platform binding. */
        fun writeRetention(retention: Duration) {
            val id = DSL.field(DSL.name("id"), SQLDataType.BOOLEAN)
            val window = DSL.field(DSL.name("job_window"), SQLDataType.INTERVALDAYTOSECOND)
            val updated = DSL.field(DSL.name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
            val value = DayToSecond.valueOf(retention)
            owner.asPlatform {
                insertInto(DSL.table(DSL.name(SCHEMA, JobSchema.JOB_RETENTION)))
                    .columns(id, window, updated)
                    .values(true, value, OffsetDateTime.now())
                    .onConflict(id)
                    .doUpdate()
                    .set(window, value)
                    .set(updated, OffsetDateTime.now())
                    .execute()
            }
        }

        inner class Replica(
            val instance: String,
            val clock: Clock,
            authorizer: JobCommandAuthorizer,
            deadlines: DeadlineSource,
            executor: Executor,
            retention: Duration,
            poolMaximum: Int = TestPoolFixture.DEFAULT_MAXIMUM,
            connectionTimeoutMillis: Long = TestPoolFixture.DEFAULT_TIMEOUT_MILLIS,
        ) {
            private val replicaDb = TestDatabase(fixture.jdbcUrl(), tables)
            private val app =
                replicaDb.login(
                    OutboxPostgresFixture.APP,
                    OutboxPostgresFixture.APP_PASSWORD,
                    poolMaximum,
                    connectionTimeoutMillis = connectionTimeoutMillis,
                )
            val tenants: TenantContextImpl = replicaDb.tenants
            val tx = app.runner
            val dsl = app.dsl

            /** The replica's pool as a DataSource bean for a Spring context; no test takes a connection from it. */
            val dataSource: DataSource = app.dataSource
            val scope = TenantScope(tenants, tx, tx, WorkloadPrincipal(SERVICE).value)
            val outbox = JdbcOutboxWriter(tx, SCHEMA, SERVICE, instance, CATALOGUE, clock)
            val runtime =
                JobRuntime(
                    SERVICE,
                    SCHEMA,
                    scope,
                    dsl,
                    outbox,
                    OutboxAuditWriter(outbox),
                    authorizer,
                    deadlines,
                    clock,
                    executor,
                    retention,
                )

            /** Binds [tenant] with a test operator's actor (INV-REQ-854 b1). */
            fun <T> asTenant(
                tenant: TenantId,
                block: () -> T,
            ): T = tenants.bind(tenant, TEST_ACTOR, block)
        }

        fun replica(
            instance: String,
            clock: Clock,
            authorizer: JobCommandAuthorizer = FakeAuthorizer(),
            deadlines: DeadlineSource = FixedDeadlineSource(),
            executor: Executor = Executor { it.run() },
            retention: Duration = JobRuntime.DEFAULT_RETENTION,
            poolMaximum: Int = TestPoolFixture.DEFAULT_MAXIMUM,
            connectionTimeoutMillis: Long = TestPoolFixture.DEFAULT_TIMEOUT_MILLIS,
        ) = Replica(instance, clock, authorizer, deadlines, executor, retention, poolMaximum, connectionTimeoutMillis)

        /** Forgets what an earlier call captured on [table] (the trigger exists from the start). */
        fun captureActors(table: String) {
            admin.asPlatform {
                deleteFrom(DSL.table(DSL.name("public", "captured_actor")))
                    .where(DSL.field(DSL.name("tbl")).eq(table))
                    .execute()
            }
        }

        /** The `app.actor` of each row inserted into [table] since [captureActors], in insert order. */
        fun capturedActors(table: String): List<String?> =
            admin.asPlatform {
                select(DSL.field(DSL.name("actor"), SQLDataType.CLOB))
                    .from(DSL.table(DSL.name("public", "captured_actor")))
                    .where(DSL.field(DSL.name("tbl")).eq(table))
                    .orderBy(DSL.field(DSL.name("seq")))
                    .fetch(DSL.field(DSL.name("actor"), SQLDataType.CLOB))
            }

        /** The tenants the tests create; [rows] reads each of them and the platform tenant in turn. */
        val knownTenants: MutableSet<TenantId> = mutableSetOf(TENANT_A, TENANT_B)

        /** Rows of `schema.table` where each [equal] column equals its value, of every tenant the test created. */
        fun rows(
            table: String,
            vararg equal: Pair<String, Any?>,
        ): List<Map<String, Any?>> = admin.rowsOf(table, knownTenants, *equal)

        /** Outbox rows of [type] for [job]'s aggregate. */
        fun events(
            type: String,
            aggregate: String,
        ) = rows("$SCHEMA.outbox", "type" to type, "subject" to aggregate).sortedBy { (it["seq"] as Number).toLong() }

        fun runs(job: String) =
            rows("$SCHEMA.job_run", "job" to job)
                .sortedWith(compareBy({ instantOf(it["tick"]) }, { it["trigger"] as String }, { it["attempt"] as Int }))

        /** Idempotency rows of [key], of every tenant the test created and the platform tenant. */
        fun keyRows(key: String) = rows("$SCHEMA.idempotency_key", "idempotency_key" to UUID.fromString(key))

        /** Moves `created_at` of the idempotency keys (all, or [key]'s) back by [age], as the superuser login. */
        fun ageKeys(
            age: Duration,
            key: String? = null,
        ) = updateKeys(key) { created -> created.minus(DSL.`val`(DayToSecond.valueOf(age))) }

        /** Sets `created_at` of [key]'s row to [age] before the database clock's now(). */
        fun setKeyAge(
            key: String,
            age: Duration,
        ) = updateKeys(key) { DSL.currentOffsetDateTime().minus(DSL.`val`(DayToSecond.valueOf(age))) }

        private fun updateKeys(
            key: String?,
            value: (Field<OffsetDateTime>) -> Field<OffsetDateTime>,
        ) {
            val created = DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
            val table = DSL.table(DSL.name(SCHEMA, "idempotency_key"))
            val matching =
                key?.let { DSL.field(DSL.name("idempotency_key"), SQLDataType.UUID).eq(UUID.fromString(it)) }
                    ?: DSL.noCondition()
            val update: DSLContext.() -> Unit = { update(table).set(created, value(created)).where(matching).execute() }
            knownTenants.filter { !it.isPlatform }.forEach { admin.asTenant(it, block = update) }
            admin.asPlatform(block = update)
        }

        private val extraLogins = mutableListOf<TestDatabase>()

        /**
         * The runtime login's own access with [tables] declared PLATFORM_SCOPED (no tenant predicate is injected), for the
         * tests that prove what the database itself refuses that login.
         */
        fun runtimeLogin(vararg tables: String): TestDatabase.Access {
            val database = TestDatabase(fixture.jdbcUrl(), tables.associateWith { TableClass.PLATFORM_SCOPED })
            extraLogins += database
            return database.login(OutboxPostgresFixture.APP, OutboxPostgresFixture.APP_PASSWORD)
        }

        override fun close() {
            extraLogins.forEach { it.close() }
            db.close()
            fixture.close()
        }

        companion object {
            const val SCHEMA = "submission"
            const val SERVICE = "submission-svc"
            const val TEST_ACTOR = "test-operator"
            const val AUDIT = "com.dynokore.gst.platform.AuditEntryRecorded.v1"

            private fun entry(
                type: String,
                topic: String,
            ) = CatalogueEntry(type, topic, setOf(CatalogueEntry.EVERY_SERVICE))

            val CATALOGUE =
                EventCatalogue(
                    listOf(
                        entry(JobPayloads.JOB_REGISTERED, "gst.platform.job-registered.v1"),
                        entry(JobPayloads.JOB_RUN_RECORDED, "gst.platform.job-run-recorded.v1"),
                        entry(JobPayloads.CONNECTION_STATUS_CHANGED, "gst.platform.connection-status-changed.v1"),
                        entry(AUDIT, "gst.platform.audit-entry-recorded.v1"),
                    ),
                )
            val TENANT_A = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a"))
            val TENANT_B = TenantId(UUID.fromString("0b000000-0000-0000-0000-00000000000b"))
            val E1 = EntityRef("E-001")
            val E2 = EntityRef("E-002")

            /**
             * Five seconds past the current hour (INV-Q-4638): the job tables are aged by the database's `now()`
             * (retention purge, ShedLock's database time), so START follows the real clock instead of a fixed date.
             * `INV_TEST_BASE_INSTANT` (an ISO instant) replaces the real clock as the base, to prove the tests do
             * not depend on the calendar date.
             */
            val START: Instant =
                (System.getenv("INV_TEST_BASE_INSTANT")?.let(Instant::parse) ?: Instant.now())
                    .truncatedTo(ChronoUnit.HOURS)
                    .plusSeconds(5)

            /** The top of START's hour, the hourly cron tick START falls in. */
            val START_HOUR: Instant = START.truncatedTo(ChronoUnit.HOURS)

            /** A timestamptz read by [rows] as an instant, independent of the JVM's time zone. */
            fun instantOf(value: Any?): Instant =
                when (value) {
                    is java.time.OffsetDateTime -> value.toInstant()
                    else -> error("not a timestamp: $value")
                }

            fun job(
                name: String,
                scope: JobEntityScope = JobEntityScope.ENTITY,
                criticality: JobCriticality = JobCriticality.STANDARD,
                cron: String = "0 0 * * * *",
                lockAtMostFor: Duration = JobDefinition.DEFAULT_LOCK_AT_MOST_FOR,
            ) = JobDefinition(
                name,
                SERVICE,
                scope,
                JobSchedule(cron),
                criticality,
                Duration.ofMinutes(15),
                lockAtMostFor,
                Duration.ZERO,
            )

            fun request(
                actor: String,
                job: String,
                entity: EntityRef?,
                reason: String? = null,
            ) = JobCommandRequest(
                actor,
                job,
                entity,
                UUID.randomUUID(),
                reason,
                CorrelationId("corr-$actor"),
                CausationId("cmd-$actor"),
            )
        }
    }
