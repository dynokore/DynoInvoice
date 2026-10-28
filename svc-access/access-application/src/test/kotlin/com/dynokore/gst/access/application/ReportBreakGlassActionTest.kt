package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.breakglass.BreakGlassAlerts
import com.dynokore.gst.access.application.breakglass.BreakGlassEffects
import com.dynokore.gst.access.application.breakglass.BreakGlassReportedActions
import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.gst.access.application.breakglass.ReportBreakGlassActionUseCase
import com.dynokore.gst.access.application.breakglass.ReportBreakGlassOutcome
import com.dynokore.gst.access.application.breakglass.ReportRefusal
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Rules of the reported break-glass action: order of checks, idempotence, refusals (INV-Q-9117 A, INV-Q-9313 A). */
class ReportBreakGlassActionTest {
    private val now = Instant.parse("2026-10-28T01:00:00Z")
    private val expiry = now.plusSeconds(3600)

    private val finds = mutableListOf<String>()
    private val stores = mutableListOf<List<Any?>>()
    private val alerts = mutableListOf<Triple<String, String, String>>()
    private val audits = mutableListOf<GrantAuditEntry>()
    private val refusals = mutableListOf<Triple<String, String, ReportRefusal>>()
    private val held = mutableMapOf<String, String>()
    private var sessionsHeld: Map<String, BreakGlassSession> = mapOf("BG-0030" to session())
    private var storeResult: () -> String? = { "BGA-1" }

    private fun session(sealed: Boolean = false) =
        BreakGlassSession("BG-0030", "T-1", 4, now.minusSeconds(60), expiry, listOf("a", "b"), sealed)

    private val useCase =
        ReportBreakGlassActionUseCase(
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            },
            object : BreakGlassSessions {
                override fun insert(
                    sessionHours: Int,
                    unlockedAt: Instant,
                    approvedBy: List<String>,
                ): BreakGlassSession = error("not used")

                override fun find(ref: String): BreakGlassSession? {
                    finds += ref
                    return sessionsHeld[ref]
                }

                override fun insertAction(
                    unlockRef: String,
                    action: BreakGlassAction,
                    target: String?,
                    callerService: String,
                ): String = error("not used")
            },
            object : BreakGlassReportedActions {
                override fun find(reportId: String): String? = held[reportId]

                override fun store(
                    unlockRef: String,
                    action: BreakGlassAction,
                    target: String?,
                    callerService: String,
                    reportId: String,
                ): String? {
                    stores += listOf(unlockRef, action, target, callerService, reportId)
                    val result = storeResult() ?: return null
                    held[reportId] = result
                    return result
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
            { reportId, unlockRef, reason -> refusals += Triple(reportId, unlockRef, reason) },
            Clock.fixed(now.plusSeconds(86_400), ZoneOffset.UTC),
        )

    private fun report(
        reportId: String = "R1",
        unlockRef: String = "BG-0030",
        action: String = "VIEW",
        at: Instant = now,
        caller: String = "tenancy-svc",
    ) = useCase.handle("IDN-SYS", reportId, unlockRef, action, "q", at, caller)

    private fun assertNothingWritten() {
        assertTrue(stores.isEmpty() && alerts.isEmpty() && audits.isEmpty())
    }

    @Test
    fun `one second before the expiry the action is stored with one alert and one audit entry`() {
        assertEquals(ReportBreakGlassOutcome.Stored("BGA-1"), report(at = expiry.minusSeconds(1)))
        assertEquals(1, stores.size)
        assertEquals(listOf(Triple("ACC-002", "VIEW", "BG-0030")), alerts)
        assertEquals(1, audits.size)
        assertTrue(refusals.isEmpty())
    }

    @Test
    fun `at the expiry the report is refused as not open and nothing is stored`() {
        assertEquals(ReportBreakGlassOutcome.Refused(ReportRefusal.NOT_OPEN), report(at = expiry))
        assertNothingWritten()
        assertEquals(listOf(Triple("R1", "BG-0030", ReportRefusal.NOT_OPEN)), refusals)
    }

    @Test
    fun `a sealed session is refused as not open`() {
        sessionsHeld = mapOf("BG-0030" to session(sealed = true))
        assertEquals(ReportBreakGlassOutcome.Refused(ReportRefusal.NOT_OPEN), report())
        assertNothingWritten()
        assertEquals(1, refusals.size)
    }

    @Test
    fun `BG-0003 is not found while BG-0030 is held`() {
        assertEquals(ReportBreakGlassOutcome.Refused(ReportRefusal.NOT_FOUND), report(unlockRef = "BG-0003"))
        assertNothingWritten()
        assertEquals(listOf(Triple("R1", "BG-0003", ReportRefusal.NOT_FOUND)), refusals)
    }

    @Test
    fun `an action outside the four names is refused without a session lookup`() {
        listOf("SHUTDOWN", "View", "VIEW ", "").forEach { bad ->
            refusals.clear()
            assertEquals(ReportBreakGlassOutcome.Refused(ReportRefusal.BAD_ACTION), report(action = bad), bad)
            assertEquals(listOf(Triple("R1", "BG-0030", ReportRefusal.BAD_ACTION)), refusals)
        }
        assertTrue(finds.isEmpty())
        assertNothingWritten()
    }

    @Test
    fun `an unknown unlockRef with a bad action is a bad action`() {
        assertEquals(
            ReportBreakGlassOutcome.Refused(ReportRefusal.BAD_ACTION),
            report(unlockRef = "BG-9999", action = "SHUTDOWN"),
        )
        assertTrue(finds.isEmpty())
    }

    @Test
    fun `the same report id twice stores once and the second is a duplicate`() {
        report()
        assertEquals(ReportBreakGlassOutcome.Duplicate, report())
        assertEquals(1, stores.size)
        assertEquals(1, alerts.size)
        assertEquals(1, audits.size)
        assertTrue(refusals.isEmpty())
    }

    @Test
    fun `a session sealed between the deliveries leaves the second one a duplicate`() {
        report()
        sessionsHeld = mapOf("BG-0030" to session(sealed = true))
        assertEquals(ReportBreakGlassOutcome.Duplicate, report())
        assertTrue(refusals.isEmpty())
        assertEquals(1, stores.size)
    }

    @Test
    fun `a store that returns null is a duplicate and writes no effects`() {
        storeResult = { null }
        assertEquals(ReportBreakGlassOutcome.Duplicate, report())
        assertEquals(1, stores.size)
        assertTrue(alerts.isEmpty() && audits.isEmpty() && refusals.isEmpty())
    }

    @Test
    fun `a store that throws reaches the caller and writes no effects`() {
        val boom = IllegalStateException("db down")
        storeResult = { throw boom }
        assertSame(boom, assertThrows<IllegalStateException> { report() })
        assertTrue(alerts.isEmpty() && audits.isEmpty() && refusals.isEmpty())
    }

    @Test
    fun `the caller service reaches the store and the audit detail unchanged`() {
        listOf("tenancy-svc", "tenancy-svc2").forEach { caller ->
            stores.clear()
            audits.clear()
            report(reportId = "R-$caller", action = "DRAIN", caller = caller)
            assertEquals(listOf(listOf("BG-0030", BreakGlassAction.DRAIN, "q", caller, "R-$caller")), stores)
            assertEquals(listOf("DRAIN by $caller"), audits.map { it.reason })
            assertEquals("IDN-SYS", audits.single().actor)
            assertEquals("recordBreakGlassAction", audits.single().action)
        }
    }
}
