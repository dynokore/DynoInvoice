package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.http.RunsController
import com.dynokore.gst.reconciliation.adapters.persistence.JooqCompletedRuns
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunReader
import com.dynokore.gst.reconciliation.application.run.GetRun
import com.dynokore.gst.reconciliation.application.run.ListRuns
import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.application.run.RunCursor
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.application.run.RunSnapshot
import com.dynokore.gst.reconciliation.application.run.RunVarianceShare
import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
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

/** Shared fixtures of the run store database tests: the runtime session, the real adapters and the helpers. */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
open class RunStoreDbFixture {
    companion object {
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

    protected class Session {
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
    protected class CountingReader(
        private val inner: RunReader,
    ) : RunReader {
        var calls = 0
        var finds = 0

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
        ) = inner.find(tenant, runId).also {
            calls++
            finds++
        }
    }

    protected val session = Session()
    protected val reader =
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
    protected val controller =
        RunsController(
            ListRuns(reader),
            GetRun(reader),
            GetWorkPaper(reader, LazyRunAnnotations { session.dsl() }, LazyRunAttestations { session.dsl() }),
            session.runner,
            session.tenants,
        )

    protected fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T = session.tenants.bind(TenantId(tenant), USER, block)

    protected fun storeReturn(
        tenant: UUID,
        version: Int = 2,
        form: String = "F5",
    ) = ReconciliationDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into reconciliation.gst_return (tenant_id, entity_ref, period_ref, version, form, filed_at, " +
                    "filing_acknowledgement, uploaded_by, recorded_at) " +
                    "values (?, ?, ?, ?, ?, now(), 'ACK', 'u', now())",
            ).use {
                it.setObject(1, tenant)
                it.setString(2, ENTITY)
                it.setString(3, PERIOD)
                it.setInt(4, version)
                it.setString(5, form)
                it.executeUpdate()
            }
    }

    protected fun comparison(
        box: String,
        submitted: String,
        filed: String,
        variance: String,
    ) = Comparison(box, BigDecimal(filed), BigDecimal(submitted), BigDecimal(variance), MissingSide.NONE)

    protected fun line(
        comparison: Comparison,
        residual: String,
        vararg classified: Triple<String, String, List<String>>,
    ) = LineResult(
        comparison,
        classified.map { (code, amount, linked) ->
            ClassifiedVariance(comparison.key, Category(code, code), BigDecimal(amount), linked)
        },
        BigDecimal(residual),
    )

    protected fun box1(submitted: String = "100.00") =
        line(
            comparison("1", submitted, "90.00", "10.00"),
            "4.00",
            Triple("TIMING_DIFFERENCE", "6.00", listOf("DOC-1")),
        )

    protected val box2 =
        line(
            comparison("2", "100.00", "91.00", "9.00"),
            "0.00",
            Triple("TIMING_DIFFERENCE", "6.00", emptyList()),
            Triple("NO_INVOICE_REQUIRED", "3.00", emptyList()),
        )

    protected fun outcome(
        version: Int = 2,
        submitted: String = "100.00",
        lines: List<LineResult> = listOf(box1(submitted), box2),
    ) = RunOutcome.Completed(
        returnVersion = version,
        result = ReconciliationResult(lines),
        unmappedLines = listOf(UnmappedLine("doc-9", 1, 3, "XX")),
        notReconcilable = listOf(NotReconcilableDocument("doc-8", 1, NotReconcilableReason.NO_LINES)),
        raisesPer003 = true,
        reconcilingItems = emptyList(),
    )

    protected val secondLines =
        listOf(
            line(comparison("1", "100.00", "94.00", "6.00"), "0.00", Triple("TIMING_DIFFERENCE", "6.00", emptyList())),
        )

