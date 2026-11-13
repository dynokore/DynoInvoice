package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `console_bff.exception` and `JooqExceptionStore` on a live PostgreSQL as the runtime role under a signed binding
 * (INV-AC-21026; PostgreSQL forced row level security, least privilege, idempotent upserts).
 */
class ExceptionStoreDbTest {
    private val migration =
        checkNotNull(javaClass.getResource("/db/migration/V202611121100__console_bff_exception.sql")).readText()

    /** Runs [block] as the runtime role in one transaction bound to [tenant]; `null` binds nothing. */
    private fun <T> asTenant(
        tenant: UUID?,
        block: (JooqExceptionStore, java.sql.Connection) -> T,
    ): T =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            if (tenant != null) SignedBindings.bind(c, tenant)
            val result = block(JooqExceptionStore(DSL.using(c, SQLDialect.POSTGRES)), c)
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

    private fun row(
        tenant: UUID,
        id: UUID = UUID.randomUUID(),
        doc: String = "doc-1",
        state: ItemState = ItemState.OPEN,
    ) = ExceptionRow(
        tenantId = tenant.toString(),
        exceptionId = id,
        entityRef = "entity-1",
        docUuid = doc,
        code = "ERR-1",
        remediationOwner = "OWNER",
        disposition = "FIX",
        state = state,
        raisedAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
        periodRef = "2026-10",
        docsequence = 7L,
    )

