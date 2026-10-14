package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * [AuditEntry] and [OverrideCommand] refuse an actor that is blank or holds an ISO control character anywhere, by
 * tenancy-kernel's shared actor rule (INV-T-4616; coordinator rulings INV-Q-4614 (A), INV-Q-4622 (A)), so an actor the
 * SQL checks would refuse is never staged.
 */
class ActorRuleTest {
    @Test
    fun `an audit entry refuses a blank or control actor and keeps a visible one unchanged`() {
        assertEquals(emptyList<String>(), NOT_ACTORS.filter { runCatching { entry(it) }.isSuccess })
        VISIBLE.forEach { assertEquals(it, entry(it).actor) }
    }

    @Test
    fun `an override command refuses a blank or control actor and keeps a visible one unchanged`() {
        assertEquals(emptyList<String>(), NOT_ACTORS.filter { runCatching { command(it) }.isSuccess })
        VISIBLE.forEach { assertEquals(it, command(it).actor) }
    }

    @Test
    fun `the refusal is an IllegalArgumentException`() {
        NOT_ACTORS.forEach {
            assertEquals(IllegalArgumentException::class, runCatching { entry(it) }.exceptionOrNull()!!::class)
            assertEquals(IllegalArgumentException::class, runCatching { command(it) }.exceptionOrNull()!!::class)
        }
    }

    private fun entry(actor: String) = AuditEntry(actor, "grantRole", "tenant", "reason")

    private fun command(actor: String) =
        OverrideCommand(
            tenant = TenantId(UUID.randomUUID()),
            entity = EntityRef("entity-1"),
            docUuid = DocUuid(UUID.randomUUID()),
            documentVersion = 1,
            transactionDate = LocalDate.of(2026, 9, 30),
            code = "MDM-003",
            reason = "UEN confirmed",
            actor = actor,
            actorRoles = setOf("tax.approver"),
            priorStateJson = "{}",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cmd-1"),
        )

    private companion object {
        val NOT_ACTORS =
            listOf("", "\t", "\n", "\r", "\u0085", " ", " ", "　", "﻿", "\u0001", "\u009F") +
                listOf("  \t　\r\n", "a\tb", "\nx", "x\r", "x\u0085", "\u001B[31mx", "x\u009B", "x\u0000")
        val VISIBLE = listOf("x​y", "CASE 5521", " user-7 ", "a".repeat(254) + "z")
    }
}