    protected fun record(
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

    protected fun list(
        tenant: UUID,
        limit: Int = 100,
        cursor: String? = null,
    ): ResponseEntity<RunPage> = asTenant(tenant) { controller.listRuns(ENTITY, PERIOD, limit, cursor) }

    protected fun get(
        tenant: UUID,
        runId: String,
    ): ResponseEntity<Run> = asTenant(tenant) { controller.getRun(UUID.fromString(runId)) }

    protected fun scalar(sql: String): Any? =
        ReconciliationDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use {
                    it.next()
                    it.getObject(1)
                }
            }
        }

    protected fun latest(tenant: UUID): RunSnapshot? =
        asTenant(tenant) {
            session.runner.inTransaction { JooqRunReader(session.dsl()).latest(tenant.toString(), ENTITY, PERIOD) }
        }

    protected fun col(
        table: String,
        column: String,
        runId: String,
    ): Any? = scalar("select $column from reconciliation.$table where run_id = '$runId'")

    protected fun count(
        table: String,
        tenant: UUID,
    ): Any? = scalar("select count(*) from reconciliation.$table where tenant_id = '$tenant'")

    protected fun runtimeSqlState(
        tenant: UUID,
        sql: String,
    ): String? {
        val failure =
            shouldThrow<Exception> {
                asTenant(tenant) { session.runner.inTransaction { session.dsl().execute(sql) } }
            }
        return generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState
    }

    protected fun share(
        code: String,
        amount: String,
    ) = RunVarianceShare(code, BigDecimal(amount))

    protected fun envelope(response: ResponseEntity<*>): ErrorEnvelope = response.body as ErrorEnvelope
}

/** Write side of INV-AC-24375 (INV-T-24376): the append-only run store. */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RunStoreDbTest : RunStoreDbFixture() {
    @Test
    fun `INV-AC-24375 each record call returns a distinct runId and stores one row per run, earlier one unchanged`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val first = record(t1, "2026-04-28T09:10:00Z")
        val rowBefore = col("reconciliation_run r", "row(r.*)::text", first.runId)
        val second = record(t1, "2026-04-29T09:10:00Z")
        first.runId shouldNotBe second.runId
        count("reconciliation_run", t1) shouldBe 2L
        col("reconciliation_run r", "row(r.*)::text", first.runId) shouldBe rowBefore
        col("reconciliation_run", "return_form", first.runId) shouldBe "F5"
        col("reconciliation_run", "return_version", second.runId) shouldBe 2
        col("reconciliation_run", "raises_per003", first.runId) shouldBe true
        count("reconciliation_run_box", t1) shouldBe 4L
        count("reconciliation_run_unmapped_line", t1) shouldBe 2L
        count("reconciliation_run_not_reconcilable", t1) shouldBe 2L
        col("reconciliation_run_not_reconcilable", "reason", first.runId) shouldBe "NO_LINES"
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
                            "select submitted_amount::text, filed_amount::text, variance::text, " +
                                "unexplained_residual::text from reconciliation.reconciliation_run_box " +
                                "where run_id = '${run.runId}' and box = '1'",
                        ).use {
                            it.next()
                            (1..4).map { i -> it.getString(i) }
                        }
                }
            }
        box shouldBe listOf("100.00", "90.00", "10.00", "4.00")
        scalar(
            "select amount::text from reconciliation.reconciliation_run_variance " +
                "where run_id = '${run.runId}' and box = '1'",
        ) shouldBe "6.00"
        scalar(
            "select linked_records[1] from reconciliation.reconciliation_run_variance " +
                "where run_id = '${run.runId}' and box = '1'",
        ) shouldBe "DOC-1"
        scalar("select count(*) from reconciliation.reconciliation_run_variance where run_id = '${run.runId}'") shouldBe
            3L
        col("reconciliation_run", "unexplained_residual::text", run.runId) shouldBe "4.00"
    }

    @Test
    fun `INV-AC-24375 a Box with several classified categories is stored with every category and no null class`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val run = record(t1, "2026-04-28T09:10:00Z")
        scalar(
            "select string_agg(category_code || '=' || amount::text, ',' order by category_code) " +
                "from reconciliation.reconciliation_run_variance where run_id = '${run.runId}' and box = '2'",
        ) shouldBe "NO_INVOICE_REQUIRED=3.00,TIMING_DIFFERENCE=6.00"
    }

    @Test
    fun `INV-AC-24375 a cent amount is stored at scale 2 and not rounded to one decimal`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val run = record(t1, "2026-04-28T09:10:00Z", outcome(submitted = "100.05"))
        scalar(
            "select submitted_amount::text from reconciliation.reconciliation_run_box " +
                "where run_id = '${run.runId}' and box = '1'",
        ) shouldBe "100.05"
    }

    @Test
    fun `INV-AC-24375 a missing return row is an IllegalStateException and nothing is stored`() {
        val t1 = UUID.randomUUID()
        shouldThrow<IllegalStateException> { record(t1, "2026-04-28T09:10:00Z") }
        count("reconciliation_run", t1) shouldBe 0L
    }

    @Test
    fun `INV-AC-24375 an UPDATE or DELETE of a run row fails with SQLSTATE 42501 in each run table`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        record(t1, "2026-04-28T09:10:00Z")
        tables.forEach { table ->
            runtimeSqlState(t1, "delete from reconciliation.$table where tenant_id = '$t1'") shouldBe "42501"
        }
        mapOf(
            "reconciliation_run" to "entity_ref",
            "reconciliation_run_box" to "box",
            "reconciliation_run_variance" to "box",
            "reconciliation_run_unmapped_line" to "tax_code",
        ).forEach { (table, column) ->
            val update = "update reconciliation.$table set $column = 'X' where tenant_id = '$t1'"
            runtimeSqlState(t1, update) shouldBe "42501"
        }
        count("reconciliation_run", t1) shouldBe 1L
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
}

