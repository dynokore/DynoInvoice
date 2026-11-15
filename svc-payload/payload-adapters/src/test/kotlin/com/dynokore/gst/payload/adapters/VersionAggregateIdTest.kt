package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.application.version.RecordVersionFacts
import com.dynokore.gst.payload.domain.version.VersionFacts
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The aggregateId of a CanonicalVersionCreated event stored with the version facts, real database (INV-AC-22861). */
class VersionAggregateIdTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val d = UUID.randomUUID()
    private val e = UUID.randomUUID()
    private val f = UUID.randomUUID()

    private fun <T> bound(
        tenant: UUID,
        block: (JooqVersionFactsStore) -> T,
    ): T =
        PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                block(JooqVersionFactsStore(DSL.using(c, SQLDialect.POSTGRES)) { tenant })
            } finally {
                c.commit()
            }
        }

    private fun handle(
        tenant: UUID,
        doc: UUID,
        member: String?,
    ) = bound(tenant) { store ->
        val data =
            """{"docUuid":"$doc","version":1,${member?.let { "\"aggregateId\":$it," } ?: ""}""" +
                """"canonicalDocument":{"currency":{"value":"SGD"},"totals":{"value":{"totalIncludingTax":"10.00"}}}}"""
        CanonicalVersionCreatedHandler(RecordVersionFacts(store)).handle(envelope(tenant, doc, data))
    }

    private fun envelope(
        tenant: UUID,
        doc: UUID,
        data: String,
    ) = Envelope(
        id = EventId("ev-${UUID.randomUUID()}"),
        source = "/gst/canonical-svc",
        type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
        subject = doc.toString(),
        time = Instant.parse("2026-11-07T10:00:00Z"),
        tenant = TenantId(tenant),
        entity = EntityRef("E-1"),
        docUuid = DocUuid(doc),
        transactionDate = LocalDate.of(2026, 1, 15),
        docSequence = 1,
        instanceId = "canonical-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun find(
        tenant: UUID,
        doc: UUID,
    ) = bound(tenant) { it.find(doc, 1) }

    @Test
    fun `INV-AC-22861 D keeps the first aggregateId after a second event and E has none`() {
        handle(t1, d, "\"AGG-2026-0001\"")
        handle(t1, d, "\"AGG-2026-0002\"")
        handle(t1, e, null)
        find(t1, d) shouldBe VersionFacts("E-1", BigDecimal("10.00"), "AGG-2026-0001")
        find(t1, e)?.aggregateId shouldBe null
        find(t1, e) shouldBe VersionFacts("E-1", BigDecimal("10.00"))
    }

    @Test
    fun `INV-AC-22861 a member that is not a non-empty string is a ContractViolation and stores nothing`() {
        listOf("12", "\"\"", "null", "true", "{}").forEach { member ->
            shouldThrow<ContractViolation> { handle(t1, f, member) }
            find(t1, f) shouldBe null
        }
    }

    @Test
    fun `INV-AC-22861 find does not return the aggregateId of another tenant's row`() {
        handle(t1, d, "\"AGG-2026-0001\"")
        find(t2, d) shouldBe null
        find(t1, d)?.aggregateId shouldBe "AGG-2026-0001"
    }

    @Test
    fun `INV-AC-22861 the tenant predicate of find hides the aggregateId when the session sees another tenant's row`() {
        handle(t1, d, "\"AGG-2026-0001\"")
        // the session is bound to t1 (row security lets t1's row through) while the store asks for t2: only the predicate refuses
        val result =
            PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                SignedBindings.bind(c, t1)
                try {
                    JooqVersionFactsStore(DSL.using(c, SQLDialect.POSTGRES)) { t2 }.find(d, 1)
                } finally {
                    c.commit()
                }
            }
        result shouldBe null
    }

    @Test
    fun `INV-AC-22861 VersionFacts refuses a blank aggregateId`() {
        shouldThrow<IllegalArgumentException> { VersionFacts("E-1", null, " ") }
        VersionFacts("E-1", null).aggregateId shouldBe null
    }
}
