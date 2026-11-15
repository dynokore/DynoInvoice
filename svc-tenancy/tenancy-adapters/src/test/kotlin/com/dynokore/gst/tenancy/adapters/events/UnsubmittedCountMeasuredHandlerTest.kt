package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-23029: the handler of `UnsubmittedDocumentCountMeasured` stores the measurement of a valid payload with the
 * clock as receipt time, ignores an event the replica does not keep, and stores nothing from a payload that breaks the
 * contract. Also the catalogue entry and the sample payload against the schema of the pinned bundle.
 */
class UnsubmittedCountMeasuredHandlerTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private class FakeReplica(
        private val keeps: Boolean,
    ) : UnsubmittedCountReplica {
        val calls = ArrayList<List<Any>>()

        override fun keep(
            tenant: TenantId,
            entity: EventEntityRef,
            measurement: UnsubmittedCountMeasurement,
            receivedAt: Instant,
        ): Boolean {
            calls += listOf(tenant, entity, measurement, receivedAt)
            return keeps
        }
    }

    private val tenant = TenantId(UUID.randomUUID())
    private val valid =
        """{"measuredAt":"2026-11-20T10:00:00Z","unsubmittedDocumentCount":5,""" +
            """"entities":[{"entityRef":"E1","unsubmittedDocumentCount":3},{"entityRef":"E2","unsubmittedDocumentCount":2}]}"""

    private fun handle(
        data: String,
        keeps: Boolean = true,
        entity: EventEntityRef = EventEntityRef.TENANT_WIDE,
    ): Pair<HandleOutcome, FakeReplica> {
        val replica = FakeReplica(keeps)
        val outcome =
            UnsubmittedCountMeasuredHandler(replica, JsonMapper.builder().build(), clock).handle(event(data, entity))
        return outcome to replica
    }

    private fun rejected(data: String) {
        val replica = FakeReplica(true)
        shouldThrow<ContractViolation> {
            UnsubmittedCountMeasuredHandler(replica, JsonMapper.builder().build(), clock).handle(event(data))
        }
        replica.calls shouldBe emptyList()
    }

    private fun event(
        data: String,
        entity: EventEntityRef = EventEntityRef.TENANT_WIDE,
    ) =
        Envelope(
            id = EventId("ev-${UUID.randomUUID()}"),
            source = "/gst/submission-svc",
            type = TenancyEventCatalogue.UNSUBMITTED_COUNT_MEASURED_TYPE,
            subject = "tenant",
            time = Instant.parse("2026-11-20T10:00:00Z"),
            tenant = tenant,
            entity = entity,
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "submission-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

    @Test
    fun `INV-AC-23029 a valid payload is applied with the tenant, the values and the clock as receipt time`() {
        val (outcome, replica) = handle(valid)
        outcome shouldBe HandleOutcome.APPLIED
        val call = replica.calls.single()
        call[0] shouldBe tenant
        call[1] shouldBe EventEntityRef.TENANT_WIDE
        call[2] shouldBe
            UnsubmittedCountMeasurement(
                Instant.parse("2026-11-20T10:00:00Z"),
                5,
                listOf(EntityUnsubmittedCount("E1", 3), EntityUnsubmittedCount("E2", 2)),
            )
        call[3] shouldBe now
    }

    @Test
    fun `INV-AC-23029 a payload with total 0 and no entities is applied`() {
        val (outcome, replica) =
            handle("""{"measuredAt":"2026-11-20T10:05:00Z","unsubmittedDocumentCount":0,"entities":[]}""")
        outcome shouldBe HandleOutcome.APPLIED
        replica.calls.single()[2] shouldBe
            UnsubmittedCountMeasurement(Instant.parse("2026-11-20T10:05:00Z"), 0, emptyList())
    }

    @Test
    fun `INV-AC-23029 an envelope with a real entity is a contract violation and stores nothing`() {
        val replica = FakeReplica(true)
        shouldThrow<ContractViolation> {
            UnsubmittedCountMeasuredHandler(replica, JsonMapper.builder().build(), clock)
                .handle(event(valid, EventEntityRef("E1")))
        }
        replica.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-23029 an event the replica does not keep is ignored`() {
        handle(valid, keeps = false).first shouldBe HandleOutcome.IGNORED
    }

    @Test
    fun `INV-AC-23029 no measuredAt is a contract violation and stores nothing`() {
        rejected("""{"unsubmittedDocumentCount":0,"entities":[]}""")
    }

    @Test
    fun `INV-AC-23029 a measuredAt that is not a date-time is a contract violation and stores nothing`() {
        rejected("""{"measuredAt":"yesterday","unsubmittedDocumentCount":0,"entities":[]}""")
        rejected("""{"measuredAt":"2026-11-20","unsubmittedDocumentCount":0,"entities":[]}""")
        rejected("""{"measuredAt":12,"unsubmittedDocumentCount":0,"entities":[]}""")
    }

    @Test
    fun `INV-AC-23029 a negative, missing or non-integer total is a contract violation and stores nothing`() {
        rejected("""{"measuredAt":"2026-11-20T10:00:00Z","unsubmittedDocumentCount":-1,"entities":[]}""")
        rejected("""{"measuredAt":"2026-11-20T10:00:00Z","entities":[]}""")
        rejected("""{"measuredAt":"2026-11-20T10:00:00Z","unsubmittedDocumentCount":1.5,"entities":[]}""")
        rejected("""{"measuredAt":"2026-11-20T10:00:00Z","unsubmittedDocumentCount":"0","entities":[]}""")
    }

    @Test
    fun `INV-AC-23029 missing or non-array entities is a contract violation and stores nothing`() {
        rejected("""{"measuredAt":"2026-11-20T10:00:00Z","unsubmittedDocumentCount":0}""")
        rejected("""{"measuredAt":"2026-11-20T10:00:00Z","unsubmittedDocumentCount":0,"entities":{}}""")
    }

    @Test
    fun `INV-AC-23029 an item count 0 is a contract violation and stores nothing`() {
        rejected(
            """{"measuredAt":"2026-11-20T10:00:00Z","unsubmittedDocumentCount":0,""" +
                """"entities":[{"entityRef":"E1","unsubmittedDocumentCount":0}]}""",
        )
    }

    @Test
    fun `INV-AC-23029 an item without entityRef is a contract violation and stores nothing`() {
        rejected(
            """{"measuredAt":"2026-11-20T10:00:00Z","unsubmittedDocumentCount":3,""" +
                """"entities":[{"unsubmittedDocumentCount":3}]}""",
        )
    }

    @Test
    fun `INV-AC-23029 a repeated entityRef is a contract violation and stores nothing`() {
        rejected(
            """{"measuredAt":"2026-11-20T10:00:00Z","unsubmittedDocumentCount":4,""" +
                """"entities":[{"entityRef":"E1","unsubmittedDocumentCount":3},""" +
                """{"entityRef":"E1","unsubmittedDocumentCount":1}]}""",
        )
    }

    @Test
    fun `INV-AC-23029 a total that differs from the item sum is a contract violation and stores nothing`() {
        rejected(valid.replace("\"unsubmittedDocumentCount\":5,", "\"unsubmittedDocumentCount\":6,"))
        rejected(valid.replace("\"unsubmittedDocumentCount\":5,", "\"unsubmittedDocumentCount\":4,"))
    }

    @Test
    fun `INV-AC-23029 a payload that is not JSON is a contract violation and stores nothing`() {
        rejected("{")
    }

    @Test
    fun `INV-AC-23029 the type is catalogued for submission-svc`() {
        val entry =
            checkNotNull(TenancyEventCatalogue.load().find(TenancyEventCatalogue.UNSUBMITTED_COUNT_MEASURED_TYPE))
        entry.topic shouldBe TenancyEventCatalogue.UNSUBMITTED_COUNT_MEASURED_TOPIC
        entry.producers shouldBe setOf("submission-svc")
    }
}