    @Test
    fun `INV-AC-21026 the column set and the primary key equal the criterion by catalog`() {
        val columns =
            catalog(
                "select column_name, data_type, is_nullable from information_schema.columns " +
                    "where table_schema = 'console_bff' and table_name = 'exception'",
            ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
        columns shouldContainExactlyInAnyOrder
            listOf(
                Triple("tenant_id", "uuid", "NO"),
                Triple("exception_id", "uuid", "NO"),
                Triple("entity_ref", "text", "NO"),
                Triple("doc_uuid", "text", "NO"),
                Triple("code", "text", "NO"),
                Triple("state", "text", "NO"),
                Triple("raised_at", "timestamp with time zone", "NO"),
                Triple("remediation_owner", "text", "NO"),
                Triple("disposition", "text", "NO"),
                Triple("period_ref", "text", "NO"),
                Triple("docsequence", "bigint", "NO"),
            )
        val key =
            catalog(
                "select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid " +
                    "and a.attnum = any(i.indkey) where i.indrelid = 'console_bff.exception'::regclass " +
                    "and i.indisprimary order by a.attname",
            ) { it.getString(1) }
        key shouldContainExactly listOf("exception_id", "tenant_id")
    }

    @Test
    fun `INV-AC-21026 a repeated upsert leaves one row`() {
        val tenant = UUID.randomUUID()
        val r = row(tenant)
        asTenant(tenant) { s, _ -> s.upsert(listOf(r)) }
        asTenant(tenant) { s, _ -> s.upsert(listOf(r)) }
        asTenant(tenant) { s, _ -> s.rowsOfDocument(tenant.toString(), "doc-1") } shouldContainExactly listOf(r)
    }

    @Test
    fun `INV-AC-21026 a changed state replaces the stored state`() {
        val tenant = UUID.randomUUID()
        val r = row(tenant)
        asTenant(tenant) { s, _ -> s.upsert(listOf(r)) }
        asTenant(tenant) { s, _ -> s.upsert(listOf(r.copy(state = ItemState.RESOLVED))) }
        asTenant(tenant) { s, _ -> s.rowsOfDocument(tenant.toString(), "doc-1") }
            .map { it.state } shouldContainExactly listOf(ItemState.RESOLVED)
    }

    @Test
    fun `INV-AC-21026 tenant A reads its row, tenant B and an unbound connection read none`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val r = row(a)
        asTenant(a) { s, _ -> s.upsert(listOf(r)) }
        asTenant(a) { s, _ -> s.openRowsOfDocument(a.toString(), "doc-1") } shouldContainExactly listOf(r)
        asTenant(b) { s, _ -> s.openRowsOfDocument(a.toString(), "doc-1") } shouldBe emptyList()
        asTenant(null) { s, _ -> s.openRowsOfDocument(a.toString(), "doc-1") } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21026 tenant B cannot update the row of A`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val r = row(a)
        asTenant(a) { s, _ -> s.upsert(listOf(r)) }
        val updated =
            asTenant(b) { _, c ->
                c.prepareStatement("update console_bff.exception set state = 'RESOLVED' where exception_id = ?").use {
                    it.setObject(1, r.exceptionId)
                    it.executeUpdate()
                }
            }
        updated shouldBe 0
        asTenant(a) { s, _ -> s.rowsOfDocument(a.toString(), "doc-1") }
            .map { it.state } shouldContainExactly listOf(ItemState.OPEN)
    }

    @Test
    fun `INV-AC-21026 the migration RLS section equals TenantRlsMigration ddl`() {
        val begin = "-- BEGIN TenantRlsMigration.ddl(\"console_bff\", \"exception\")"
        val end = "-- END TenantRlsMigration.ddl"
        val section = migration.substringAfter(begin, "").substringBefore(end, "")
        (section.isNotBlank()) shouldBe true
        section.trim() shouldBe TenantRlsMigration.ddl("console_bff", "exception").trim()
    }

    @Test
    fun `INV-AC-21026 RLS is enabled and forced and one tenant_isolation policy names bound_tenant`() {
        val flags =
            catalog(
                "select relrowsecurity, relforcerowsecurity from pg_class " +
                    "where oid = 'console_bff.exception'::regclass",
            ) { it.getBoolean(1) to it.getBoolean(2) }
        flags shouldBe listOf(true to true)
        val policies =
            catalog(
                "select policyname, cmd, qual, with_check from pg_policies " +
                    "where schemaname = 'console_bff' and tablename = 'exception'",
            ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
        policies.size shouldBe 1
        policies.single()[0] shouldBe "tenant_isolation"
        policies.single()[1] shouldBe "ALL"
        policies.single()[2] shouldContain "console_bff.bound_tenant()"
        policies.single()[3] shouldContain "console_bff.bound_tenant()"
    }

    @Test
    fun `INV-AC-21026 the runtime role holds select insert update and no delete`() {
        val grants =
            catalog(
                "select has_table_privilege('console_bff_runtime', 'console_bff.exception', 'SELECT'), " +
                    "has_table_privilege('console_bff_runtime', 'console_bff.exception', 'INSERT'), " +
                    "has_table_privilege('console_bff_runtime', 'console_bff.exception', 'UPDATE'), " +
                    "has_table_privilege('console_bff_runtime', 'console_bff.exception', 'DELETE')",
            ) { listOf(it.getBoolean(1), it.getBoolean(2), it.getBoolean(3), it.getBoolean(4)) }
        grants shouldBe listOf(listOf(true, true, true, false))
    }

    @Test
    fun `INV-AC-21026 openRowsOfDocument returns OPEN rows of the given tenant and document only`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val open = row(a, doc = "doc-1")
        asTenant(a) { s, _ ->
            s.upsert(
                listOf(
                    open,
                    row(a, doc = "doc-1", state = ItemState.RESOLVED),
                    row(a, doc = "doc-2"),
                ),
            )
        }
        asTenant(b) { s, _ -> s.upsert(listOf(row(b, doc = "doc-1"))) }
        asTenant(a) { s, _ -> s.openRowsOfDocument(a.toString(), "doc-1") } shouldContainExactly listOf(open)
        // a store asked for another tenant's id under this binding finds nothing either way
        asTenant(b) { s, _ -> s.openRowsOfDocument(a.toString(), "doc-1") } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21026 openRowsOfDocument filters by tenant even when the policy would admit the row`() {
        // the binding is tenant A; asking for tenant B's id must still return nothing (the store names the tenant)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        asTenant(a) { s, _ -> s.upsert(listOf(row(a))) }
        asTenant(a) { s, _ -> s.openRowsOfDocument(b.toString(), "doc-1") } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21026 a stored OVERRIDDEN row is not replaced by an OPEN row of the same exceptionId`() {
        val tenant = UUID.randomUUID()
        val r = row(tenant, state = ItemState.OVERRIDDEN)
        asTenant(tenant) { s, _ -> s.upsert(listOf(r)) }
        asTenant(tenant) { s, _ -> s.upsert(listOf(r.copy(state = ItemState.OPEN, code = "ERR-2"))) }
        asTenant(tenant) { s, _ -> s.rowsOfDocument(tenant.toString(), "doc-1") } shouldContainExactly listOf(r)
    }
}
