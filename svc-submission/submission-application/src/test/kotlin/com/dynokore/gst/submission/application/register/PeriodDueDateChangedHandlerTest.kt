package com.dynokore.gst.submission.application.register

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
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

/** The PeriodDueDateChanged consumer with an in-memory port (INV-AC-20513). */
class PeriodDueDateChangedHandlerTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
    private val d1 = LocalDate.of(2026, 7, 31)
    private val d2 = LocalDate.of(2026, 8, 31)

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

    private class Memory : PeriodDueDates {
        val rows = mutableMapOf<Triple<TenantId, String, String>, Pair<LocalDate, Long>>()
        var writes = 0

        override fun upsert(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
            dueDate: LocalDate,
            dueDateVersion: Long,
        ): UpsertResult {
            val key = Triple(tenant, entityRef, periodRef)
            val old = rows[key]
            if (old != null && old.second >= dueDateVersion) return UpsertResult.UNCHANGED
            rows[key] = dueDate to dueDateVersion
            writes++
            return if (old == null) UpsertResult.INSERTED else UpsertResult.UPDATED
        }

        override fun find(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
        ): LocalDate? = rows[Triple(tenant, entityRef, periodRef)]?.first
    }

    private val store = Memory()
    private var next: () -> PeriodDueDateChangedData = { error("unset") }
    private val handler = PeriodDueDateChangedHandler(tenants, { next() }, store)

    private fun deliver(
        date: LocalDate,
        version: Long,
        period: String = "P",
    ): HandleOutcome {
        next = { PeriodDueDateChangedData("E", period, date, version) }
        return handler.handle(envelope())
    }

    private fun envelope() =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/tenancy-svc",
            type = "com.dynokore.gst.tenancy.PeriodDueDateChanged.v1",
            subject = "E",
            time = Instant.parse("2026-10-19T08:00:00Z"),
            tenant = tenant,
            entity = EntityRef("E"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "tenancy-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    @Test
    fun `INV-AC-20513 a valid event of version 1 stores newFilingDueDate`() {
        deliver(d1, 1) shouldBe HandleOutcome.APPLIED
        store.find(tenant, "E", "P") shouldBe d1
    }

    @Test
    fun `INV-AC-20513 a re-delivery is IGNORED`() {
        deliver(d1, 1)
        deliver(d1, 1) shouldBe HandleOutcome.IGNORED
        store.writes shouldBe 1
    }

    @Test
    fun `INV-AC-20513 version 2 changes the row and a later version 1 is IGNORED`() {
        deliver(d1, 1)
        deliver(d2, 2) shouldBe HandleOutcome.APPLIED
        deliver(d1, 1) shouldBe HandleOutcome.IGNORED
        store.find(tenant, "E", "P") shouldBe d2
    }

    @Test
    fun `INV-AC-20513 an equal version with another date changes nothing`() {
        deliver(d1, 3)
        deliver(d2, 3) shouldBe HandleOutcome.IGNORED
        store.find(tenant, "E", "P") shouldBe d1
    }

    @Test
    fun `INV-AC-20513 the version is kept per period`() {
        deliver(d2, 2)
        deliver(d1, 1, "P2") shouldBe HandleOutcome.APPLIED
        store.find(tenant, "E", "P2") shouldBe d1
    }

    @Test
    fun `INV-AC-20513 a malformed event is a ContractViolation that writes nothing and logs no value`() {
        val h = PeriodDueDateChangedHandler(tenants, { throw ContractViolation("member is missing") }, store)
        val e = shouldThrow<ContractViolation> { h.handle(envelope()) }
        (e.message ?: "") shouldNotContain "2026"
        store.writes shouldBe 0
    }
}
