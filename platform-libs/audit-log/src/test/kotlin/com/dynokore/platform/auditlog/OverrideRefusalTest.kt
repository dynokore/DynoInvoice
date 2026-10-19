package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/** Refusals and payload shaping need no database: they are decided before any transaction opens. */
class OverrideRefusalTest {
    private val untouchable =
        object : TransactionRunner, CurrentConnection {
            override fun <T> inTransaction(block: () -> T): T = error("a refused override must not open a transaction")

            override fun require(): Connection = error("a refused override must not use a connection")
        }

    // A tenancy-jooq DSLContext whose runner opens nothing: a statement would fail, a refusal never gets that far.
    private val dsl = TenancyJooq.dslContext(emptyMap(), TenantContextImpl(), untouchable)
    private val noAudit =
        object : AuditWriter {
            override fun record(
                entry: AuditEntry,
                context: EventContext,
            ): Envelope = error("a refused override must not write an entry")
        }
    private val tenant = UUID.randomUUID()

    private fun service(policy: OverridePolicy = OverridePolicy()) =
        JdbcOverrideService(
            dsl,
            untouchable,
            noAudit,
            AuditHarness.CATALOGUE,
            "submission",
            AuditHarness.CLOCK,
            policy,
        )

    // The command builders live in the harness; its constructor needs Docker, so build commands here.
    private fun command(
        code: String = "MDM-003",
        reason: String? = "because",
        roles: Set<String> = setOf("tax.approver"),
    ) = OverrideCommand(
        com.dynokore.platform.tenancykernel
            .TenantId(tenant),
        com.dynokore.platform.outboxandqueue
            .EntityRef("e"),
        com.dynokore.platform.outboxandqueue
            .DocUuid(UUID.randomUUID()),
        1,
        null,
        code,
        reason,
        "u",
        roles,
        "{}",
        com.dynokore.platform.outboxandqueue
            .CorrelationId("c"),
        com.dynokore.platform.outboxandqueue
            .CausationId("k"),
    )

    @Test
    fun `refusals are decided before any transaction or write`() {
        fun reason(c: OverrideCommand) = assertThrows(OverrideRefused::class.java) { service().override(c) }.reason
        assertEquals(RefusalReason.TERMINAL, reason(command(code = "SUB-009")))
        val everyRole = setOf("tax.approver", "platform.engineer")
        assertEquals(RefusalReason.TERMINAL, reason(command(code = "SUB-009", roles = everyRole)))
        assertEquals(RefusalReason.NOT_OVERRIDABLE, reason(command(code = "PAY-001")))
        assertEquals(RefusalReason.UNKNOWN_CODE, reason(command(code = "NOPE")))
        assertEquals(RefusalReason.REASON_REQUIRED, reason(command(reason = "")))
        assertEquals(RefusalReason.ROLE_NOT_AUTHORISED, reason(command(roles = emptySet())))
    }

    @Test
    fun `one tax approver is enough and no other role is`() {
        val policy = OverridePolicy()
        assertEquals(true, policy.permits(setOf("tax.approver")))
        assertEquals(false, policy.permits(setOf("tenant.admin", "platform.engineer")))
        val refused =
            assertThrows(OverrideRefused::class.java) {
                service(policy).override(command(roles = setOf("tenant.admin")))
            }
        assertEquals(RefusalReason.ROLE_NOT_AUTHORISED, refused.reason)
    }

    @Test
    fun `a TERMINAL code stays refused whatever the policy and roles`() {
        val policy = OverridePolicy()
        assertEquals(
            RefusalReason.TERMINAL,
            assertThrows(OverrideRefused::class.java) {
                service(policy).override(command(code = "SUB-009", roles = setOf("tax.approver")))
            }.reason,
        )
    }

    @Test
    fun `an outbox failure surfaces as AuditWriteFailed and a bad prior state is rejected`() {
        val failing =
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<Envelope> = error("outbox refuses")
            }
        val writer = OutboxAuditWriter(failing)
        val context = EventContextSample.context(tenant)
        assertThrows(AuditWriteFailed::class.java) { writer.record(AuditEntry("a", "x", "s"), context) }
        val recording =
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<Envelope> = error("a malformed entry must not reach the outbox")
            }
        listOf("[1]", "{oops").forEach { prior ->
            assertThrows(IllegalArgumentException::class.java) {
                OutboxAuditWriter(recording).record(AuditEntry("a", "x", "s", priorStateJson = prior), context)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { AuditEntry("", "x", "s") }
        assertThrows(IllegalArgumentException::class.java) { AuditEntry("a", "x", "s", reason = " ") }
    }
}

private object EventContextSample {
    fun context(tenant: UUID) =
        EventContext(
            com.dynokore.platform.tenancykernel
                .TenantId(tenant),
            com.dynokore.platform.outboxandqueue
                .EntityRef("e"),
            null,
            null,
            "k",
            null,
            com.dynokore.platform.outboxandqueue
                .CorrelationId("c"),
            com.dynokore.platform.outboxandqueue
                .CausationId("k"),
        )
}
