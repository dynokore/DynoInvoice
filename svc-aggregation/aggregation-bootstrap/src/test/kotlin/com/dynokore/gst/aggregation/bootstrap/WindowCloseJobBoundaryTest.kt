package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.gst.aggregation.application.PeriodSpans
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.jobs.DeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobEntitySource
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The scheduled closing run under two [JobRuntime] instances over one database (INV-REQ-413 item 2, INV-Q-14448 A):
 * the window of the week 28 Mar to 3 Apr 2022, the spans of the quarters ending 31 Mar and 30 Jun, WEEKLY cadence.
 */
class WindowCloseJobBoundaryTest {
    /** One injected clock for both instances and the service (ARC-13, P160): the ticks are fixed 2022 instants. */
    private class TestClock : Clock() {
        val now = AtomicReference(Instant.parse("2022-03-31T00:00:00Z"))

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now.get()
    }

    private val clock = TestClock()

    private val spans =
        listOf(
            PeriodSpan(LocalDate.of(2022, 1, 1), LocalDate.of(2022, 3, 31), "2022-Q1"),
            PeriodSpan(LocalDate.of(2022, 4, 1), LocalDate.of(2022, 6, 30), "2022-Q2"),
        )

    /** The tick whose business date is [date]: the firing just after midnight Singapore of the next day. */
    private fun tickOf(date: LocalDate): Instant =
        date
            .plusDays(1)
            .atStartOfDay()
            .minusHours(SGT_HOURS)
            .plusMinutes(5)
            .toInstant(ZoneOffset.UTC)

    private fun run(
        dir: Path,
        known: PeriodSpans,
        block: (ApplicationContext, UUID, JobRuntime) -> Unit,
    ) {
        AggregationContext
            .online(dir)
            .withAllowBeanDefinitionOverriding(true)
            .withBean("periodBoundarySource", PeriodBoundarySource::class.java, {
                PeriodBoundarySource {
                    _,
                    _,
                    _,
                    ->
                    known
                }
            })
            .withBean("clock", Clock::class.java, { clock })
            .withBean("cadenceSource", CadenceSource::class.java, { CadenceSource { _, _, _ -> Cadence.WEEKLY } })
            .run { context ->
                context.startupFailure?.let { throw it }
                val tenant = tenant(context)
                openWindow(tenant)
                val second = secondRuntime(context)
                block(context, tenant, second)
            }
    }

    private fun tenant(context: ApplicationContext): UUID =
        UUID.randomUUID().also { tenant ->
            val structure = context.getBean(StructureReplica::class.java)
            listOf("E-001" to StructureNodeKind.ENTITY, "BU-01" to StructureNodeKind.BUSINESS_UNIT).forEach {
                val code = if (it.first == "E-001") "E" else "B"
                structure.accept(
                    OrganisationStructureChanged(
                        TenantId(tenant),
                        it.first,
                        StructureChange.UPDATED,
                        StructureNode(it.second, null, false, it.first, code, emptyList(), 2),
                    ),
                )
            }
        }

