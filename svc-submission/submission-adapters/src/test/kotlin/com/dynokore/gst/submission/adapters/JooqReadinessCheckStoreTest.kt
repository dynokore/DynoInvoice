package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqReadinessCheckStore
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/** Tests of `submission.readiness_check` and its store against the real database (INV-T-17045, INV-Q-17042 A). */
class JooqReadinessCheckStoreTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private var bound: UUID = UUID.randomUUID()
    private val tenants = contextOf { bound }
    private val store = JooqReadinessCheckStore(DSL.using(connection, SQLDialect.POSTGRES), tenants)
    private val created = mutableListOf<UUID>()
    private val t0 = Instant.parse("2026-11-01T08:00:00Z")

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            created.forEach { t ->
                c.createStatement().use { it.execute("delete from submission.readiness_check where tenant_id = '$t'") }
            }
        }
    }

    private fun tenant() = UUID.randomUUID().also { created += it }

    private fun <T> inTx(
        tenant: UUID,
        commit: Boolean = true,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        bound = tenant
        return try {
            block().also { if (commit) connection.commit() }
        } finally {
            if (!commit) connection.rollback()
        }
    }

    @Suppress("LongParameterList")
    private fun append(
        t: UUID,
        verdict: CheckVerdict,
        approver: String,
        at: Instant,
        entity: String = "E1",
        period: String = "P1",
    ) = inTx(t) { store.append(entity, period, verdict, at, approver, null) }

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }

    private fun ownerRows(sql: String) = SubmissionDb.superuser().use { it.strings(sql) }

    private fun view(c: StoredReadinessCheck) = listOf(c.checkId, c.verdict, c.checkedAt, c.approver)

    @Test
    fun `INV-AC-17044 two appends are read back newest first, isolated, and never changed or deleted`() {
        val t1 = tenant()
        val t2 = tenant()
        val first = append(t1, CheckVerdict.READY, "U1", t0)
        val second = append(t1, CheckVerdict.NOT_READY, "filing-workflow", t0.plusSeconds(60))
        (first.checkId != second.checkId) shouldBe true
        inTx(t1) { store.latest("E1", "P1") } shouldBe second
        inTx(t1) { store.page("E1", "P1", 100) } shouldContainExactly listOf(second, first)
        inTx(t1) { store.latest("E1", "P2") } shouldBe null
        inTx(t1) { store.latest("E2", "P1") } shouldBe null
        inTx(t2) { store.page("E1", "P1", 100) } shouldBe emptyList()
        listOf("update submission.readiness_check set verdict = 'READY'", "delete from submission.readiness_check")
            .forEach { sql ->
                assertThrows<Exception> { inTx(t1) { DSL.using(connection, SQLDialect.POSTGRES).execute(sql) } }
                connection.rollback()
            }
        inTx(t1) { store.page("E1", "P1", 100) }.map(::view) shouldBe listOf(second, first).map(::view)
        ownerRows("select verdict from submission.readiness_check where tenant_id = '$t1' order by seq") shouldBe
            listOf("READY", "NOT_READY")
        for (bad in listOf("ready", "READY ")) {
            val e =
                assertThrows<SQLException> {
                    SubmissionDb.superuser().use { c ->
                        c.createStatement().use {
                            it.execute(
                                "insert into submission.readiness_check (tenant_id, entity_ref, period_ref, verdict, " +
                                    "checked_at, approver) values ('$t1', 'E1', 'P1', '$bad', now(), 'U1')",
                            )
                        }
                    }
                }
            e.message.orEmpty().contains("check constraint") shouldBe true
        }
    }

    @Test
    fun `append then latest and page return every field appended and distinct check ids`() {
        val t = tenant()
        val a = inTx(t) { store.append("E1", "P1", CheckVerdict.READY, t0, "U1", """{"k": 1}""") }
        val b = append(t, CheckVerdict.NOT_READY, "filing-workflow", t0.plusSeconds(5))
        a.checkId shouldBe "chk-${a.seq}"
        (a.checkId != b.checkId) shouldBe true
        inTx(t) { store.latest("E1", "P1") } shouldBe b
        val page = inTx(t) { store.page("E1", "P1", 10) }
        page.map { it.checkId } shouldContainExactly listOf(b.checkId, a.checkId)
        page[1].verdict shouldBe CheckVerdict.READY
        page[1].checkedAt shouldBe t0
        page[1].approver shouldBe "U1"
        page[1].outcomeValues!!.replace(" ", "") shouldBe """{"k":1}"""
    }

    @Test
    fun `a session bound to another tenant and a forged binding read nothing`() {
        val t1 = tenant()
        val t2 = tenant()
        append(t1, CheckVerdict.READY, "U1", t0)
        inTx(t2) { store.latest("E1", "P1") } shouldBe null
        SignedBindings.bind(connection, t1, signature = "forged")
        bound = t1
        val forged = runCatching { store.page("E1", "P1", 10) }.getOrDefault(emptyList())
        connection.rollback()
        forged shouldBe emptyList()
    }

    @Test
    fun `another entity or another period of the same tenant reads nothing`() {
        val t = tenant()
        append(t, CheckVerdict.READY, "U1", t0)
        inTx(t) { store.page("E2", "P1", 10) } shouldBe emptyList()
        inTx(t) { store.page("E1", "P2", 10) } shouldBe emptyList()
    }

    @Test
    fun `an update and a delete by the runtime role fail and leave the rows unchanged`() {
        val t = tenant()
        val a = append(t, CheckVerdict.READY, "U1", t0)
        val statements =
            listOf("update submission.readiness_check set approver = 'X'", "delete from submission.readiness_check")
        for (sql in statements) {
            val e = assertThrows<Exception> { inTx(t) { DSL.using(connection, SQLDialect.POSTGRES).execute(sql) } }
            e.message.orEmpty().contains("permission denied") shouldBe true
            connection.rollback()
        }
        inTx(t) { store.page("E1", "P1", 10) } shouldBe listOf(a)
    }

    @Test
    fun `an insert of a verdict that is not exactly READY or NOT_READY fails the check`() {
        val t = tenant()
        for (bad in listOf("ready", "READY ")) {
            val e =
                assertThrows<SQLException> {
                    SubmissionDb.superuser().use { c ->
                        c.createStatement().use {
                            it.execute(
                                "insert into submission.readiness_check (tenant_id, entity_ref, period_ref, verdict, " +
                                    "checked_at, approver) values ('$t', 'E1', 'P1', '$bad', now(), 'U1')",
                            )
                        }
                    }
                }
            e.message.orEmpty().contains("check constraint") shouldBe true
        }
    }

    @Test
    fun `two simultaneous appends to one period leave two rows with distinct check ids`() {
        val t = tenant()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                (1..2)
                    .map { n ->
                        pool.submit<StoredReadinessCheck> {
                            SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { c ->
                                c.autoCommit = false
                                SignedBindings.bind(c, t)
                                barrier.await()
                                JooqReadinessCheckStore(DSL.using(c, SQLDialect.POSTGRES), contextOf { t })
                                    .append("E1", "P1", CheckVerdict.READY, t0.plusSeconds(n.toLong()), "U$n", null)
                                    .also { c.commit() }
                            }
                        }
                    }.map { it.get() }
            results.map { it.checkId }.toSet().size shouldBe 2
        } finally {
            pool.shutdownNow()
        }
        ownerRows("select count(*) from submission.readiness_check where tenant_id = '$t'") shouldBe listOf("2")
    }

    @Test
    fun `a rollback of the caller's transaction leaves no row`() {
        val t = tenant()
        inTx(t, commit = false) { store.append("E1", "P1", CheckVerdict.READY, t0, "U1", null) }
        ownerRows("select count(*) from submission.readiness_check where tenant_id = '$t'") shouldBe listOf("0")
    }

    @Test
    fun `limit 0 and 501 are refused, 500 is accepted and before returns only older records`() {
        val t = tenant()
        val a = append(t, CheckVerdict.READY, "U1", t0)
        val b = append(t, CheckVerdict.NOT_READY, "U1", t0.plusSeconds(1))
        assertThrows<IllegalArgumentException> { store.page("E1", "P1", 0) }
        assertThrows<IllegalArgumentException> { store.page("E1", "P1", 501) }
        inTx(t) { store.page("E1", "P1", 500) }.size shouldBe 2
        inTx(t) { store.page("E1", "P1", 10, before = b.seq) } shouldBe listOf(a)
        assertThrows<IllegalArgumentException> { store.append(" ", "P1", CheckVerdict.READY, t0, "U1", null) }
        assertThrows<IllegalArgumentException> { store.append("E1", "P1", CheckVerdict.READY, t0, "", null) }
    }

    @Test
    fun `a stored verdict outside READY and NOT_READY fails the read closed`() {
        val t = tenant()
        SubmissionDb.superuser().use { c ->
            c.createStatement().use {
                it.execute("alter table submission.readiness_check drop constraint readiness_check_verdict_check")
            }
        }
        try {
            SubmissionDb.superuser().use { c ->
                c.createStatement().use {
                    it.execute(
                        "insert into submission.readiness_check (tenant_id, entity_ref, period_ref, verdict, " +
                            "checked_at, approver) values ('$t', 'E1', 'P1', 'ready', now(), 'U1')",
                    )
                }
            }
            assertThrows<IllegalStateException> { inTx(t) { store.latest("E1", "P1") } }
            connection.rollback()
        } finally {
            SubmissionDb.superuser().use { c ->
                c.createStatement().use {
                    it.execute("delete from submission.readiness_check where tenant_id = '$t'")
                    it.execute(
                        "alter table submission.readiness_check add constraint readiness_check_verdict_check " +
                            "check (verdict in ('READY', 'NOT_READY'))",
                    )
                }
            }
        }
    }

    @Test
    fun `the tenant_id column has no default and an insert stores the tenant of the context`() {
        val a = tenant()
        ownerRows(
            "select column_default is null from information_schema.columns " +
                "where table_schema = 'submission' and table_name = 'readiness_check' and column_name = 'tenant_id'",
        ) shouldBe listOf("t")
        append(a, CheckVerdict.READY, "U1", t0)
        ownerRows("select tenant_id from submission.readiness_check where tenant_id = '$a'") shouldBe
            listOf(a.toString())
    }
}

private fun contextOf(current: () -> UUID): TenantContext =
    object : TenantContext {
        override fun current(): TenantId = TenantId(current())

        override fun currentActor(): String? = null

        override fun <T> bind(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T = block()
    }
