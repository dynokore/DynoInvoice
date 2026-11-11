package com.dynokore.gst.submission.application.extension

import com.dynokore.gst.submission.application.register.RegisterDueDate
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The PeriodExtended handler with fake ports (INV-AC-20595). */
class PeriodExtendedHandlerTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000020596"))
    private val doc1 = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    private val doc2 = "4fa85f64-5717-4562-b3fc-2c963f66afa7"
    private val doc3 = "5fa85f64-5717-4562-b3fc-2c963f66afa8"
    private val apr30 = LocalDate.of(2026, 4, 30)
    private val may14 = LocalDate.of(2026, 5, 14)

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private class Store : DueDateExtensions {
        val rows = mutableListOf<DueDateExtension>()

        override fun insert(
            tenant: TenantId,
            row: DueDateExtension,
        ): Boolean {
            if (rows.any { it.sourceEventId == row.sourceEventId }) return false
            rows += row
            return true
        }

        override fun forPeriod(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
        ): List<DueDateExtension> = rows.filter { it.entityRef == entityRef && it.periodRef == periodRef }
    }

    private fun scope(kind: ExtensionScopeKind = ExtensionScopeKind.PERIOD) = PeriodExtendedScope(kind)

    private fun data(
        target: ExtensionTarget = ExtensionTarget.SUBMISSION_DUE_DATE,
        filing: LocalDate? = null,
        submission: LocalDate? = may14,
        scope: PeriodExtendedScope = scope(),
        period: String = "P",
    ) = PeriodExtendedData("E", period, target, filing, submission, scope, "IRAS-1")

    private fun envelope(id: String) =
        Envelope(
            id = EventId(id),
            source = "/gst/tenancy-svc",
            type = "com.dynokore.gst.tenancy.PeriodExtended.v1",
            subject = "E",
            time = Instant.parse("2026-10-19T08:00:00Z"),
            tenant = tenant,
            entity = EntityRef("E"),
            docUuid = DocUuid(UUID.fromString(doc1)),
            transactionDate = LocalDate.parse("2026-03-20"),
            docSequence = 1,
            instanceId = "tenancy-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    private val entries =
        listOf(
            RegisterDueDate(doc1, apr30),
            RegisterDueDate(doc2, LocalDate.of(2026, 5, 31)),
            RegisterDueDate(doc3, LocalDate.of(2026, 3, 31)),
        )

    private fun handle(
        store: Store,
        data: PeriodExtendedData,
        id: String = UUID.randomUUID().toString(),
        register: List<RegisterDueDate> = entries,
    ) = PeriodExtendedHandler(tenants, { data }, { _, e, p -> if (e == "E" && p == "P") register else emptyList() }, store)
        .handle(envelope(id))

    @Test
    fun `INV-AC-20595 a submission extension of the period stores the extended and the original date`() {
        val s = Store()
        handle(s, data(), register = listOf(RegisterDueDate(doc1, apr30))) shouldBe HandleOutcome.APPLIED
        val r = s.rows.single()
        r.originalDueDate shouldBe apr30
        r.extendedDueDate shouldBe may14
        r.scopeKind shouldBe ExtensionScopeKind.PERIOD
        r.scopeRef shouldBe null
        r.extendedFilingDueDate shouldBe null
        r.irasReference shouldBe "IRAS-1"
    }

    @Test
    fun `INV-AC-20595 a re-delivery stores nothing and is IGNORED`() {
        val s = Store()
        handle(s, data(), id = "ev-1")
        handle(s, data(), id = "ev-1") shouldBe HandleOutcome.IGNORED
        s.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-20595 the original date of a PERIOD scope is the earliest register due date`() {
        val s = Store()
        handle(s, data())
        s.rows.single().originalDueDate shouldBe LocalDate.of(2026, 3, 31)
    }

    @Test
    fun `INV-AC-20595 target BOTH stores both dates`() {
        val s = Store()
        handle(s, data(ExtensionTarget.BOTH, filing = LocalDate.of(2026, 5, 7)))
        val r = s.rows.single()
        r.extendedDueDate shouldBe may14
        r.extendedFilingDueDate shouldBe LocalDate.of(2026, 5, 7)
    }

    @Test
    fun `INV-AC-20595 target FILING_DUE_DATE stores the filing date as the extended date`() {
        val s = Store()
        handle(s, data(ExtensionTarget.FILING_DUE_DATE, filing = may14, submission = null))
        s.rows.single().extendedDueDate shouldBe may14
        s.rows.single().extendedFilingDueDate shouldBe null
    }

    @Test
    fun `INV-AC-20595 a document set stores sorted lower-case refs and the earliest date of its documents only`() {
        val s = Store()
        val sc = PeriodExtendedScope(ExtensionScopeKind.DOCUMENT_SET, documentRefs = listOf(doc2.uppercase(), doc1))
        handle(s, data(scope = sc))
        val r = s.rows.single()
        r.scopeKind shouldBe ExtensionScopeKind.DOCUMENT_SET
        r.scopeRef shouldBe listOf(doc1, doc2).sorted().joinToString(",")
        r.originalDueDate shouldBe apr30
    }

    @Test
    fun `INV-AC-20595 a date range stores its interval and a null original date`() {
        val s = Store()
        val sc =
            PeriodExtendedScope(
                ExtensionScopeKind.DATE_RANGE,
                dateFrom = LocalDate.of(2026, 4, 1),
                dateTo = LocalDate.of(2026, 4, 3),
            )
        handle(s, data(scope = sc))
        val r = s.rows.single()
        r.scopeKind shouldBe ExtensionScopeKind.DATE_RANGE
        r.scopeRef shouldBe "2026-04-01/2026-04-03"
        r.originalDueDate shouldBe null
    }

    @Test
    fun `INV-AC-20595 a malformed event is a ContractViolation and writes nothing`() {
        val s = Store()
        val bad =
            PeriodExtendedHandler(
                tenants,
                { throw ContractViolation("PeriodExtended data member irasReference is missing or not a non-empty string") },
                { _, _, _ -> entries },
                s,
            )
        shouldThrow<ContractViolation> { bad.handle(envelope("ev-x")) }
        shouldThrow<ContractViolation> { handle(s, data(submission = null)) }
            .message!! shouldNotContain "2026"
        shouldThrow<ContractViolation> {
            handle(s, data(ExtensionTarget.BOTH, filing = null))
        }
        s.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-20595 an event for a period without register entries stores a null original date`() {
        val s = Store()
        handle(s, data(period = "OTHER")) shouldBe HandleOutcome.APPLIED
        s.rows.single().originalDueDate shouldBe null
    }
}
