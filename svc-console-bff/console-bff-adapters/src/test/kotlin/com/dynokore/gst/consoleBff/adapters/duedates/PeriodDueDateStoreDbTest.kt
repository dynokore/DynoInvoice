package com.dynokore.gst.consoleBff.adapters.duedates

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
import com.dynokore.gst.consoleBff.application.duedates.PeriodKey
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * `console_bff.period_due_date` and `JooqPeriodDueDateStore` on a live PostgreSQL as the runtime role under a signed
 * binding (INV-AC-21077; forced row level security, least privilege, version-ordered idempotent writes).
 */
class PeriodDueDateStoreDbTest {
    private val migration =
        checkNotNull(javaClass.getResource("/db/migration/V202611121200__console_bff_period_due_date.sql")).readText()

    private val k1 = PeriodKey("E1", "P1")
    private val k2 = PeriodKey("E1", "P2")
    private val k3 = PeriodKey("E1", "P3")

    private fun d(text: String) = LocalDate.parse(text)

    /** Runs [block] as the runtime role in one transaction bound to [tenant]; `null` binds nothing. */
    private fun <T> asTenant(
        tenant: UUID?,
        block: (JooqPeriodDueDateStore, java.sql.Connection) -> T,
    ): T =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            if (tenant != null) SignedBindings.bind(c, tenant)
            val result = block(JooqPeriodDueDateStore(DSL.using(c, SQLDialect.POSTGRES)), c)
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

