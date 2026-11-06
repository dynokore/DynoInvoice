package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.persistence.JooqOverrideStore
import com.dynokore.gst.payload.application.override.NewOverride
import com.dynokore.gst.payload.application.override.StoredOverride
import com.dynokore.gst.payload.domain.override.OverrideStatus
import gst.build.konsist.MigrationLint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The override store against the real database as `payload_runtime` with a signed binding (INV-T-10713). */
class JooqOverrideStoreTest {
    private companion object {
        const val FORCED_QUERY =
            "select relrowsecurity and relforcerowsecurity from pg_class where oid = to_regclass(?)"
    }

    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val doc = UUID.randomUUID()

    private fun <T> bound(
        tenant: UUID,
        commit: Boolean = true,
        block: (JooqOverrideStore) -> T,
    ): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                block(JooqOverrideStore(DSL.using(c, SQLDialect.POSTGRES)))
            } finally {
                if (commit) c.commit() else c.rollback()
            }
        }

    private fun pending(
        code: String = "PAY-011",
        version: Int = 1,
        by: String = "A1",
    ) = NewOverride(doc, version, code, "register lag", OverrideStatus.PENDING, by, null)

    @Test
    fun `a pending row is stored with its columns and found`() {
        val row = bound(t1) { it.insert(pending()) }!!
        row.status shouldBe OverrideStatus.PENDING
        row.requestedBy shouldBe "A1"
        row.releasedBy shouldBe null
        bound(t1) { it.find(doc, 1, "PAY-011") } shouldBe row
    }

    @Test
    fun `a second insert of the same key stores nothing`() {
        bound(t1) { it.insert(pending()) } shouldNotBe null
        bound(t1) { it.insert(pending(by = "A2")) } shouldBe null
        bound(t1) { it.find(doc, 1, "PAY-011") }!!.requestedBy shouldBe "A1"
    }

    @Test
    fun `the same key under another version or tenant is another row and another tenant sees nothing`() {
        bound(t1) { it.insert(pending()) } shouldNotBe null
        bound(t1) { it.insert(pending(version = 2)) } shouldNotBe null
        bound(t2) { it.insert(pending()) } shouldNotBe null
        bound(t2) { it.find(doc, 1, "PAY-011") }!!.requestedBy shouldBe "A1"
        bound(t2) { it.release(doc, 1, "PAY-011", "A9") }!!.releasedBy shouldBe "A9"
        bound(t1) { it.find(doc, 1, "PAY-011") }!!.status shouldBe OverrideStatus.PENDING
    }

    @Test
    fun `a released row cannot be released again`() {
        bound(t1) { it.insert(pending()) }
        bound(t1) { it.release(doc, 1, "PAY-011", "A2") }!!.releasedBy shouldBe "A2"
        bound(t1) { it.release(doc, 1, "PAY-011", "A3") } shouldBe null
        bound(t1) { it.find(doc, 1, "PAY-011") }!!.releasedBy shouldBe "A2"
    }

    @Test
    fun `a row released in one step carries both users`() {
        val row = bound(t1) { it.insert(NewOverride(doc, 1, "PAY-011", "r", OverrideStatus.RELEASED, "A1", "A1")) }!!
        row.status shouldBe OverrideStatus.RELEASED
        row.releasedBy shouldBe "A1"
        (row.releasedAt != null) shouldBe true
    }

    @Test
    fun `two simultaneous approvals release once and the loser changes no row`() {
        bound(t1) { it.insert(pending()) }
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val results: List<StoredOverride?>
        try {
            val jobs =
                listOf("A2", "A3").map { approver ->
                    pool.submit<StoredOverride?> {
                        bound(t1) { s ->
                            barrier.await(30, TimeUnit.SECONDS)
                            s.release(doc, 1, "PAY-011", approver)
                        }
                    }
                }
            results = jobs.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        results.count { it != null } shouldBe 1
        results.count { it == null } shouldBe 1
        bound(t1) { it.find(doc, 1, "PAY-011") }!!.releasedBy shouldBe results.single { it != null }!!.releasedBy
    }

    @Test
    fun `entries are ordered by request time then code and page after a cursor`() {
        bound(t1) { it.insert(pending("PAY-011")) }
        bound(t1) { it.insert(pending("PAY-012")) }
        bound(t1) { it.insert(pending("PAY-013")) }
        val all = bound(t1) { it.entries(doc, 1, null, 10) }
        all.map { it.code }.toSet() shouldBe setOf("PAY-011", "PAY-012", "PAY-013")
        val first = bound(t1) { it.entries(doc, 1, null, 2) }
        first.size shouldBe 2
        val rest = bound(t1) { it.entries(doc, 1, first.last().requestedAt to first.last().code, 10) }
        (first + rest).map { it.code } shouldBe all.map { it.code }
        bound(t1) { it.entries(doc, 2, null, 10) } shouldBe emptyList()
    }

    @Test
    fun `a status outside the enum is refused by the database`() {
        PayloadDb.superuser().use { c ->
            c.createStatement().use { s ->
                shouldThrow<java.sql.SQLException> {
                    s.execute(
                        "insert into payload.validation_override " +
                            "(tenant_id, doc_uuid, version, code, reason, status, requested_by) " +
                            "values ('${UUID.randomUUID()}', '${UUID.randomUUID()}', 1, 'PAY-011', 'r', " +
                            "'SUPERSEDED', 'A1')",
                    )
                }
            }
        }
    }

    private fun migration() =
        File("src/main/resources/db/migration")
            .listFiles()!!
            .single { it.name.contains("validation_override") }
            .readText()

    private fun violations(sql: String) = MigrationLint.lint("probe.sql", listOf(1 to sql))

    @Test
    fun `the migration passes the lint and a copy without tenant_id fails it`() {
        violations(migration()) shouldBe emptyList()
        violations(migration().replace("    tenant_id uuid not null,\n", "")).isNotEmpty() shouldBe true
    }

    private fun forced(
        table: String,
        create: String? = null,
    ): Boolean =
        PayloadDb.superuser().use { c ->
            c.autoCommit = false
            try {
                create?.let { c.createStatement().use { s -> s.execute(it) } }
                c.prepareStatement(FORCED_QUERY).use { p ->
                    p.setString(1, table)
                    p.executeQuery().use { r -> r.next() && r.getBoolean(1) }
                }
            } finally {
                c.rollback()
            }
        }

    @Test
    fun `the table is under forced row security and a copy without it is detected by the same query`() {
        forced("payload.validation_override") shouldBe true
        val probe = migration().replace("ALTER TABLE payload.validation_override FORCE ROW LEVEL SECURITY;\n", "")
        forced("payload.validation_override") shouldBe true
        // a copy of the table without FORCE, created and rolled back, is reported as not forced
        forced(
            "payload.probe_override",
            "create table payload.probe_override (id int, tenant_id uuid not null); " +
                "alter table payload.probe_override enable row level security",
        ) shouldBe false
        (probe.contains("FORCE ROW LEVEL SECURITY")) shouldBe false
    }

    @Test
    fun `the signed binding policy is on the table`() {
        PayloadDb.superuser().use { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "select count(*) from pg_policies " +
                            "where tablename = 'validation_override' and qual like '%bound_tenant%'",
                    ).use { r ->
                        r.next()
                        r.getInt(1) shouldBe 1
                    }
            }
        }
    }
}