/** Read side of INV-AC-24375 (INV-T-24376): `listRuns` and `getRun` over the real adapters. */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RunReadDbTest : RunStoreDbFixture() {
    @Test
    fun `INV-AC-24375 find and latest return the variance classes of every Box and the form on every RecordedRun`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        val older = record(t1, "2026-04-28T09:10:00Z")
        val newer = record(t1, "2026-04-29T09:10:00Z", outcome(lines = secondLines))
        older.returnForm shouldBe "F5"
        val found =
            asTenant(t1) {
                session.runner.inTransaction { JooqRunReader(session.dsl()).find(t1.toString(), older.runId) }
            }!!
        found.boxes.map { it.box to it.varianceClasses } shouldBe
            listOf(
                "1" to listOf(share("TIMING_DIFFERENCE", "6.00"), share("UNEXPLAINED", "4.00")),
                "2" to listOf(share("NO_INVOICE_REQUIRED", "3.00"), share("TIMING_DIFFERENCE", "6.00")),
            )
        val last = latest(t1)!!
        last.run.runId shouldBe newer.runId
        last.boxes.map { it.box to it.varianceClasses } shouldBe
            listOf("1" to listOf(share("TIMING_DIFFERENCE", "6.00")))
        val pageRows =
            asTenant(t1) {
                session.runner.inTransaction {
                    JooqRunReader(
                        session.dsl(),
                    ).page(t1.toString(), ENTITY, PERIOD, 10, null)
                }
            }
        pageRows shouldBe listOf(newer, older)
        found.run shouldBe pageRows.last()
        last.run shouldBe pageRows.first()
        pageRows.forEach { it.returnForm shouldBe "F5" }
    }

    @Test
    fun `INV-AC-24375 listRuns makes one page read and no find call, each item carries returnForm F5`() {
        val t1 = UUID.randomUUID()
        storeReturn(t1)
        record(t1, "2026-04-28T09:10:00Z")
        record(t1, "2026-04-29T09:10:00Z")
        val before = reader.finds
        val response = list(t1)
        response.body!!.items.map { it.returnForm } shouldBe listOf(Run.ReturnForm.F5, Run.ReturnForm.F5)
        reader.finds shouldBe before
    }

    @Test
    fun `INV-AC-24375 find names the tenant itself, so a connection that bypasses row security reads one tenant`() {
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
        latest(t2) shouldBe null
        latest(t1)!!.run.runId shouldBe run.runId
        get(t1, run.runId).statusCode.value() shouldBe 200
    }

    @Test
    fun `INV-AC-24375 every other operation of both controllers still answers 501`() {
        val id = UUID.randomUUID()
        controller.exportWorkPaper(id, "PDF").statusCode.value() shouldBe 501
        RunsController::class.java.declaredMethods
            .filter {
                !it.isSynthetic && !it.isBridge &&
                    java.lang.reflect.Modifier
                        .isPublic(it.modifiers)
            }.map { it.name }
            .toSet() shouldBe setOf("listRuns", "getRun", "getWorkPaper")
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