    private fun openWindow(tenant: UUID) =
        AggregationDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "insert into aggregation.aggregate_window (window_id, tenant_id, entity_ref, business_unit_ref, " +
                        "aggregate_type, cadence, window_start, window_end, state) select gen_random_uuid(), ?, " +
                        "'E-001', 'BU-01', 'PCP', 'WEEKLY', date '2022-03-28', date '2022-04-03', 'OPEN'",
                ).use {
                    it.setObject(1, tenant)
                    it.executeUpdate()
                }
        }

    /** A second instance of the service: its own runtime over the same tables, with the same job and body. */
    private fun secondRuntime(context: ApplicationContext): JobRuntime {
        val runtime =
            JobRuntime(
                WindowCloseJob.SERVICE,
                "aggregation",
                context.getBean(TenantScope::class.java),
                context.getBean(DSLContext::class.java),
                context.getBean(OutboxWriter::class.java),
                context.getBean(AuditWriter::class.java),
                context.getBean(JobCommandAuthorizer::class.java),
                context.getBean(DeadlineSource::class.java),
                clock,
                Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofDays(30),
            )
        val first = context.getBean(WindowCloseJob::class.java)
        runtime.register(WindowCloseJob.DEFINITION, context.getBean(JobEntitySource::class.java), first.body)
        return runtime
    }

    private fun <T> query(
        sql: String,
        tenant: UUID,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        AggregationDb.superuser().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenant)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) read(rows) else null }.toList()
                }
            }
        }

    private fun jobRuns(tenant: UUID) =
        query(
            "select status from aggregation.job_run where tenant_id = ? and job = 'aggregation-window-close'",
            tenant,
        ) {
            it.getString(1)
        }

    private fun aggregates(tenant: UUID) =
        query(
            "select period_start || '..' || period_end from aggregation.aggregate where tenant_id = ? order by 1",
            tenant,
        ) {
            it.getString(1)
        }

    private fun events(tenant: UUID) =
        query(
            "select (data->>'state') || ':' || (data->'window'->>'to') from aggregation.outbox " +
                "where tenant_id = ? and type like '%AggregateWindowChanged%'",
            tenant,
        ) {
            it.getString(1)
        }

    /**
     * The lease times of ShedLock come from the database clock (real time), not from the injected clock, so the days
     * between two fixed ticks are simulated by lapsing the leases: only the `job_run` key then stops a repeated tick.
     */
    private fun expireLeases() =
        AggregationDb.superuser().use { connection ->
            connection.createStatement().use {
                it.executeUpdate("update aggregation.shedlock set lock_until = lock_until - interval '3 days'")
            }
        }

    /** Both instances fire the same tick at once: a barrier makes the lock contended. */
    private fun fireBoth(
        runtimes: List<JobRuntime>,
        at: Instant,
    ) {
        clock.now.set(at)
        expireLeases()
        val barrier = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(runtimes.size)
        try {
            runtimes
                .map { runtime ->
                    pool.submit {
                        barrier.await()
                        runtime.fire(WindowCloseJob.NAME, at)
                    }
                }.also { barrier.countDown() }
                .forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `INV-AC-1574 the part up to 31 Mar closes on its tick, the part from 1 Apr on the tick of 3 Apr, once per tick`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, PeriodSpans.Known(spans)) { context, tenant, second ->
            val runtimes = listOf(context.getBean(JobRuntime::class.java), second)

            fireBoth(runtimes, tickOf(LocalDate.of(2022, 3, 31)))
            jobRuns(tenant) shouldContainExactly listOf("SUCCEEDED")
            aggregates(tenant) shouldContainExactly listOf("2022-03-28..2022-03-31")
            events(tenant) shouldContainExactly listOf("CLOSED:2022-03-31")

            // the same tick again, from either instance: no run, no aggregate, no event
            fireBoth(runtimes, tickOf(LocalDate.of(2022, 3, 31)))
            jobRuns(tenant) shouldContainExactly listOf("SUCCEEDED")
            aggregates(tenant) shouldContainExactly listOf("2022-03-28..2022-03-31")
            events(tenant) shouldContainExactly listOf("CLOSED:2022-03-31")

            // the part from 1 Apr closes on the tick of 3 Apr only
            fireBoth(runtimes, tickOf(LocalDate.of(2022, 4, 3)))
            jobRuns(tenant) shouldContainExactly listOf("SUCCEEDED", "SUCCEEDED")
            aggregates(tenant) shouldContainExactly listOf("2022-03-28..2022-03-31", "2022-04-01..2022-04-03")
            events(tenant).sorted() shouldContainExactly listOf("CLOSED:2022-03-31", "CLOSED:2022-04-03")
        }

    @Test
    fun `a window for which no span is known stays open and nothing is created or published`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, PeriodSpans.Unknown) { context, tenant, _ ->
            val at = tickOf(LocalDate.of(2022, 4, 3))
            clock.now.set(at)
            context.getBean(JobRuntime::class.java).fire(WindowCloseJob.NAME, at)
            jobRuns(tenant) shouldContainExactly listOf("SUCCEEDED")
            aggregates(tenant) shouldContainExactly emptyList()
            events(tenant) shouldContainExactly emptyList()
            query("select state from aggregation.aggregate_window where tenant_id = ?", tenant) { it.getString(1) }
                .shouldContainExactly("OPEN")
        }

    private companion object {
        const val SGT_HOURS = 8L
    }
}
