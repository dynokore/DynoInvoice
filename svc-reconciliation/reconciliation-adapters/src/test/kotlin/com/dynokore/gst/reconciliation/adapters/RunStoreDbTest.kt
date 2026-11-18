package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.http.ReconciliationController
import com.dynokore.gst.reconciliation.adapters.http.RunsController
import com.dynokore.gst.reconciliation.adapters.persistence.JooqCompletedRuns
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunReader
import com.dynokore.gst.reconciliation.application.run.GetRun
import com.dynokore.gst.reconciliation.application.run.ListRuns
import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.application.run.RunCursor
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.contract.model.ErrorEnvelope
import com.dynokore.gst.reconciliation.contract.model.Run
import com.dynokore.gst.reconciliation.contract.model.RunPage
import com.dynokore.gst.reconciliation.domain.boxes.UnmappedLine
import com.dynokore.gst.reconciliation.domain.run.NotReconcilableDocument
import com.dynokore.gst.reconciliation.domain.run.RunOutcome
import com.dynokore.gst.reconciliation.domain.sgd.NotReconcilableReason
import com.dynokore.platform.reconciliationengine.Category
import com.dynokore.platform.reconciliationengine.ClassifiedVariance
import com.dynokore.platform.reconciliationengine.Comparison
import com.dynokore.platform.reconciliationengine.LineResult
import com.dynokore.platform.reconciliationengine.MissingSide
import com.dynokore.platform.reconciliationengine.ReconciliationResult
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.http.ResponseEntity
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * INV-AC-24375 at the database (INV-T-24376): the run store and the `listRuns` and `getRun` reads over a real
 * PostgreSQL schema with the runtime login, the real transaction runner of the tenancy kernel and the real adapters
 * (TPL-18); the only stand-in is the tenant binding the test sets the way the request filter does.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RunStoreDbTest {
    private companion object {
        const val USER = "user-U1"
        const val ENTITY = "E1"
        const val PERIOD = "P1"
        val tables =
            listOf(
                "reconciliation_run",
                "reconciliation_run_box",
                "reconciliation_run_variance",
                "reconciliation_run_unmapped_line",
                "reconciliation_run_not_reconcilable",
            )
    }

    private class Session {
        val tenants = TenantContextImpl()
        val runner =
            TransactionRunnerImpl(
                PGSimpleDataSource().apply {
                    setUrl(ReconciliationDb.jdbcUrl)
                    user = ReconciliationDb.RUNTIME
                    password = ReconciliationDb.RUNTIME_PASSWORD
                },
                tenants,
                null,
                object : BindingKeySource {
                    override fun currentKey(): ByteArray = ReconciliationDb.bindingKey.copyOf()

                    override fun previousKey(): ByteArray? = null
                },
            )

        fun dsl(): DSLContext = DSL.using(runner.require(), SQLDialect.POSTGRES)
    }

    /** Counts the reads that reach the store, to show a refused request reads nothing. */
    private class CountingReader(
        private val inner: RunReader,
    ) : RunReader {
        var calls = 0

        override fun latest(
            tenant: String,
            entityRef: String,
            periodRef: String,
        ) = inner.latest(tenant, entityRef, periodRef).also { calls++ }

        override fun page(
            tenant: String,
            entityRef: String,
            periodRef: String,
            limit: Int,
            after: RunCursor?,
        ) = inner.page(tenant, entityRef, periodRef, limit, after).also { calls++ }

        override fun find(
            tenant: String,
            runId: String,
        ) = inner.find(tenant, runId).also { calls++ }
    }

    private val session = Session()
    private val reader =
        CountingReader(
            object : RunReader {
                override fun latest(
                    tenant: String,
                    entityRef: String,
                    periodRef: String,
                ) = JooqRunReader(session.dsl()).latest(tenant, entityRef, periodRef)

                override fun page(
                    tenant: String,
                    entityRef: String,
                    periodRef: String,
                    limit: Int,
                    after: RunCursor?,
                ) = JooqRunReader(session.dsl()).page(tenant, entityRef, periodRef, limit, after)

                override fun find(
                    tenant: String,
                    runId: String,
                ) = JooqRunReader(session.dsl()).find(tenant, runId)
            },
        )
    private val controller = RunsController(ListRuns(reader), GetRun(reader), session.runner, session.tenants)

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T = session.tenants.bind(TenantId(tenant), USER, block)

    private fun storeReturn(
        tenant: UUID,
        version: Int = 2,
        form: String = "F5",
    ) = ReconciliationDb.superuser().use { c ->
        c.prepareStatement(
            "insert into reconciliation.gst_return (tenant_id, entity_ref, period_ref, version, form, filed_at, " +
                "filing_acknowledgement, uploaded_by, recorded_at) values (?, ?, ?, ?, ?, now(), 'ACK', 'u', now())",
        ).use {
            it.setObject(1, tenant)
            it.setString(2, ENTITY)
            it.setString(3, PERIOD)
            it.setInt(4, version)
            it.setString(5, form)
            it.executeUpdate()
        }
    }

    private fun outcome(
        version: Int = 2,
        submitted: String = "100.00",
    ) = RunOutcome.Completed(
        returnVersion = version,
        result =
            ReconciliationResult(
                listOf(
                    LineResult(
                        Comparison("1", BigDecimal("90.00"), BigDecimal(submitted), BigDecimal("10.00"), MissingSide.NONE),
                        listOf(
                            ClassifiedVariance(
                                "1",
                                Category("TIMING_DIFFERENCE", "Timing difference"),
                                BigDecimal("6.00"),
                                listOf("DOC-1"),
                            ),
                        ),
                        BigDecimal("4.00"),
                    ),
                ),
            ),
        unmappedLines = listOf(UnmappedLine("doc-9", 1, 3, "XX")),
        notReconcilable = listOf(NotReconcilableDocument("doc-8", 1, NotReconcilableReason.NO_LINES)),
        raisesPer003 = true,
        reconcilingItems = emptyList(),
    )

    private fun record(
        tenant: UUID,
        at: String,
        outcome: RunOutcome.Completed = outcome(),
    ): RecordedRun =
        asTenant(tenant) {
            session.runner.inTransaction {
                JooqCompletedRuns(session.dsl()).record(
                    tenant.toString(),
                    ENTITY,
                    PERIOD,
                    outcome,
                    Instant.parse(at),
                )
            }
        }

    private fun list(
        tenant: UUID,
        limit: Int = 100,
        cursor: String? = null,
    ): ResponseEntity<RunPage> = asTenant(tenant) { controller.listRuns(ENTITY, PERIOD, limit, cursor) }

    private fun get(
        tenant: UUID,
        runId: String,
    ): ResponseEntity<Run> = asTenant(tenant) { controller.getRun(UUID.fromString(runId)) }

    private fun scalar(sql: String): Any? =
        ReconciliationDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use {
                    it.next()
                    it.getObject(1)
                }
            }
        }

    private fun runtimeSqlState(
        tenant: UUID,
        sql: String,
    ): String? {
        val failure =
            shouldThrow<Exception> {
                asTenant(tenant) { session.runner.inTransaction { session.dsl().execute(sql) } }
            }
        return generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState
    }

    private fun envelope(response: ResponseEntity<*>): ErrorEnvelope = response.body as ErrorEnvelope

    @Test
    fun `INV-AC-24375 each record call returns a distinct runId and stores one row per run, the earlier kept unchanged`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val first = record(t1, "2026-04-28T09:10:00Z")
        val rowBefore = scalar("select row(r.*)::text from reconciliation.reconciliation_run r where run_id = '${first.runId}'")
        val second = record(t1, "2026-04-29T09:10:00Z")
        first.runId shouldNotBe second.runId
        scalar("select count(*) from reconciliation.reconciliation_run where tenant_id = '$t1'") shouldBe 2L
        scalar("select row(r.*)::text from reconciliation.reconciliation_run r where run_id = '${first.runId}'") shouldBe rowBefore
        scalar("select return_form from reconciliation.reconciliation_run where run_id = '${first.runId}'") shouldBe "F5"
        scalar("select return_version from reconciliation.reconciliation_run where run_id = '${second.runId}'") shouldBe 2
        scalar("select raises_per003 from reconciliation.reconciliation_run where run_id = '${first.runId}'") shouldBe true
        scalar("select count(*) from reconciliation.reconciliation_run_box where tenant_id = '$t1'") shouldBe 2L
        scalar("select count(*) from reconciliation.reconciliation_run_unmapped_line where tenant_id = '$t1'") shouldBe 2L
        scalar("select count(*) from reconciliation.reconciliation_run_not_reconcilable where tenant_id = '$t1'") shouldBe 2L
        scalar("select reason from reconciliation.reconciliation_run_not_reconcilable where run_id = '${first.runId}'") shouldBe "NO_LINES"
    }

    @Test
    fun `INV-AC-24375 the stored Box and variance rows keep the amounts at scale 2`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val run = record(t1, "2026-04-28T09:10:00Z")
        val box =
            ReconciliationDb.superuser().use { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "select submitted_amount::text, filed_amount::text, variance::text, variance_class, " +
                                "unexplained_residual::text from reconciliation.reconciliation_run_box " +
                                "where run_id = '${run.runId}' and box = '1'",
                        ).use {
                            it.next()
                            (1..5).map { i -> it.getString(i) }
                        }
                }
            }
        box shouldBe listOf("100.00", "90.00", "10.00", "TIMING_DIFFERENCE", "4.00")
        scalar("select amount::text from reconciliation.reconciliation_run_variance where run_id = '${run.runId}'") shouldBe "6.00"
        scalar("select linked_records[1] from reconciliation.reconciliation_run_variance where run_id = '${run.runId}'") shouldBe "DOC-1"
        scalar("select unexplained_residual::text from reconciliation.reconciliation_run where run_id = '${run.runId}'") shouldBe "4.00"
    }

    @Test
    fun `INV-AC-24375 a cent amount is stored at scale 2 and not rounded to one decimal`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val run = record(t1, "2026-04-28T09:10:00Z", outcome(submitted = "100.05"))
        scalar("select submitted_amount::text from reconciliation.reconciliation_run_box where run_id = '${run.runId}'") shouldBe "100.05"
    }

    @Test
    fun `INV-AC-24375 find names the tenant itself, so a connection that bypasses row-level security reads one tenant`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        storeReturn(t1)
        val run = record(t1, "2026-04-28T09:10:00Z")
        ReconciliationDb.superuser().use { c ->
            val dsl = DSL.using(c, SQLDialect.POSTGRES)
            JooqRunReader(dsl).find(t2.toString(), run.runId) shouldBe null
            JooqRunReader(dsl).find(t1.toString(), run.runId)!!.run.runId shouldBe run.runId
            JooqRunReader(dsl).page(t2.toString(), ENTITY, PERIOD, 10, null) shouldBe emptyList()
            JooqRunReader(dsl).latest(t2.toString(), ENTITY, PERIOD) shouldBe null
        }
    }

    @Test
    fun `INV-AC-24375 a missing return row is an IllegalStateException and nothing is stored`() {
        val t1 = UUID.randomUUID()
        shouldThrow<IllegalStateException> { record(t1, "2026-04-28T09:10:00Z") }
        scalar("select count(*) from reconciliation.reconciliation_run where tenant_id = '$t1'") shouldBe 0L
    }

    @Test
    fun `INV-AC-24375 the form is read from the stored return of the recorded version`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1, version = 1, form = "F7")
        storeReturn(t1, version = 2, form = "F5")
        val one = record(t1, "2026-04-28T09:10:00Z", outcome(version = 1))
        get(t1, one.runId).body?.returnForm shouldBe Run.ReturnForm.F7
    }

    @Test
    fun `INV-AC-24375 listRuns returns both runs newest first and getRun returns the older run as stored`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val older = record(t1, "2026-04-28T09:10:00Z")
        val newer = record(t1, "2026-04-29T09:10:00Z")
        val page = list(t1)
        page.statusCode.value() shouldBe 200
        val items = page.body!!.items
        items.map { it.runId.toString() } shouldBe listOf(newer.runId, older.runId)
        val first = items.last()
        first.entityRef shouldBe ENTITY
        first.periodRef shouldBe PERIOD
        first.completedAt shouldBe Instant.parse("2026-04-28T09:10:00Z").atOffset(ZoneOffset.UTC)
        first.returnVersion shouldBe 2
        first.returnForm shouldBe Run.ReturnForm.F5
        val read = get(t1, older.runId)
        read.statusCode.value() shouldBe 200
        read.body shouldBe first
        // keyset paging: one run per page, then the cursor continues strictly after the last row
        val p1 = list(t1, limit = 1).body!!
        p1.items.map { it.runId.toString() } shouldBe listOf(newer.runId)
        val p2 = list(t1, limit = 1, cursor = p1.nextCursor).body!!
        p2.items.map { it.runId.toString() } shouldBe listOf(older.runId)
        p2.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-24375 every read as T2 returns no run of T1 and getRun answers 404`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        storeReturn(t1)
        val run = record(t1, "2026-04-28T09:10:00Z")
        list(t2).body!!.items shouldBe emptyList()
        val missing = get(t2, run.runId)
        missing.statusCode.value() shouldBe 404
        envelope(missing).code shouldBe "SRT-005"
        asTenant(t2) { session.runner.inTransaction { JooqRunReader(session.dsl()).latest(t2.toString(), ENTITY, PERIOD) } } shouldBe null
        asTenant(t1) { session.runner.inTransaction { JooqRunReader(session.dsl()).latest(t1.toString(), ENTITY, PERIOD) } }!!.run.runId shouldBe run.runId
        get(t1, run.runId).statusCode.value() shouldBe 200
    }

    @Test
    fun `INV-AC-24375 an UPDATE or DELETE of a run row fails with SQLSTATE 42501 in each run table`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        record(t1, "2026-04-28T09:10:00Z")
        tables.forEach { table ->
            runtimeSqlState(t1, "delete from reconciliation.$table where tenant_id = '$t1'") shouldBe "42501"
        }
        runtimeSqlState(t1, "update reconciliation.reconciliation_run set entity_ref = 'X' where tenant_id = '$t1'") shouldBe "42501"
        runtimeSqlState(t1, "update reconciliation.reconciliation_run_box set box = 'X' where tenant_id = '$t1'") shouldBe "42501"
        runtimeSqlState(t1, "update reconciliation.reconciliation_run_variance set box = 'X' where tenant_id = '$t1'") shouldBe "42501"
        runtimeSqlState(t1, "update reconciliation.reconciliation_run_unmapped_line set tax_code = 'X' where tenant_id = '$t1'") shouldBe "42501"
        scalar("select count(*) from reconciliation.reconciliation_run where tenant_id = '$t1'") shouldBe 1L
    }

    @Test
    fun `INV-AC-24375 the append-only trigger of each run table refuses update and delete even for its owner`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        record(t1, "2026-04-28T09:10:00Z")
        tables.forEach { table ->
            val state =
                ReconciliationDb.superuser().use { c ->
                    c.createStatement().use { st ->
                        try {
                            st.executeUpdate("delete from reconciliation.$table where tenant_id = '$t1'")
                            "none"
                        } catch (e: SQLException) {
                            e.sqlState
                        }
                    }
                }
            state shouldBe "42501"
        }
    }

    @Test
    fun `INV-AC-24375 a rollback after record leaves no run row`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        shouldThrow<IllegalStateException> {
            asTenant(t1) {
                session.runner.inTransaction {
                    JooqCompletedRuns(session.dsl()).record(
                        t1.toString(),
                        ENTITY,
                        PERIOD,
                        outcome(),
                        Instant.parse("2026-04-28T09:10:00Z"),
                    )
                    error("rollback")
                }
            }
        }
        tables.forEach { scalar("select count(*) from reconciliation.$it where tenant_id = '$t1'") shouldBe 0L }
    }

    @Test
    fun `INV-AC-24375 getReconciliation and every other operation of both controllers still answer 501`() {
        val id = UUID.randomUUID()
        ReconciliationController().getReconciliation(ENTITY, PERIOD).statusCode.value() shouldBe 501
        controller.getWorkPaper(id).statusCode.value() shouldBe 501
        controller.exportWorkPaper(id, "PDF").statusCode.value() shouldBe 501
        RunsController::class.java.declaredMethods
            .filter { !it.isSynthetic && !it.isBridge && java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet() shouldBe setOf("listRuns", "getRun")
    }

    @Test
    fun `INV-AC-24375 listRuns with limit 501 answers 400 with the API-20 envelope and reads nothing`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        record(t1, "2026-04-28T09:10:00Z")
        val before = reader.calls
        val refused = list(t1, limit = 501)
        refused.statusCode.value() shouldBe 400
        envelope(refused).code shouldBe "SRT-001"
        envelope(refused).status shouldBe 400
        reader.calls shouldBe before
        list(t1, limit = 500).statusCode.value() shouldBe 200
    }

    @Test
    fun `INV-AC-24375 listRuns with a limit below 1 or an unreadable cursor answers 400 and reads nothing`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        record(t1, "2026-04-28T09:10:00Z")
        val before = reader.calls
        list(t1, limit = 0).statusCode.value() shouldBe 400
        list(t1, limit = -1).statusCode.value() shouldBe 400
        val bad = list(t1, cursor = "not-a-cursor")
        bad.statusCode.value() shouldBe 400
        envelope(bad).code shouldBe "SRT-001"
        reader.calls shouldBe before
    }
}
