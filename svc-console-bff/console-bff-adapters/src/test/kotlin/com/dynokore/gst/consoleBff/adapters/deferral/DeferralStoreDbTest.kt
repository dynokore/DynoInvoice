package com.dynokore.gst.consoleBff.adapters.deferral

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
import com.dynokore.gst.consoleBff.application.deferral.DeferralStore
import com.dynokore.gst.consoleBff.application.deferral.DeferralStoreContractTest
import com.dynokore.gst.consoleBff.domain.deferral.Deferral
import com.dynokore.gst.consoleBff.domain.deferral.DeferralKind
import com.dynokore.gst.consoleBff.domain.deferral.DeferralState
import com.dynokore.gst.consoleBff.domain.deferral.DeferralTarget
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `console_bff.deferral` and `JooqDeferralStore` on a live PostgreSQL as the runtime role under a signed binding
 * (INV-AC-21156; PostgreSQL forced row level security, least privilege, partial unique index for one active row).
 */
class DeferralStoreDbTest {
    private val migration =
        checkNotNull(javaClass.getResource("/db/migration/V202611121300__console_bff_deferral.sql")).readText()

    private val begin = "-- BEGIN TenantRlsMigration.ddl(\"console_bff\", \"deferral\")"
    private val end = "-- END TenantRlsMigration.ddl"
    private val predicate = "tenant_id = (select console_bff.bound_tenant())"

