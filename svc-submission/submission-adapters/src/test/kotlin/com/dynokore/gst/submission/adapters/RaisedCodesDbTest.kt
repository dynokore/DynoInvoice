package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.JooqRaisedCodes
import com.dynokore.gst.submission.application.arrival.RaisedCode
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** The raised-code store as `submission_runtime`: table shape, grants, tenant isolation, idempotent insert. */
class RaisedCodesDbTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
    private val store = JooqRaisedCodes(dsl)
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val at = Instant.parse("2026-11-12T03:00:00Z")

    @AfterEach
    fun close() = connection.close()

    private fun <T> asTenant(
        tenant: UUID,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        return try {
            block().also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        }
    }

    private fun raised(
        doc: String,
        version: Int = 1,
        code: String = "SUB-010",
    ) = RaisedCode(doc, version, "E-001", "2026-Q1", code, null, null)

    private fun rows(
        tenant: UUID,
        doc: String,
    ): List<List<Any?>> =
        asTenant(tenant) {
            dsl
                .fetch(SELECT_ROWS, doc)
                .map { listOf(it.get(0), it.get(1), it.get(2, OffsetDateTime::class.java).toInstant()) }
        }

    private fun Connection.fails(sql: String): String? =
        createStatement().use { st ->
            runCatching { st.execute(sql) }.exceptionOrNull()?.let { (it as SQLException).sqlState }
        }

    @Test
    fun `INV-AC-21088 the table has exactly the named columns and the unique key`() {
        SubmissionDb.superuser().use { c ->
            val cols = mutableListOf<String>()
            c.createStatement().use { st ->
                st.executeQuery(
                    "select column_name from information_schema.columns where table_schema = 'submission' " +
                        "and table_name = 'raised_code' order by ordinal_position",
                ).use { rs -> while (rs.next()) cols += rs.getString(1) }
            }
            cols shouldBe
                listOf("seq", "tenant_id", "doc_uuid", "version", "entity_ref", "period_ref", "code", "raised_at")
            val keys = mutableListOf<String>()
            c.createStatement().use { st ->
                st.executeQuery(
                    "select pg_get_constraintdef(oid) from pg_constraint " +
                        "where conrelid = 'submission.raised_code'::regclass and contype = 'u'",
                ).use { rs -> while (rs.next()) keys += rs.getString(1) }
            }
            keys shouldBe listOf("UNIQUE (tenant_id, doc_uuid, version, code)")
        }
    }

    @Test
    fun `INV-AC-21088 the runtime role holds select and insert and neither update nor delete`() {
        fun has(privilege: String) =
            dsl.fetchValue("select has_table_privilege('submission_runtime', 'submission.raised_code', ?)", privilege)
        has("SELECT") shouldBe true
        has("INSERT") shouldBe true
        has("UPDATE") shouldBe false
        has("DELETE") shouldBe false
        val doc = UUID.randomUUID().toString()
        asTenant(tenantA) { store.append(TenantId(tenantA), raised(doc), at) } shouldBe true
        connection.fails("update submission.raised_code set code = 'X-1'") shouldBe "42501"
        connection.rollback()
        connection.fails("delete from submission.raised_code") shouldBe "42501"
        connection.rollback()
        rows(tenantA, doc).size shouldBe 1
    }

    @Test
    fun `INV-AC-21088 another tenant reads no row`() {
        val doc = UUID.randomUUID().toString()
        asTenant(tenantA) { store.append(TenantId(tenantA), raised(doc), at) } shouldBe true
        rows(tenantB, doc) shouldBe emptyList()
        rows(tenantA, doc).size shouldBe 1
    }

    @Test
    fun `INV-AC-21088 a repeat inserts no row and a new version or code inserts one`() {
        val doc = UUID.randomUUID().toString()
        val first = asTenant(tenantA) { store.append(TenantId(tenantA), raised(doc), at) }
        val repeat = asTenant(tenantA) { store.append(TenantId(tenantA), raised(doc), at.plusSeconds(60)) }
        val version2 = asTenant(tenantA) { store.append(TenantId(tenantA), raised(doc, version = 2), at) }
        val other = asTenant(tenantA) { store.append(TenantId(tenantA), raised(doc, code = "PER-007"), at) }
        listOf(first, repeat, version2, other) shouldBe listOf(true, false, true, true)
        rows(tenantA, doc) shouldBe
            listOf(listOf(1, "SUB-010", at), listOf(2, "SUB-010", at), listOf(1, "PER-007", at))
    }

    @Test
    fun `INV-AC-21088 raised_at is the instant given and not the database clock`() {
        val doc = UUID.randomUUID().toString()
        val past = Instant.parse("2020-01-02T03:04:05Z")
        asTenant(tenantA) { store.append(TenantId(tenantA), raised(doc), past) } shouldBe true
        rows(tenantA, doc).single()[2] shouldBe past
    }

    private companion object {
        const val SELECT_ROWS =
            "select version, code, raised_at from submission.raised_code where doc_uuid = ? order by seq"
    }
}
