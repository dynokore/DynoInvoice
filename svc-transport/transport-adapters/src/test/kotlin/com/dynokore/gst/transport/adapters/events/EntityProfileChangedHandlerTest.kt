package com.dynokore.gst.transport.adapters.events

import com.dynokore.gst.transport.application.ApplyEntityProfileChanged
import com.dynokore.gst.transport.application.IssuanceReplica
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.IssuanceModeEntry
import com.dynokore.gst.transport.domain.IssuanceReplicaRow
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** The wire reading of `EntityProfileChanged` (issuanceMode entries and structureVersion only), over a map replica. */
class EntityProfileChangedHandlerTest {
    private val rows = HashMap<EntityRef, IssuanceReplicaRow>()
    private val replica =
        object : IssuanceReplica {
            override fun find(entity: EntityRef) = rows[entity]

            override fun replace(
                entity: EntityRef,
                row: IssuanceReplicaRow,
            ) {
                rows[entity] = row
            }
        }
    private val handler = EntityProfileChangedHandler(ApplyEntityProfileChanged(replica))
    private val tenant = UUID.randomUUID()
    private val entity = EntityRef("E-1")

    private fun event(
        data: String,
        id: String = "ev-${UUID.randomUUID()}",
    ) = Envelope(
        id = EventId(id),
        source = "/gst/tenancy",
        type = "com.dynokore.gst.tenancy.EntityProfileChanged.v1",
        subject = "E-1",
        time = Instant.parse("2026-11-01T00:00:00Z"),
        tenant = TenantId(tenant),
        entity = EventEntityRef("E-1"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "tenancy-svc-0",
        correlation = CorrelationId("c"),
        causation = CausationId("c"),
        dataJson = data,
    )

    private fun payload(
        version: Long,
        profile: String,
    ) = """{"entityRef":"E-1","changedAttributes":[],"effectiveDate":"2026-11-01",""" +
        """"node":{"kind":"ENTITY","name":"Acme","uen":"X","retired":false,""" +
        """"profile":[$profile],"structureVersion":$version}}"""

    private fun mode(
        value: String,
        date: String,
    ) = """{"attribute":"issuanceMode","value":"$value","effectiveDate":"$date"}"""

    @Test
    fun `INV-AC-9490 the issuanceMode entries and the structureVersion are stored and no other attribute is read`() {
        val other = """{"attribute":"tradingName","value":"Secret Co","effectiveDate":"2026-01-01"}"""
        handler.handle(
            event(payload(3, mode("OBSERVE", "2026-12-01") + "," + other + "," + mode("RELAY", "2026-01-01"))),
        ) shouldBe
            HandleOutcome.APPLIED
        rows.getValue(entity) shouldBe
            IssuanceReplicaRow(
                TenantRef(tenant.toString()),
                listOf(
                    IssuanceModeEntry("OBSERVE", LocalDate.parse("2026-12-01")),
                    IssuanceModeEntry("RELAY", LocalDate.parse("2026-01-01")),
                ),
                3,
            )
    }

    @Test
    fun `INV-AC-9490 a redelivery and an older event leave the row unchanged`() {
        val newer = payload(5, mode("OBSERVE", "2026-01-01"))
        handler.handle(event(newer)) shouldBe HandleOutcome.APPLIED
        val stored = rows.getValue(entity)
        handler.handle(event(newer)) shouldBe HandleOutcome.IGNORED
        handler.handle(event(payload(4, mode("RELAY", "2026-01-01")))) shouldBe HandleOutcome.IGNORED
        rows.getValue(entity) shouldBe stored
    }

    @Test
    fun `INV-AC-9490 an event without node or without an issuanceMode entry leaves the row unchanged`() {
        handler.handle(event(payload(1, mode("OBSERVE", "2026-01-01"))))
        val stored = rows.getValue(entity)
        handler.handle(event("""{"entityRef":"E-1","changedAttributes":[],"effectiveDate":"2026-11-01"}""")) shouldBe
            HandleOutcome.IGNORED
        handler.handle(
            event(payload(9, """{"attribute":"tradingName","value":"x","effectiveDate":"2026-01-01"}""")),
        ) shouldBe
            HandleOutcome.IGNORED
        rows.getValue(entity) shouldBe stored
    }

    @Test
    fun `INV-AC-9490 a payload that breaks the contract is a ContractViolation, not a state change`() {
        for (bad in listOf(
            "not json",
            """{"node":{}}""",
            """{"entityRef":"E-1","node":{"profile":[]}}""",
            """{"entityRef":"E-1","node":[]}""",
        )) {
            shouldThrow<ContractViolation> { handler.handle(event(bad)) }
        }
        shouldThrow<ContractViolation> { handler.handle(event(payload(1, mode("OBSERVE", "01/02/2026")))) }
        rows.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-9490 an entity held under another tenant is not overwritten`() {
        val foreign =
            IssuanceReplicaRow(
                TenantRef(UUID.randomUUID().toString()),
                listOf(IssuanceModeEntry("OBSERVE", LocalDate.parse("2026-01-01"))),
                1,
            )
        rows[entity] = foreign
        handler.handle(event(payload(7, mode("RELAY", "2026-01-01")))) shouldBe HandleOutcome.IGNORED
        rows.getValue(entity) shouldBe foreign
    }
}
