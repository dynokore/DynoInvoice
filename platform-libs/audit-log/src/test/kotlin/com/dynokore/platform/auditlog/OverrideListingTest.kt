package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** Query refusals and the listing's DDL need no database: they are decided before any select or audit write. */
class OverrideListingTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val jan = LocalDate.of(2026, 1, 1)

    private fun query(
        from: LocalDate = jan,
        to: LocalDate = LocalDate.of(2026, 3, 31),
        reader: String = "auditor-1",
    ) = OverrideListQuery(tenant, EntityRef("E1"), from, to, reader, CorrelationId("corr-1"), CausationId("cmd-1"))

    @Test
    fun `a range that ends before it starts and a blank reader are refused`() {
        assertThrows(IllegalArgumentException::class.java) { query(to = jan.minusDays(1)) }
        assertThrows(IllegalArgumentException::class.java) { query(reader = "") }
        assertThrows(IllegalArgumentException::class.java) { query(reader = "  ") }
        assertEquals(jan, query(to = jan).to, "a one-day range is allowed")
    }

    @Test
    fun `a refused query reaches neither the database nor the audit writer`() {
        var calls = 0
        val writer =
            object : AuditWriter {
                override fun record(
                    entry: AuditEntry,
                    context: EventContext,
                ): Envelope {
                    calls++
                    error("must not be called")
                }
            }
        val untouchable =
            object : TransactionRunner, CurrentConnection {
                override fun <T> inTransaction(block: () -> T): T = error("no transaction for a refused query")

                override fun require(): Connection = error("no connection for a refused query")
            }
        val dsl = TenancyJooq.dslContext(emptyMap(), TenantContextImpl(), untouchable)
        val service: OverrideListing =
            JdbcOverrideService(dsl, untouchable, writer, AuditHarness.CATALOGUE, "submission", AuditHarness.CLOCK)
        assertThrows(IllegalArgumentException::class.java) { service.list(query(to = jan.minusDays(1))) }
        assertThrows(IllegalArgumentException::class.java) { service.list(query(reader = " ")) }
        // A reader the audit entry itself refuses (control character) is also refused before the select.
        assertThrows(IllegalArgumentException::class.java) { service.list(query(reader = "a\u0000b")) }
        assertEquals(0, calls)
    }

    @Test
    fun `the DDL carries the entity and date columns, the index and an insert-only grant`() {
        val ddl = AuditSchema.ddl("submission", "submission_app")
        assertTrue(ddl.contains("entity_ref text not null"))
        assertTrue(ddl.contains("transaction_date date,"), "nullable: no not-null on the date")
        assertTrue(
            ddl.contains(
                "override_grant_tenant_entity_date on submission.override_grant (tenant_id, entity_ref, transaction_date)",
            ),
        )
        assertTrue(ddl.contains("grant select, insert on submission.override_grant to submission_app;"))
        assertFalse(grantsMore(ddl))
    }

    @Test
    fun `a look-alike DDL granting update or delete fails the same assertion`() {
        val ddl = AuditSchema.ddl("submission", "submission_app")
        val base = "grant select, insert on submission.override_grant"
        assertFalse(grantsMore(ddl))
        assertTrue(grantsMore(ddl.replace(base, "grant select, insert, update on submission.override_grant")))
        assertTrue(grantsMore(ddl.replace(base, "GRANT SELECT, INSERT, DELETE ON submission.override_grant")))
        assertTrue(grantsMore(ddl + "\ngrant update on submission.override_grant to x;"))
    }

    private fun grantsMore(ddl: String) =
        Regex("""\bgrant\b[^;]*\b(update|delete|all)\b""", RegexOption.IGNORE_CASE).containsMatchIn(ddl)
}
