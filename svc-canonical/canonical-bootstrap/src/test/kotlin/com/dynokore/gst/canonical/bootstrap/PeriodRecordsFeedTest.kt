package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.PeriodRecordsReader
import com.dynokore.gst.canonical.adapters.persistence.PeriodSpanSet
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-21431: the PeriodRecordsChanged consumer over a fake period span store. */
class PeriodRecordsFeedTest {
    private val t1 = TenantId(UUID.fromString("11111111-1111-1111-1111-111111111111"))

    private class FakeStore : PeriodSpanSink {
        val sets = mutableListOf<PeriodSpanSet>()

        override fun apply(set: PeriodSpanSet): Boolean {
            sets += set
            return true
        }
    }

    private fun data(
        entityRef: String? = "\"ENT-1\"",
        version: Int = 3,
        periods: String? = null,
        firstEnd: String = "2026-03-31",
        secondRef: String? = "\"2026-Q2\"",
    ): String {
        val second =
            buildString {
                append("{")
                if (secondRef != null) append("\"periodRef\":$secondRef,")
                append("\"periodStart\":\"2026-04-01\",\"periodEnd\":\"2026-06-30\"}")
            }
        val list =
            periods
                ?: "[{\"periodRef\":\"2026-Q1\",\"periodStart\":\"2026-01-01\",\"periodEnd\":\"$firstEnd\"," +
                "\"filingDueDate\":\"2026-04-30\"},$second]"
        return buildString {
            append("{")
            if (entityRef != null) append("\"entityRef\":$entityRef,")
            append("\"version\":$version,\"periods\":$list}")
        }
    }

    private fun envelope(
        dataJson: String,
        tenant: TenantId = t1,
    ) = Envelope(
        id = EventId("evt-1"),
        source = "/gst/tenancy-svc",
        type = PeriodRecordsFeed.EVENT_TYPE,
        subject = "ENT-1",
        time = Instant.parse("2026-07-01T00:00:00Z"),
        tenant = tenant,
        entity = EntityRef("ENT-1"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "i-1",
        correlation = CorrelationId("c-1"),
        causation = CausationId("k-1"),
        dataJson = dataJson,
    )

    private fun handle(
        dataJson: String,
        tenant: TenantId = t1,
    ): Pair<FakeStore, Result<HandleOutcome>> {
        val store = FakeStore()
        val handler = PeriodRecordsHandler(PeriodRecordsReader(), store)
        return store to runCatching { handler.handle(envelope(dataJson, tenant)) }
    }

    private fun refused(
        dataJson: String,
        tenant: TenantId = t1,
    ): FakeStore {
        val (store, result) = handle(dataJson, tenant)
        result.exceptionOrNull() as? ContractViolation ?: error("not refused: $result")
        store.sets shouldBe emptyList()
        return store
    }

    @Test
    fun `INV-AC-21431 P1 valid event applies exactly one complete set`() {
        val (store, result) = handle(data())
        result.getOrThrow() shouldBe HandleOutcome.APPLIED
        store.sets shouldBe
            listOf(
                PeriodSpanSet(
                    t1.value,
                    "ENT-1",
                    3,
                    listOf(
                        PeriodSpan("2026-Q1", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31")),
                        PeriodSpan("2026-Q2", LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30")),
                    ),
                ),
            )
    }

    @Test
    fun `INV-AC-21431 P2 version 0 is a contract violation`() {
        refused(data(version = 0))
    }

    @Test
    fun `INV-AC-21431 P3 empty periods is a contract violation`() {
        refused(data(periods = "[]"))
    }

    @Test
    fun `INV-AC-21431 P4 period end before start is a contract violation`() {
        refused(data(firstEnd = "2025-12-31"))
    }

    @Test
    fun `INV-AC-21431 P5 missing entityRef is a contract violation`() {
        refused(data(entityRef = null))
    }

    @Test
    fun `INV-AC-21431 P6 envelope without tenantid is a contract violation`() {
        // An Envelope cannot carry a domain event without a tenant (FR-K.3); the platform tenant models a missing one.
        assertThrows<ContractViolation> { PeriodRecordsReader().read(data(), TenantId.PLATFORM) }
    }

    @Test
    fun `INV-AC-21431 P7 period without periodRef is a contract violation`() {
        refused(data(secondRef = null))
    }

    @Test
    fun `INV-AC-21431 P1 tenant comes from the envelope not from the data`() {
        val other = "22222222-2222-2222-2222-222222222222"
        val spoofed = data().replace("\"entityRef\"", "\"tenantId\":\"$other\",\"entityRef\"")
        val (store, _) = handle(spoofed)
        store.sets.single().tenantId shouldBe t1.value
    }

    @Test
    fun `INV-AC-21431 subscription lists exactly the topic and the event type`() {
        PeriodRecordsFeed.TOPIC shouldBe "gst.tenancy.period-records-changed.v1"
        PeriodRecordsFeed.EVENT_TYPE shouldBe "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1"
    }
}
