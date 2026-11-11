package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.gst.aggregation.application.PeriodSpans
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.jobs.DeadlineSource
import com.dynokore.platform.serviceruntime.jobs.FireResult
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobEntitySource
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobTarget
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
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The scheduled closing run under two [JobRuntime] instances over one database (INV-REQ-413 item 2, INV-Q-14448 A):
 * the window of the week 28 Mar to 3 Apr 2022, the spans of the quarters ending 31 Mar and 30 Jun, WEEKLY cadence.
 */
class WindowCloseJobBoundaryTest {
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
                Clock.systemUTC(),
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
            "select data->>'state' from aggregation.outbox " +
                "where tenant_id = ? and type like '%AggregateWindowChanged%'",
            tenant,
        ) {
            it.getString(1)
        }

    @Test
    fun `INV-AC-1574 the registered job runs once per tick over two instances and a second firing closes nothing again`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, PeriodSpans.Known(spans)) { context, tenant, second ->
            val first = context.getBean(JobRuntime::class.java)
            val at = tickOf(LocalDate.of(2022, 4, 3))
            val results = listOf(first.fire(WindowCloseJob.NAME, at), second.fire(WindowCloseJob.NAME, at))
            val target = JobTarget.Entity(TenantId(tenant), EntityRef("E-001"))
            results.map { it[target] }.count { it == FireResult.RAN } shouldBe 1
            jobRuns(tenant) shouldContainExactly listOf("SUCCEEDED")
            val closed = aggregates(tenant)
            events(tenant).sorted() shouldContainExactly List(closed.size) { "CLOSED" }

            // the same tick again, from either instance: no run, no aggregate, no event
            first.fire(WindowCloseJob.NAME, at)
            second.fire(WindowCloseJob.NAME, at)
            jobRuns(tenant) shouldContainExactly listOf("SUCCEEDED")
            aggregates(tenant) shouldContainExactly closed
            events(tenant).size shouldBe closed.size
        }

    @Test
    fun `a window for which no span is known stays open and nothing is created or published`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, PeriodSpans.Unknown) { context, tenant, _ ->
            context.getBean(JobRuntime::class.java).fire(WindowCloseJob.NAME, tickOf(LocalDate.of(2022, 4, 3)))
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