    /** Runs [block] as the runtime role in one transaction bound to [tenant]; `null` binds nothing. */
    private fun <T> asTenant(
        tenant: UUID?,
        block: (JooqDeferralStore, Connection) -> T,
    ): T =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            if (tenant != null) SignedBindings.bind(c, tenant)
            val result = block(JooqDeferralStore(DSL.using(c, SQLDialect.POSTGRES)), c)
            c.commit()
            result
        }

    private fun <T> catalog(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        ConsoleDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
            }
        }

    private val at = Instant.parse("2026-11-14T00:00:00Z").truncatedTo(ChronoUnit.MICROS)

    private fun candidate(
        entityRef: String = "entity-1",
        ref: String = "doc-1",
        by: String = "user-1",
        kind: DeferralKind = DeferralKind.DOCUMENT,
    ) = Deferral(UUID.randomUUID(), entityRef, DeferralTarget(kind, ref), DeferralState.DEFERRED, by, at)

    /** The expression after [keyword] up to the closing parenthesis that ends the clause line. */
    private fun clause(
        section: String,
        keyword: String,
    ): String =
        section
            .lines()
            .first { it.trim().startsWith(keyword) }
            .trim()
            .removePrefix(keyword)
            .trim()
            .removeSuffix(";")
            .let { it.removePrefix("(").removeSuffix(")") }

    private fun insertRaw(
        c: Connection,
        tenant: UUID,
        kind: String,
        state: String,
        ref: String = "doc-1",
    ) = c
        .prepareStatement(
            "insert into console_bff.deferral (tenant_id, deferral_id, entity_ref, target_kind, target_ref, state, " +
                "deferred_by, deferred_at) values (?, ?, 'entity-1', ?, ?, ?, 'user-1', now())",
        ).use {
            it.setObject(1, tenant)
            it.setObject(2, UUID.randomUUID())
            it.setString(3, kind)
            it.setString(4, ref)
            it.setString(5, state)
            it.executeUpdate()
        }

    private fun failsOnInsert(
        tenant: UUID,
        kind: String,
        state: String,
        ref: String = "doc-1",
    ): Boolean =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                insertRaw(c, tenant, kind, state, ref)
                false
            } catch (_: SQLException) {
                true
            } finally {
                c.rollback()
            }
        }

    @Test
    fun `INV-AC-21156 the column set and the primary key equal the criterion by catalog with no until and no reason`() {
        val columns =
            catalog(
                "select column_name, data_type, is_nullable from information_schema.columns " +
                    "where table_schema = 'console_bff' and table_name = 'deferral'",
            ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        columns shouldContainExactlyInAnyOrder
            listOf(
                Triple("tenant_id", "uuid", "NO"),
                Triple("deferral_id", "uuid", "NO"),
                Triple("entity_ref", "text", "NO"),
                Triple("target_kind", "text", "NO"),
                Triple("target_ref", "text", "NO"),
                Triple("state", "text", "NO"),
                Triple("deferred_by", "text", "NO"),
                Triple("deferred_at", "timestamp with time zone", "NO"),
            )
        columns.map { it.first }.any { it == "until" || it == "reason" } shouldBe false
        val key =
            catalog(
                "select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid " +
                    "and a.attnum = any(i.indkey) where i.indrelid = 'console_bff.deferral'::regclass " +
                    "and i.indisprimary order by array_position(i.indkey::int2[], a.attnum)",
            ) { it.getString(1) }
        key shouldBe listOf("tenant_id", "deferral_id")
    }

    @Test
    fun `INV-AC-21156 state accepts only DEFERRED and UNDONE and target_kind only DOCUMENT and GROUP`() {
        val t = UUID.randomUUID()
        failsOnInsert(t, "DOCUMENT", "OPEN") shouldBe true
        failsOnInsert(t, "FOLDER", "DEFERRED") shouldBe true
        failsOnInsert(t, "DOCUMENT", "DEFERRED") shouldBe false
        failsOnInsert(t, "GROUP", "UNDONE") shouldBe false
        catalog(
            "select pg_get_constraintdef(oid) from pg_constraint where conrelid = 'console_bff.deferral'::regclass " +
                "and contype = 'c' order by 1",
        ) { it.getString(1) }.let { defs ->
            defs.size shouldBe 2
            defs.any { it.contains("'DEFERRED'") && it.contains("'UNDONE'") } shouldBe true
            defs.any { it.contains("'DOCUMENT'") && it.contains("'GROUP'") } shouldBe true
        }
    }

    @Test
    fun `INV-AC-21156 the partial unique index rejects a second DEFERRED row and allows any number of UNDONE rows`() {
        val indexDef =
            catalog(
                "select indexdef from pg_indexes where schemaname = 'console_bff' and tablename = 'deferral' " +
                    "and indexname = 'deferral_one_deferred'",
            ) { it.getString(1) }
        indexDef shouldBe
            listOf(
                "CREATE UNIQUE INDEX deferral_one_deferred ON console_bff.deferral USING btree " +
                    "(tenant_id, entity_ref, target_kind, target_ref) WHERE (state = 'DEFERRED'::text)",
            )
        val t = UUID.randomUUID()
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, t)
            insertRaw(c, t, "DOCUMENT", "UNDONE")
            insertRaw(c, t, "DOCUMENT", "UNDONE")
            insertRaw(c, t, "DOCUMENT", "DEFERRED")
            val rejected =
                try {
                    insertRaw(c, t, "DOCUMENT", "DEFERRED")
                    false
                } catch (_: SQLException) {
                    true
                }
            rejected shouldBe true
            c.rollback()
        }
    }

    @Test
    fun `INV-AC-21156 the second defer returns the first row unchanged`() {
        val t = UUID.randomUUID()
        val first = asTenant(t) { s, _ -> s.deferIfAbsent(candidate(by = "user-1")) }
        val second = asTenant(t) { s, _ -> s.deferIfAbsent(candidate(by = "user-2")) }
        second shouldBe first
        second.deferredBy shouldBe "user-1"
    }

    @Test
    fun `INV-AC-21156 after undo the row is UNDONE and a new defer stores a second row`() {
        val t = UUID.randomUUID()
        val first = asTenant(t) { s, _ -> s.deferIfAbsent(candidate()) }
        val undone = asTenant(t) { s, _ -> s.undo("entity-1", first.deferralId) }
        undone shouldBe first.copy(state = DeferralState.UNDONE)
        val again = asTenant(t) { s, _ -> s.deferIfAbsent(candidate()) }
        (again.deferralId == first.deferralId) shouldBe false
        again.state shouldBe DeferralState.DEFERRED
        catalog(
            "select count(*) from console_bff.deferral where tenant_id = '$t'",
        ) { it.getInt(1) } shouldBe listOf(2)
    }

    @Test
    fun `INV-AC-21156 concurrent defers of one key leave one DEFERRED row and return the same deferralId to both`() {
        val t = UUID.randomUUID()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                (1..2)
                    .map { n ->
                        pool.submit<Deferral> {
                            asTenant(t) { s, _ ->
                                barrier.await(30, TimeUnit.SECONDS)
                                s.deferIfAbsent(candidate(by = "user-$n"))
                            }
                        }
                    }.map { it.get(60, TimeUnit.SECONDS) }
            results[0].deferralId shouldBe results[1].deferralId
        } finally {
            pool.shutdownNow()
        }
        catalog(
            "select count(*) from console_bff.deferral where tenant_id = '$t' and state = 'DEFERRED'",
        ) { it.getInt(1) } shouldBe listOf(1)
    }

    @Test
    fun `INV-AC-21156 tenant B reads no row of A cannot update it and may defer the same key itself`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val rowA = asTenant(a) { s, _ -> s.deferIfAbsent(candidate()) }
        asTenant(b) { s, _ -> s.findDeferred("entity-1", rowA.target) } shouldBe null
        asTenant(null) { s, _ -> s.findDeferred("entity-1", rowA.target) } shouldBe null
        asTenant(b) { s, _ -> s.undo("entity-1", rowA.deferralId) } shouldBe null
        val updated =
            asTenant(b) { _, c ->
                c.prepareStatement("update console_bff.deferral set state = 'UNDONE' where deferral_id = ?").use {
                    it.setObject(1, rowA.deferralId)
                    it.executeUpdate()
                }
            }
        updated shouldBe 0
        asTenant(a) { s, _ -> s.findDeferred("entity-1", rowA.target) } shouldBe rowA
        val rowB = asTenant(b) { s, _ -> s.deferIfAbsent(candidate(by = "user-b")) }
        (rowB.deferralId == rowA.deferralId) shouldBe false
        rowB.deferredBy shouldBe "user-b"
    }

    @Test
    fun `INV-AC-21156 the migration RLS section equals TenantRlsMigration ddl and both clauses equal the predicate`() {
        val section = migration.substringAfter(begin, "").substringBefore(end, "")
        (section.isNotBlank()) shouldBe true
        section.trim() shouldBe TenantRlsMigration.ddl("console_bff", "deferral").trim()
        clause(section, "USING") shouldBe predicate
        clause(section, "WITH CHECK") shouldBe predicate
    }

    @Test
    fun `INV-AC-21156 RLS is enabled and forced and the tenant_isolation policy for ALL has the predicate`() {
        catalog(
            "select relrowsecurity, relforcerowsecurity from pg_class where oid = 'console_bff.deferral'::regclass",
        ) { it.getBoolean(1) to it.getBoolean(2) } shouldBe listOf(true to true)
        val policies =
            catalog(
                "select policyname, cmd, qual, with_check from pg_policies " +
                    "where schemaname = 'console_bff' and tablename = 'deferral'",
            ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
        policies.size shouldBe 1
        val p = policies.single()
        p[0] shouldBe "tenant_isolation"
        p[1] shouldBe "ALL"
        p[2] shouldBe PRINTED_PREDICATE
        p[3] shouldBe PRINTED_PREDICATE
        // the printed bare-call form differs: it has no sub-select
        (PRINTED_PREDICATE.contains("SELECT")) shouldBe true
        p[2].shouldContain("( SELECT console_bff.bound_tenant()")
    }

    @Test
    fun `INV-AC-21156 the runtime role holds select insert update and no delete`() {
        catalog(
            "select has_table_privilege('console_bff_runtime', 'console_bff.deferral', 'SELECT'), " +
                "has_table_privilege('console_bff_runtime', 'console_bff.deferral', 'INSERT'), " +
                "has_table_privilege('console_bff_runtime', 'console_bff.deferral', 'UPDATE'), " +
                "has_table_privilege('console_bff_runtime', 'console_bff.deferral', 'DELETE')",
        ) { listOf(it.getBoolean(1), it.getBoolean(2), it.getBoolean(3), it.getBoolean(4)) } shouldBe
            listOf(listOf(true, true, true, false))
    }

    /** INV-AC-21156: the adapter passes the abstract contract cases of the model task. */
    class AdapterContract : DeferralStoreContractTest() {
        private val open = mutableListOf<Connection>()
        private val tenant = UUID.randomUUID()
        private var rows: Connection? = null

        // One long transaction per store, bound to a fresh tenant: later reads see earlier writes, nothing leaks.
        override fun newStore(): DeferralStore {
            val c = ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD)
            open += c
            rows = c
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            return JooqDeferralStore(DSL.using(c, SQLDialect.POSTGRES))
        }

        override fun rowCount(store: DeferralStore): Int =
            checkNotNull(rows).createStatement().use { s ->
                s.executeQuery("select count(*) from console_bff.deferral where state = 'DEFERRED'").use {
                    it.next()
                    it.getInt(1)
                }
            }

        @org.junit.jupiter.api.AfterEach
        fun close() {
            open.forEach { runCatching { it.rollback() }.also { _ -> it.close() } }
        }
    }

    private companion object {
        // The text PostgreSQL prints for the sub-select predicate; recorded from pg_policies (see the MR).
        const val PRINTED_PREDICATE = "(tenant_id = ( SELECT console_bff.bound_tenant() AS bound_tenant))"
    }
}
