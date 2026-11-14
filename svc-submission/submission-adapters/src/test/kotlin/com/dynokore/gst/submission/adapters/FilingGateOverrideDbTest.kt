package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqFilingGateAuditTrail
import com.dynokore.gst.submission.adapters.persistence.JooqOverrideStore
import com.dynokore.gst.submission.adapters.persistence.JooqReleasedOverrideChecks
import com.dynokore.gst.submission.application.gate.ApproveOverride
import com.dynokore.gst.submission.application.gate.GateModes
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.gate.NewOverride
import com.dynokore.gst.submission.application.gate.OverrideResult
import com.dynokore.gst.submission.application.gate.ReleaseDecisions
import com.dynokore.gst.submission.application.gate.RequestOverride
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.gst.submission.domain.gate.LatestCheck
import com.dynokore.gst.submission.domain.gate.OverrideStatus
import com.dynokore.gst.submission.domain.gate.ReleaseOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import gst.build.konsist.MigrationLint
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.util.PSQLException
import java.io.File
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/** The one-per-check index, the audit table and the instants against the real database (INV-T-10714). */
class FilingGateOverrideDbTest {
    private val created = mutableListOf<UUID>()
    private val connections = mutableListOf<Connection>()

    @AfterEach
    fun cleanUp() {
        connections.forEach { it.close() }
        SubmissionDb.superuser().use { c ->
            created.forEach { t ->
                c.createStatement().use {
                    it.execute("delete from submission.filing_override where tenant_id = '$t'")
                    it.execute("delete from submission.filing_gate_override_audit where tenant_id = '$t'")
                }
            }
        }
    }

    private fun tenant() = UUID.randomUUID().also { created += it }

    /** A runtime connection bound to [tenant], with its stores. */
    private inner class Session(
        val tenant: UUID,
    ) {
        val connection: Connection =
            SubmissionDb
                .connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD)
                .also {
                    it.autoCommit = false
                    connections += it
                }
        private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
        private val context =
            object : TenantContext {
                override fun current() = TenantId(tenant)

                override fun currentActor(): String? = null

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T = block()
            }
        val store = JooqOverrideStore(dsl, context)
        val released = JooqReleasedOverrideChecks(dsl, context)
        val audit = JooqFilingGateAuditTrail(dsl, context)

        fun <T> tx(block: () -> T): T =
            try {
                SignedBindings.bind(connection, tenant)
                block().also { connection.commit() }
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }

        fun auditActions(): List<String> =
            ownerStrings(
                "select action || ':' || actor_user_id from submission.filing_gate_override_audit " +
                    "where tenant_id = '$tenant' order by seq",
            )
    }

    private fun ownerStrings(sql: String): List<String> =
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        }

    private fun pending(
        check: String = "chk-1",
        by: String = "A1",
    ) = NewOverride("E1", "P1", check, "supplier fix", OverrideStatus.PENDING, by, null)

    private val latest =
        object : LatestChecks {
            override fun latest(
                entityRef: String,
                periodRef: String,
            ) = LatestCheck("chk-1", CheckVerdict.NOT_READY)

            override fun knownCheckIds(
                entityRef: String,
                periodRef: String,
            ) = setOf("chk-1")
        }

    @Test
    fun `a second row for one key fails on the one-per-check index and look-alikes insert`() {
        val s = Session(tenant())
        val row = s.tx { s.store.insert(pending()) }
        val failure = assertThrows<Exception> { s.tx { s.store.insert(pending(by = "A9")) } }
        val psql = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<PSQLException>().first()
        psql.sqlState shouldBe "23505"
        psql.serverErrorMessage?.constraint shouldBe "filing_override_one_per_check"
        s.tx { s.store.entries("E1", "P1") } shouldBe listOf(row)
        // the same check in another period, another entity and another tenant is a different key
        s.tx { s.store.insert(NewOverride("E1", "P2", "chk-1", "r", OverrideStatus.PENDING, "A1", null)) }
        s.tx { s.store.insert(NewOverride("E2", "P1", "chk-1", "r", OverrideStatus.PENDING, "A1", null)) }
        val other = Session(tenant())
        other.tx { other.store.insert(pending()) }
        other.tx { other.store.entries("E1", "P1") }.size shouldBe 1
    }

    @Test
    fun `the migration changes no column of filing_override and adds the index only`() {
        violations(migration) shouldBe emptyList()
        val lines = migration.lines().filter { it.contains("filing_override ", ignoreCase = true) }
        lines.filter { it.contains("alter table", ignoreCase = true) } shouldBe emptyList()
        migration.contains("create unique index filing_override_one_per_check") shouldBe true
        migration.startsWith("SET lock_timeout = '5s';") shouldBe true
    }

    @Test
    fun `INV-AC-20023 the released-checks read of confirmFiling lacks a pending override and holds a released one`() {
        val s = Session(tenant())
        val request =
            RequestOverride(
                { _, _, _ -> true },
                GateModes { GateMode.BLOCKING },
                latest,
                { _, _, _, _ -> ReleaseOutcome.AwaitsSecond },
                s.store,
                s.audit,
            )
        s.tx { request.execute("A1", "E1", "P1", "chk-1", "supplier fix") }
        val id = s.tx { s.store.entries("E1", "P1") }.single().id
        s.tx { s.released.releasedCheckIds("E1", "P1") } shouldBe emptySet()
        val approve =
            ApproveOverride({ _, _, _ -> true }, latest, { _, _, _, _ -> ReleaseOutcome.Released }, s.store, s.audit)
        s.tx { approve.execute("A2", "E1", "P1", id) }.shouldBeInstanceOf<OverrideResult.Released>()
        s.tx { s.released.releasedCheckIds("E1", "P1") } shouldBe setOf("chk-1")
        s.tx { s.released.releasedCheckIds("E1", "P2") } shouldBe emptySet()
    }

    @Test
    fun `the instants are the recorded_at of the audit entries and a release in one step shares one`() {
        val s = Session(tenant())
        val row = s.tx { s.store.insert(pending()) }
        s.tx { s.audit.requested("A1", row) }
        val requested = s.tx { s.audit.ofPeriod("E1", "P1") }.getValue("chk-1")
        requested.releasedAt shouldBe null
        s.tx { s.store.release(row.id, "A2") }
        s.tx { s.audit.released("A2", row.copy(status = OverrideStatus.RELEASED, releasedBy = "A2")) }
        val after = s.tx { s.audit.ofPeriod("E1", "P1") }.getValue("chk-1")
        after.requestedAt shouldBe requested.requestedAt
        (after.releasedAt != null) shouldBe true
        val one = Session(tenant())
        val step =
            one.tx {
                one.store.insert(
                    NewOverride("E1", "P1", "chk-1", "r", OverrideStatus.RELEASED, "A1", "A1"),
                )
            }
        one.tx {
            one.audit.requested("A1", step)
            one.audit.released("A1", step)
        }
        val both = one.tx { one.audit.ofPeriod("E1", "P1") }.getValue("chk-1")
        both.releasedAt shouldBe both.requestedAt
    }

    @Test
    fun `an override without a REQUESTED entry has no instant and a release without its RELEASED entry has none`() {
        val s = Session(tenant())
        val row = s.tx { s.store.insert(pending()) }
        s.tx { s.audit.ofPeriod("E1", "P1") } shouldBe emptyMap()
        s.tx { s.audit.requested("A1", row) }
        s.tx { s.store.release(row.id, "A2") }
        s.tx { s.audit.ofPeriod("E1", "P1") }.getValue("chk-1").releasedAt shouldBe null
    }

    @Test
    fun `INV-AC-20023 two threads approving the same pending override release it once, one 201 and one 404`() {
        val tenant = tenant()
        val setup = Session(tenant)
        val row = setup.tx { setup.store.insert(pending()) }
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf("A2", "A3")
                    .map { approver ->
                        pool.submit<OverrideResult> {
                            val s = Session(tenant)
                            val approve =
                                ApproveOverride(
                                    { _, _, _ -> true },
                                    latest,
                                    { _, _, _, _ -> ReleaseOutcome.Released },
                                    s.store,
                                    s.audit,
                                )
                            barrier.await()
                            s.tx { approve.execute(approver, "E1", "P1", row.id) }
                        }
                    }.map { it.get() }
            results.count { it is OverrideResult.Released } shouldBe 1
            results.count { it == OverrideResult.NotFound } shouldBe 1
            val winner =
                results
                    .filterIsInstance<OverrideResult.Released>()
                    .single()
                    .entry.override.releasedBy
            setup.auditActions() shouldBe listOf("RELEASED:$winner")
            setup.tx { setup.store.entries("E1", "P1") }.single().releasedBy shouldBe winner
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `audit counts follow request, release, refused approval and release in one step`() {
        val s = Session(tenant())
        val request =
            RequestOverride(
                { _, _, _ -> true },
                GateModes { GateMode.BLOCKING },
                latest,
                { _, _, _, _ -> ReleaseOutcome.AwaitsSecond },
                s.store,
                s.audit,
            )
        s
            .tx { request.execute("A1", "E1", "P1", "chk-1", "supplier fix") }
            .shouldBeInstanceOf<OverrideResult.AwaitsSecond>()
        s.auditActions() shouldBe listOf("REQUESTED:A1")
        val id = s.tx { s.store.entries("E1", "P1") }.single().id

        val denied = ReleaseDecisions { _, _, _, _ -> ReleaseOutcome.Denied("SAME_PERSON_TWICE") }
        val refused = ApproveOverride({ _, _, _ -> true }, latest, denied, s.store, s.audit)
        s.tx { refused.execute("A1", "E1", "P1", id) } shouldBe OverrideResult.Forbidden
        s.auditActions() shouldBe listOf("REQUESTED:A1")

        val releasing = ReleaseDecisions { _, _, _, _ -> ReleaseOutcome.Released }
        val approve = ApproveOverride({ _, _, _ -> true }, latest, releasing, s.store, s.audit)
        s.tx { approve.execute("A2", "E1", "P1", id) }.shouldBeInstanceOf<OverrideResult.Released>()
        s.auditActions() shouldBe listOf("REQUESTED:A1", "RELEASED:A2")
        ownerStrings(
            "select coalesce(first_approver_user_id, '-') || ':' || coalesce(reason, '-') " +
                "from submission.filing_gate_override_audit where tenant_id = '${s.tenant}' order by seq",
        ) shouldBe listOf("-:supplier fix", "A1:-")

        val t2 = Session(tenant())
        val blocking = GateModes { GateMode.BLOCKING }
        val step = RequestOverride({ _, _, _ -> true }, blocking, latest, releasing, t2.store, t2.audit)
        t2.tx { step.execute("A1", "E1", "P1", "chk-1", "supplier fix") }.shouldBeInstanceOf<OverrideResult.Released>()
        t2.auditActions() shouldBe listOf("REQUESTED:A1", "RELEASED:A1")
    }

    @Test
    fun `the runtime role cannot update or delete an audit entry and sees only its own tenant`() {
        val s = Session(tenant())
        val other = Session(tenant())
        val row = s.tx { s.store.insert(pending()) }
        s.tx { s.audit.requested("A1", row) }
        listOf(
            "update submission.filing_gate_override_audit set actor_user_id = 'X'",
            "delete from submission.filing_gate_override_audit",
        ).forEach { sql ->
            SignedBindings.bind(s.connection, s.tenant)
            assertThrows<Exception> { s.connection.createStatement().use { it.execute(sql) } }
            s.connection.rollback()
        }
        s.auditActions() shouldBe listOf("REQUESTED:A1")
        SignedBindings.bind(other.connection, other.tenant)
        other.connection.createStatement().use { st ->
            st.executeQuery("select count(*) from submission.filing_gate_override_audit").use { rs ->
                rs.next()
                rs.getInt(1) shouldBe 0
            }
        }
        other.connection.rollback()
        SignedBindings.bind(other.connection, other.tenant)
        assertThrows<Exception> {
            other.connection.createStatement().use {
                it.execute(
                    AUDIT_INSERT + "(tenant_id, entity_ref, period_ref, check_id, action, actor_user_id, reason) " +
                        "values ('${s.tenant}', 'E1', 'P1', 'chk-1', 'REQUESTED', 'A1', 'r')",
                )
            }
        }
        other.connection.rollback()
    }

    @Test
    fun `an APPROVED entry is stored and a copy of the table whose check lacks APPROVED refuses it`() {
        val s = Session(tenant())
        val row = s.tx { s.store.insert(pending()) }
        s.tx { s.audit.approved("A2", row) }
        s.auditActions() shouldBe listOf("APPROVED:A2")
        val ddl = migration.substringAfter("create table submission.filing_gate_override_audit (").substringBefore(");")
        val without = ddl.replace("('REQUESTED', 'APPROVED', 'RELEASED')", "('REQUESTED', 'RELEASED')")
        (without != ddl) shouldBe true
        SubmissionDb.superuser().use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { st ->
                    st.execute("create table submission.probe_audit ($without)")
                    assertThrows<Exception> {
                        st.execute(
                            "insert into submission.probe_audit (tenant_id, entity_ref, period_ref, check_id, " +
                                "action, actor_user_id, first_approver_user_id) values " +
                                "('${s.tenant}', 'E1', 'P1', 'c', 'APPROVED', 'A2', 'A1')",
                        )
                    }
                }
            } finally {
                c.rollback()
            }
        }
    }

    @Test
    fun `an audit row outside the action enum is refused by the table`() {
        val s = Session(tenant())
        SignedBindings.bind(s.connection, s.tenant)
        assertThrows<Exception> {
            s.connection.createStatement().use {
                it.execute(
                    AUDIT_INSERT + "(tenant_id, entity_ref, period_ref, check_id, action, actor_user_id) " +
                        "values ('${s.tenant}', 'E1', 'P1', 'chk-1', 'DELETED', 'A1')",
                )
            }
        }
        s.connection.rollback()
    }

    // ---- probes of the migration lint on copies of the migration ---------------------------------------------------

    private val migration =
        File("src/main/resources/db/migration/V202611181200__filing_gate_override.sql").readText()

    private fun violations(sql: String) = MigrationLint.lint("V202611181200__probe.sql", listOf(1 to sql))

    @Test
    fun `the migration passes the lint and a copy without tenant_id fails it`() {
        violations(migration) shouldBe emptyList()
        val tenantColumn = "    tenant_id uuid not null,\n"
        val withoutTenant = migration.replace(tenantColumn, "")
        (withoutTenant != migration) shouldBe true
        violations(withoutTenant).isNotEmpty() shouldBe true
    }

    /** The facts TenantTablesTest reads of a table: tenant_id not null, RLS enabled and forced, the signed policy. */
    private fun sound(
        connection: Connection,
        table: String,
    ): Boolean =
        connection
            .createStatement()
            .use { st ->
                st
                    .executeQuery(
                        "select c.relrowsecurity and c.relforcerowsecurity " +
                            "and exists (select 1 from pg_attribute a where a.attrelid = c.oid " +
                            "and a.attname = 'tenant_id' and a.attnotnull) " +
                            "and exists (select 1 from pg_policy p where p.polrelid = c.oid " +
                            "and pg_get_expr(p.polqual, p.polrelid) like '%bound_tenant()%' " +
                            "and pg_get_expr(p.polwithcheck, p.polrelid) like '%bound_tenant()%') " +
                            "from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                            "where n.nspname = 'submission' and c.relname = '$table'",
                    ).use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
            }

    @Test
    fun `the audit table is sound and a scratch copy without the forced policy or without tenant_id is not`() {
        SubmissionDb.superuser().use { c ->
            sound(c, "filing_gate_override_audit") shouldBe true
            c.autoCommit = false
            try {
                c.createStatement().use { st ->
                    st.execute("create table submission.probe_noforce (tenant_id uuid not null)")
                    st.execute("alter table submission.probe_noforce enable row level security")
                    st.execute(
                        "create policy p on submission.probe_noforce " +
                            "using (tenant_id = (select submission.bound_tenant())) " +
                            "with check (tenant_id = (select submission.bound_tenant()))",
                    )
                    st.execute("create table submission.probe_notenant (id int)")
                    st.execute("alter table submission.probe_notenant enable row level security")
                    st.execute("alter table submission.probe_notenant force row level security")
                }
                sound(c, "probe_noforce") shouldBe false
                sound(c, "probe_notenant") shouldBe false
            } finally {
                c.rollback()
            }
        }
    }

    private companion object {
        const val AUDIT_INSERT = "insert into submission.filing_gate_override_audit "
    }
}
