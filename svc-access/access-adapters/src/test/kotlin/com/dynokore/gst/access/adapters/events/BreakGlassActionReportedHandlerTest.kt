package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.breakglass.BreakGlassAlerts
import com.dynokore.gst.access.application.breakglass.BreakGlassEffects
import com.dynokore.gst.access.application.breakglass.BreakGlassReportedActions
import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.gst.access.application.breakglass.ReportBreakGlassActionUseCase
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Decoding of the platform event BreakGlassActionReported and the outcome it maps to (INV-Q-9117 A, INV-Q-9313 A). */
class BreakGlassActionReportedHandlerTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val at = Instant.parse("2026-10-29T10:00:00Z")
    private val stores = mutableListOf<List<Any?>>()
    private val actors = mutableListOf<String>()
    private val held = mutableSetOf<String>()

    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor() = "workload:access-svc"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val handler =
        BreakGlassActionReportedHandler(
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

                    override fun find(ref: String): BreakGlassSession? =
                        BreakGlassSession(
                            "BG-1",
                            "T-1",
                            4,
                            at.minusSeconds(60),
                            at.plusSeconds(3600),
                            listOf("a", "b"),
                            false,
                        )

                    override fun insertAction(
                        unlockRef: String,
                        action: BreakGlassAction,
                        target: String?,
                        callerService: String,
                    ): String = error("not used")

                    override fun markSealed(ref: String) = error("not used")
                },
                object : BreakGlassReportedActions {
                    override fun find(reportId: String): String? = if (reportId in held) "BGA-0" else null

                    override fun store(
                        unlockRef: String,
                        action: BreakGlassAction,
                        target: String?,
                        callerService: String,
                        reportId: String,
                    ): String? {
                        stores += listOf(unlockRef, action, target, callerService, reportId)
                        return "BGA-1"
                    }
                },
                BreakGlassEffects(
                    BreakGlassAlerts { _, _, _ -> },
                    object : GrantAudit {
                        override fun record(entry: GrantAuditEntry) {
                            actors += entry.actor
                        }
                    },
                ),
                { _, _, _ -> },
                Clock.fixed(at, ZoneOffset.UTC),
            ),
            JsonMapper.builder().build(),
            tenants,
            setOf("tenancy-svc"),
        )

    private fun envelope(
        data: String,
        type: String = "com.dynokore.gst.platform.BreakGlassActionReported.v1",
        entity: EntityRef = EntityRef.TENANT_WIDE,
        source: String = "/gst/tenancy-svc",
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = source,
        type = type,
        subject = "break-glass:BG-1",
        time = at,
        tenant = tenant,
        entity = entity,
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "tenancy-svc-0",
        correlation = CorrelationId("c"),
        causation = CausationId("c"),
        dataJson = data,
    )

    private fun payload(
        reportId: String = "R1",
        occurredAt: String = "2026-10-29T10:00:00Z",
        action: String = "VIEW",
        target: String? = "q",
        extra: String = "",
    ): String {
        val targetPart = if (target == null) "" else ""","target":"$target""""
        val head = """{"reportId":"$reportId","unlockRef":"BG-1","action":"$action","""
        return head + """"occurredAt":"$occurredAt"$targetPart$extra}"""
    }

    private val real = EntityRef(UUID.randomUUID().toString())

    @Test
    fun `the valid event with the tenant-wide entity and a real tenant is applied`() {
        assertEquals(HandleOutcome.APPLIED, handler.handle(envelope(payload())))
        assertEquals(listOf(listOf("BG-1", BreakGlassAction.VIEW, "q", "tenancy-svc", "R1")), stores)
        assertEquals(listOf("workload:access-svc"), actors)
    }

    @Test
    fun `a report already held is ignored`() {
        held += "R1"
        assertEquals(HandleOutcome.IGNORED, handler.handle(envelope(payload())))
        assertTrue(stores.isEmpty())
    }

    @Test
    fun `the domain-class type of before INV-Q-9313 is a violation`() {
        assertRefused("com.dynokore.gst.tenancy.BreakGlassActionReported.v1")
    }

    @Test
    fun `a type with a changed name is a violation`() {
        assertRefused("com.dynokore.gst.platform.BreakGlassActionReportedX.v1")
    }

    @Test
    fun `a type with a changed case is a violation`() {
        assertRefused("com.dynokore.gst.platform.breakGlassActionReported.v1")
    }

    @Test
    fun `a source outside the declared producers is a violation and nothing is stored`() {
        for (source in listOf("/gst/tenancy-svc-alt", "/gst/access-svc", "/gst/tenancy")) {
            assertThrows<ContractViolation> { handler.handle(envelope(payload(), source = source)) }
        }
        assertTrue(stores.isEmpty())
    }

    private fun assertRefused(type: String) {
        val e = assertThrows<ContractViolation> { handler.handle(envelope(payload(), type, real)) }
        assertTrue(e.message!!.contains(type))
        assertTrue(stores.isEmpty())
    }

    @Test
    fun `a callerService property is ignored`() {
        handler.handle(envelope(payload(extra = ""","callerService":"evil-svc","other":1""")))
        assertEquals("tenancy-svc", stores.single()[3])
    }

    @Test
    fun `a report id of 64 characters is read and of 65 is a violation`() {
        assertEquals(HandleOutcome.APPLIED, handler.handle(envelope(payload(reportId = "r".repeat(64)))))
        assertEquals("r".repeat(64), stores.single()[4])
        assertThrows<ContractViolation> { handler.handle(envelope(payload(reportId = "r".repeat(65)))) }
        assertEquals(1, stores.size)
    }

    @Test
    fun `an occurredAt without an offset is a violation`() {
        assertThrows<ContractViolation> { handler.handle(envelope(payload(occurredAt = "2026-10-29T10:00:00"))) }
        assertTrue(stores.isEmpty())
    }

    @Test
    fun `a target of 128 characters is read and of 129 is a violation`() {
        assertEquals(HandleOutcome.APPLIED, handler.handle(envelope(payload(target = "t".repeat(128)))))
        assertEquals("t".repeat(128), stores.single()[2])
        assertThrows<ContractViolation> { handler.handle(envelope(payload(target = "t".repeat(129)))) }
        assertEquals(1, stores.size)
    }

    @Test
    fun `an absent target is read as null and a non-string or empty target is a violation`() {
        handler.handle(envelope(payload(target = null)))
        assertEquals(null, stores.single()[2])
        assertThrows<ContractViolation> { handler.handle(envelope(payload(target = ""))) }
        assertThrows<ContractViolation> { handler.handle(envelope(payload(target = null, extra = ""","target":5"""))) }
    }

    @Test
    fun `a missing, blank or non-string report id or unlock reference is a violation`() {
        val bad =
            listOf(
                """{"unlockRef":"BG-1","action":"VIEW","occurredAt":"2026-10-29T10:00:00Z"}""",
                """{"reportId":" ","unlockRef":"BG-1","action":"VIEW","occurredAt":"2026-10-29T10:00:00Z"}""",
                """{"reportId":7,"unlockRef":"BG-1","action":"VIEW","occurredAt":"2026-10-29T10:00:00Z"}""",
                """{"reportId":"R1","action":"VIEW","occurredAt":"2026-10-29T10:00:00Z"}""",
                """{"reportId":"R1","unlockRef":"${"u".repeat(65)}","action":"VIEW",""" +
                    """"occurredAt":"2026-10-29T10:00:00Z"}""",
                """{"reportId":"R1","unlockRef":"BG-1","action":3,"occurredAt":"2026-10-29T10:00:00Z"}""",
                """{"reportId":"R1","unlockRef":"BG-1","action":"VIEW"}""",
            )
        bad.forEach { assertThrows<ContractViolation> { handler.handle(envelope(it)) } }
        assertTrue(stores.isEmpty())
    }

    @Test
    fun `a refused action is ignored`() {
        assertEquals(HandleOutcome.IGNORED, handler.handle(envelope(payload(action = "SHUTDOWN"))))
        assertTrue(stores.isEmpty())
    }
}
