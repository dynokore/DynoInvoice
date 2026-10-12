package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import javax.sql.DataSource

/** A settable clock shared by the replicas of a test. */
class MutableClock(var now: Instant) : Clock() {
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
class MovableDeadlines(var margin: Duration = Duration.ofHours(1)) : DeadlineSource {
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
 * the job tables of [JobSchema], and outbox-and-queue's fixture roles (app, worker). Each [replica] is one
 * service instance with its own transaction runner, outbox writer (instance id) and [JobRuntime].
 */
class JobsHarness(image: String) : AutoCloseable {
    val fixture = OutboxPostgresFixture(image)

    init {
        fixture.createService(SCHEMA, JobSchema.ddl(SCHEMA, OutboxPostgresFixture.APP, OutboxPostgresFixture.WORKER))
    }

    inner class Replica(
        val instance: String,
        val clock: Clock,
        authorizer: JobCommandAuthorizer,
        deadlines: DeadlineSource,
        executor: Executor,
        retention: Duration,
        app: DataSource,
    ) {
        val tenants = TenantContextImpl()
        val tx = TransactionRunnerImpl(app, tenants)
        val scope = TenantScope(tenants, tx, tx)
        val outbox = JdbcOutboxWriter(tx, SCHEMA, SERVICE, instance, CATALOGUE, clock)
        val runtime =
            JobRuntime(
                SERVICE, SCHEMA, scope, outbox, OutboxAuditWriter(outbox), fixture.workerDataSource(), authorizer,
                deadlines, clock, executor, retention,
            )

        fun <T> asTenant(
            tenant: TenantId,
            block: () -> T,
        ): T = tenants.bind(tenant, block)
    }

    fun replica(
        instance: String,
        clock: Clock,
        authorizer: JobCommandAuthorizer = FakeAuthorizer(),
        deadlines: DeadlineSource = FixedDeadlineSource(),
        executor: Executor = Executor { it.run() },
        retention: Duration = JobRuntime.DEFAULT_RETENTION,
        app: DataSource = fixture.appDataSource(),
    ) = Replica(instance, clock, authorizer, deadlines, executor, retention, app)

    /** Rows of [sql] as the superuser (every tenant). */
    fun rows(
        sql: String,
        vararg args: Any,
    ): List<Map<String, Any?>> = fixture.adminDataSource().connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                val meta = rows.metaData
                buildList {
                    while (rows.next()) {
                        add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rows.getObject(it) })
                    }
                }
            }
        }
    }

    /** Outbox rows of [type] for [job]'s aggregate. */
    fun events(
        type: String,
        aggregate: String,
    ) = rows("select * from $SCHEMA.outbox where type = ? and subject = ? order by seq", type, aggregate)

    fun runs(job: String) = rows("select * from $SCHEMA.job_run where job = ? order by tick, trigger, attempt", job)

    override fun close() = fixture.close()

    companion object {
        const val SCHEMA = "submission"
        const val SERVICE = "submission-svc"
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
        val START: Instant = Instant.parse("2026-10-12T00:00:05Z")

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
