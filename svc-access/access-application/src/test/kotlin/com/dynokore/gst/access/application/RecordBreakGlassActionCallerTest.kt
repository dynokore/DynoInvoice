package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.breakglass.BreakGlassAlerts
import com.dynokore.gst.access.application.breakglass.BreakGlassEffects
import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.gst.access.application.breakglass.RecordBreakGlassActionUseCase
import com.dynokore.gst.access.application.breakglass.RecordBreakGlassOutcome
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The verified calling service is stored with the action and named in the audit entry (INV-Q-9113 A). */
class RecordBreakGlassActionCallerTest {
    private val now = Instant.parse("2026-10-28T01:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val inserted = mutableListOf<List<Any?>>()
    private val alerts = mutableListOf<Triple<String, String, String>>()
    private val audits = mutableListOf<GrantAuditEntry>()

    private fun session(
        sealed: Boolean = false,
        expiresAt: Instant = now.plusSeconds(3600),
    ) = BreakGlassSession("BG-1", "T-1", 4, now.minusSeconds(60), expiresAt, listOf("a", "b"), sealed)

    private fun useCase(found: BreakGlassSession?) =
        RecordBreakGlassActionUseCase(
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            object : BreakGlassSessions {
                override fun insert(
                    sessionHours: Int,
                    unlockedAt: Instant,
                    approvedBy: List<String>,
                ): BreakGlassSession = error("not used")

                override fun find(ref: String): BreakGlassSession? = found

                override fun insertAction(
                    unlockRef: String,
                    action: BreakGlassAction,
                    target: String?,
                    callerService: String,
                ): String {
                    inserted += listOf(unlockRef, action, target, callerService)
                    return "BGA-1"
                }
            },
            BreakGlassEffects(
                BreakGlassAlerts { code, action, ref -> alerts += Triple(code, action, ref) },
                object : GrantAudit {
                    override fun record(entry: GrantAuditEntry) {
                        audits += entry
                    }
                },
            ),
            clock,
        )

    @Test
    fun `the caller is stored as given and the audit detail names it`() {
        listOf("ingestion-svc", "ingestion-svc2").forEach { caller ->
            inserted.clear()
            audits.clear()
            alerts.clear()
            val outcome = useCase(session()).handle("IDN-ADM", "BG-1", BreakGlassAction.DRAIN, "q", caller)
            assertInstanceOf(RecordBreakGlassOutcome.Recorded::class.java, outcome)
            assertEquals(listOf(listOf("BG-1", BreakGlassAction.DRAIN, "q", caller)), inserted)
            assertEquals(listOf("DRAIN by $caller"), audits.map { it.reason })
            assertEquals("IDN-ADM", audits.single().actor)
            assertEquals(listOf(Triple("ACC-002", "DRAIN", "BG-1")), alerts)
        }
    }

    @Test
    fun `a sealed, expired or unknown session inserts and writes nothing`() {
        val cases =
            mapOf(
                session(sealed = true) to RecordBreakGlassOutcome.NotOpen,
                session(expiresAt = now) to RecordBreakGlassOutcome.NotOpen,
                null to RecordBreakGlassOutcome.NotFound,
            )
        cases.forEach { (found, expected) ->
            assertEquals(expected, useCase(found).handle("IDN-ADM", "BG-1", BreakGlassAction.VIEW, null, "ingestion-svc"))
        }
        assertTrue(inserted.isEmpty() && audits.isEmpty() && alerts.isEmpty())
    }
}