    private fun stored(
        tenant: UUID,
        key: PeriodKey,
    ): Pair<LocalDate, Long>? =
        ConsoleDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select due_date, due_date_version from console_bff.period_due_date " +
                        "where tenant_id = ? and entity_ref = ? and period_ref = ?",
                ).use { st ->
                    st.setObject(1, tenant)
                    st.setString(2, key.entityRef)
                    st.setString(3, key.periodRef)
                    st.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject(1, LocalDate::class.java) to rs.getLong(2) else null
                    }
                }
        }

    @Test
    fun `INV-AC-21077 the column set and the primary key equal the criterion by catalog`() {
        val columns =
            catalog(
                "select column_name, data_type, is_nullable from information_schema.columns " +
                    "where table_schema = 'console_bff' and table_name = 'period_due_date'",
            ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        columns shouldContainExactlyInAnyOrder
            listOf(
                Triple("tenant_id", "uuid", "NO"),
                Triple("entity_ref", "text", "NO"),
                Triple("period_ref", "text", "NO"),
                Triple("due_date", "date", "NO"),
                Triple("due_date_version", "bigint", "NO"),
            )
        val key =
            catalog(
                "select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid " +
                    "and a.attnum = any(i.indkey) where i.indrelid = 'console_bff.period_due_date'::regclass " +
                    "and i.indisprimary order by a.attname",
            ) { it.getString(1) }
        key shouldContainExactly listOf("entity_ref", "period_ref", "tenant_id")
    }

    @Test
    fun `INV-AC-21077 a higher version replaces the row and an equal or lower one changes nothing`() {
        val t = UUID.randomUUID()
        asTenant(t) { s, _ -> s.applyIfNewer(t.toString(), k1, d("2026-12-01"), 2) } shouldBe true
        stored(t, k1) shouldBe (d("2026-12-01") to 2L)
        asTenant(t) { s, _ -> s.applyIfNewer(t.toString(), k1, d("2026-12-02"), 2) } shouldBe false
        stored(t, k1) shouldBe (d("2026-12-01") to 2L)
        asTenant(t) { s, _ -> s.applyIfNewer(t.toString(), k1, d("2026-12-03"), 1) } shouldBe false
        stored(t, k1) shouldBe (d("2026-12-01") to 2L)
        asTenant(t) { s, _ -> s.applyIfNewer(t.toString(), k1, d("2026-12-04"), 3) } shouldBe true
        stored(t, k1) shouldBe (d("2026-12-04") to 3L)
    }

    @Test
    fun `INV-AC-21077 the initial put writes version 0 only when no row exists`() {
        val t = UUID.randomUUID()
        asTenant(t) { s, _ -> s.putInitialIfAbsent(t.toString(), k1, d("2026-11-30")) } shouldBe true
        stored(t, k1) shouldBe (d("2026-11-30") to 0L)
        asTenant(t) { s, _ -> s.putInitialIfAbsent(t.toString(), k1, d("2027-01-01")) } shouldBe false
        stored(t, k1) shouldBe (d("2026-11-30") to 0L)
        asTenant(t) { s, _ -> s.applyIfNewer(t.toString(), k2, d("2026-12-15"), 1) } shouldBe true
        asTenant(t) { s, _ -> s.putInitialIfAbsent(t.toString(), k2, d("2027-01-01")) } shouldBe false
        stored(t, k2) shouldBe (d("2026-12-15") to 1L)
        asTenant(t) { s, _ -> s.applyIfNewer(t.toString(), k1, d("2026-12-20"), 1) } shouldBe true
        stored(t, k1) shouldBe (d("2026-12-20") to 1L)
    }

    @Test
    fun `INV-AC-21077 the read returns the two existing keys and omits the third`() {
        val t = UUID.randomUUID()
        asTenant(t) { s, _ ->
            s.applyIfNewer(t.toString(), k1, d("2026-12-01"), 1)
            s.putInitialIfAbsent(t.toString(), k2, d("2026-12-02"))
        }
        asTenant(t) { s, _ -> s.dueDates(t.toString(), setOf(k1, k2, k3)) } shouldBe
            mapOf(k1 to d("2026-12-01"), k2 to d("2026-12-02"))
        asTenant(t) { s, _ -> s.dueDates(t.toString(), emptySet()) } shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-21077 tenant B sees no row of A and updates 0 rows`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        asTenant(a) { s, _ -> s.applyIfNewer(a.toString(), k1, d("2026-12-01"), 1) }
        asTenant(b) { s, _ -> s.dueDates(b.toString(), setOf(k1)) } shouldBe emptyMap()
        asTenant(b) { s, _ -> s.dueDates(a.toString(), setOf(k1)) } shouldBe emptyMap()
        asTenant(null) { s, _ -> s.dueDates(a.toString(), setOf(k1)) } shouldBe emptyMap()
        val updated =
            asTenant(b) { _, c ->
                c
                    .prepareStatement(
                        "update console_bff.period_due_date set due_date = '2030-01-01' where entity_ref = ?",
                    ).use {
                        it.setString(1, k1.entityRef)
                        it.executeUpdate()
                    }
            }
        updated shouldBe 0
        stored(a, k1) shouldBe (d("2026-12-01") to 1L)
    }

    @Test
    fun `INV-AC-21077 dueDates filters by tenant even when the policy would admit the row`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        asTenant(a) { s, _ -> s.applyIfNewer(a.toString(), k1, d("2026-12-01"), 1) }
        asTenant(a) { s, _ -> s.dueDates(b.toString(), setOf(k1)) } shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-21077 the migration RLS section equals TenantRlsMigration ddl`() {
        val begin = "-- BEGIN TenantRlsMigration.ddl(\"console_bff\", \"period_due_date\")"
        val end = "-- END TenantRlsMigration.ddl"
        val section = migration.substringAfter(begin, "").substringBefore(end, "")
        (section.isNotBlank()) shouldBe true
        section.trim() shouldBe TenantRlsMigration.ddl("console_bff", "period_due_date").trim()
    }

    @Test
    fun `INV-AC-21077 RLS is enabled and forced and one tenant_isolation policy names bound_tenant`() {
        val flags =
            catalog(
                "select relrowsecurity, relforcerowsecurity from pg_class " +
                    "where oid = 'console_bff.period_due_date'::regclass",
            ) { it.getBoolean(1) to it.getBoolean(2) }
        flags shouldBe listOf(true to true)
        val policies =
            catalog(
                "select policyname, cmd, qual, with_check from pg_policies " +
                    "where schemaname = 'console_bff' and tablename = 'period_due_date'",
            ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
        policies.size shouldBe 1
        policies.single()[0] shouldBe "tenant_isolation"
        policies.single()[1] shouldBe "ALL"
        policies.single()[2] shouldContain "console_bff.bound_tenant()"
        policies.single()[3] shouldContain "console_bff.bound_tenant()"
    }

    @Test
    fun `INV-AC-21077 the runtime role holds select insert update and no delete`() {
        val grants =
            catalog(
                "select has_table_privilege('console_bff_runtime', 'console_bff.period_due_date', 'SELECT'), " +
                    "has_table_privilege('console_bff_runtime', 'console_bff.period_due_date', 'INSERT'), " +
                    "has_table_privilege('console_bff_runtime', 'console_bff.period_due_date', 'UPDATE'), " +
                    "has_table_privilege('console_bff_runtime', 'console_bff.period_due_date', 'DELETE')",
            ) { listOf(it.getBoolean(1), it.getBoolean(2), it.getBoolean(3), it.getBoolean(4)) }
        grants shouldBe listOf(listOf(true, true, true, false))
    }
}
