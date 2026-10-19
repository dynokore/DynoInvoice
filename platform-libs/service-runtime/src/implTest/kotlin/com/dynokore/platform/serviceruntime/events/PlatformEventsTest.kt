package com.dynokore.platform.serviceruntime.events

import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.contract.ContractValidator
import com.dynokore.platform.serviceruntime.events.ConnectionStatusBoard.ConnectionKey
import com.dynokore.platform.serviceruntime.jobs.MutableClock
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.PolicySource
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-2148 for the three platform events of INV-API-3306, INV-API-3308 and INV-API-3310 (INV-TS-3307,
 * INV-TS-3309, INV-TS-3311): each is read from its CloudEvents record carrying an envelope attribute and a payload
 * field its reader does not know, as a later minor version of the same major would (INV-REQ-1163), and is processed
 * exactly as the version the reader was built against. The payloads written and read match the pinned
 * platform-contracts schemas.
 */
class PlatformEventsTest {
    private val mapper = ObjectMapper()
    private val json = EnvelopeJson(mapper)
    private val reader = PlatformEventReader(mapper)
    private val contracts = ContractValidator()
    private val tenant = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a"))
    private val start = Instant.parse("2026-10-12T00:00:00Z")

    /** The CloudEvents JSON of an event of [type], with an unknown envelope attribute and an unknown payload field. */
    private fun record(
        type: String,
        data: ObjectNode,
        source: String = "/gst/payload-svc",
        minorIncrement: Boolean = true,
        stamp: Stamp = stamp(),
    ): String {
        if (minorIncrement) data.put("addedInV1_3", "a field this reader was not built with")
        val envelope =
            Envelope(
                EventId(UUID.randomUUID().toString()),
                source,
                type,
                "agg-1",
                stamp.time,
                stamp.tenant,
                stamp.entity,
                null,
                null,
                null,
                stamp.instance,
                CorrelationId("corr-1"),
                CausationId("cause-1"),
                mapper.writeValueAsString(data),
            )
        val node = mapper.readTree(json.write(envelope)) as ObjectNode
        if (minorIncrement) node.put("futureattribute", "an envelope extension this reader does not know")
        return mapper.writeValueAsString(node)
    }

    /** Whose event it is, where and when. */
    private data class Stamp(
        val tenant: TenantId,
        val entity: EntityRef,
        val instance: String,
        val time: Instant,
    )

    private fun stamp(
        tenant: TenantId = this.tenant,
        entity: EntityRef = EntityRef("E-001"),
        instance: String = "svc-1",
        time: Instant = start,
    ) = Stamp(tenant, entity, instance, time)

    private fun policyData(
        value: String,
        scope: String = "ENTITY:E-001",
    ) = mapper.createObjectNode().apply {
        put("scope", scope)
        put("vpId", "VP-13")
        set("value", mapper.createArrayNode().add(mapper.createObjectNode().put("key", value)))
        put("effectiveDate", "2026-10-01")
        put("source", "OVERRIDE")
    }

    @Test
    fun `INV-TS-3307 a policy replica applies a PolicyChanged with an unknown field and a minor increment normally`() {
        val replica = PolicyReplica(MutableClock(start))
        val topic = PlatformEventTypes.POLICY_CHANGED_TOPIC
        val data = policyData("STRICT")
        contracts.schemaErrors(POLICY_SCHEMA, data).shouldBeEmpty()
        val withUnknown = record(PlatformEventTypes.POLICY_CHANGED, data, source = "/gst/rules-svc")
        val plain =
            record(
                PlatformEventTypes.POLICY_CHANGED,
                policyData("STRICT"),
                source = "/gst/rules-svc",
                minorIncrement = false,
            )

        val read = reader.policyChanged(topic, LogRecord(7, withUnknown))
        val asV1 = reader.policyChanged(topic, LogRecord(7, plain))
        read.scope.toString() shouldBe asV1.scope.toString()
        read.copy(scope = asV1.scope) shouldBe asV1
        replica.accept(read)

        val scope = PolicyScope.entity(tenant, "E-001")
        val held = replica.valueAt(scope, "VP-13", LocalDate.parse("2026-10-05"))
        held?.value shouldBe """[{"key":"STRICT"}]"""
        held?.source shouldBe PolicySource.OVERRIDE
    }

    @Test
    fun `PolicyChanged orders by event-log offset, a redelivered offset changes nothing and a later one wins`() {
        val replica = PolicyReplica(MutableClock(start))
        val topic = PlatformEventTypes.POLICY_CHANGED_TOPIC
        val scope = PolicyScope.entity(tenant, "E-001")
        val on = LocalDate.parse("2026-10-05")
        replica.accept(
            reader.policyChanged(topic, LogRecord(10, record(PlatformEventTypes.POLICY_CHANGED, policyData("LATER")))),
        )
        replica.accept(
            reader.policyChanged(topic, LogRecord(9, record(PlatformEventTypes.POLICY_CHANGED, policyData("EARLIER")))),
        )
        replica.valueAt(scope, "VP-13", on)?.value shouldBe """[{"key":"LATER"}]"""
        replica.accept(
            reader.policyChanged(topic, LogRecord(10, record(PlatformEventTypes.POLICY_CHANGED, policyData("LATER")))),
        )
        replica.valueAt(scope, "VP-13", on)?.value shouldBe """[{"key":"LATER"}]"""
        replica.accept(
            reader.policyChanged(topic, LogRecord(11, record(PlatformEventTypes.POLICY_CHANGED, policyData("NEWEST")))),
        )
        replica.valueAt(scope, "VP-13", on)?.value shouldBe """[{"key":"NEWEST"}]"""
    }

    @Test
    fun `an installation PolicyChanged arrives without tenantid and applies at the installation level`() {
        val replica = PolicyReplica(MutableClock(start))
        val data = policyData("STRICT", scope = "INSTALLATION")
        val text =
            record(
                PlatformEventTypes.POLICY_CHANGED,
                data,
                stamp = stamp(tenant = TenantId.PLATFORM, entity = EntityRef.PLATFORM),
            )
        mapper.readTree(text).has("tenantid") shouldBe false
        replica.accept(reader.policyChanged(PlatformEventTypes.POLICY_CHANGED_TOPIC, LogRecord(1, text)))
        replica.valueAt(PolicyScope.entity(tenant, "E-009"), "VP-13", LocalDate.parse("2026-10-05"))?.value shouldBe
            """[{"key":"STRICT"}]"""
    }

    @Test
    fun `INV-TS-3309 a consumer applies a ConnectionStatusChanged with an unknown field, keeps the worst state`() {
        val board = ConnectionStatusBoard()

        fun report(
            instance: String,
            state: String,
            at: Instant,
            minorIncrement: Boolean = true,
        ): ConnectionStatusReport {
            val data = mapper.createObjectNode().put("connection", "ap-primary").put("state", state)
            if (state != "up") data.putObject("lastFailure").put("at", at.toString()).put("reason", "TRN-004")
            contracts.schemaErrors(CONNECTION_SCHEMA, data).shouldBeEmpty()
            val text =
                record(
                    PlatformEventTypes.CONNECTION_STATUS_CHANGED,
                    data,
                    source = "/gst/transport-svc",
                    minorIncrement = minorIncrement,
                    stamp = stamp(instance = instance, time = at),
                )
            return reader.connectionStatusChanged(text)
        }

        fun apply(report: ConnectionStatusReport) =
            board.apply(ConnectionKey(report.service, report.connection), report.instance, report.state, report.time)
        val key = ConnectionKey("transport-svc", "ap-primary")

        val first = report("svc-1", "down", start)
        first.instance shouldBe "svc-1"
        first.lastFailure?.reason shouldBe "TRN-004"
        val plain = report("svc-1", "down", start, minorIncrement = false)
        first.copy(eventId = plain.eventId) shouldBe plain

        apply(first) shouldBe true
        apply(report("svc-2", "up", start.plusSeconds(5))) shouldBe true
        board.worst(key) shouldBe ReceivedConnectionState.DOWN
        apply(report("svc-1", "degraded", start.plusSeconds(10))) shouldBe true
        board.worst(key) shouldBe ReceivedConnectionState.DEGRADED
        // within one instance's stream the latest envelope time wins: an older report changes nothing
        apply(report("svc-1", "down", start.plusSeconds(1))) shouldBe false
        board.worst(key) shouldBe ReceivedConnectionState.DEGRADED
        apply(report("svc-1", "up", start.plusSeconds(20))) shouldBe true
        board.worst(key) shouldBe ReceivedConnectionState.UP
        board.instances(key) shouldBe
            mapOf("svc-1" to ReceivedConnectionState.UP, "svc-2" to ReceivedConnectionState.UP)
    }

    @Test
    fun `INV-Q-3718 a state outside up, degraded and down is shown as UNKNOWN, ranked between degraded and down`() {
        val data = mapper.createObjectNode().put("connection", "ap-primary").put("state", "maintenance")
        val read = reader.connectionStatusChanged(record(PlatformEventTypes.CONNECTION_STATUS_CHANGED, data))
        read.stateText shouldBe "maintenance"
        read.state shouldBe ReceivedConnectionState.UNKNOWN

        val board = ConnectionStatusBoard()
        val key = ConnectionKey(read.service, read.connection)
        board.apply(key, "svc-1", ReceivedConnectionState.DEGRADED, start) shouldBe true
        board.apply(key, "svc-2", read.state, start) shouldBe true
        // an unknown instance outranks degraded and up ...
        board.worst(key) shouldBe ReceivedConnectionState.UNKNOWN
        // ... but not down
        board.apply(key, "svc-1", ReceivedConnectionState.DOWN, start.plusSeconds(1)) shouldBe true
        board.worst(key) shouldBe ReceivedConnectionState.DOWN
        // and a contract state from the same instance replaces the unknown one
        board.apply(key, "svc-1", ReceivedConnectionState.UP, start.plusSeconds(2)) shouldBe true
        board.apply(key, "svc-2", ReceivedConnectionState.UP, start.plusSeconds(2)) shouldBe true
        board.worst(key) shouldBe ReceivedConnectionState.UP
        ReceivedConnectionState.entries.map { it.name } shouldBe listOf("UP", "DEGRADED", "UNKNOWN", "DOWN")
    }

    @Test
    fun `INV-TS-3311 an EvidenceArtefactRecorded with an unknown field and minor increment is one artefact`() {
        val artefact =
            EvidenceArtefact.of(
                EvidenceArtefactType.RENDITION,
                "rendition/doc-1/v2",
                "%PDF-1.7".toByteArray(),
            )
        val written = mutableListOf<Pair<OutboundEvent, EventContext>>()
        val recorder =
            OutboxEvidenceArtefactRecorder(
                object : OutboxWriter {
                    override fun append(
                        events: List<OutboundEvent>,
                        context: EventContext,
                    ): List<Envelope> {
                        events.forEach { written += it to context }
                        return emptyList()
                    }
                },
            )
        recorder.record(artefact, context())
        written shouldHaveSize 1
        written[0].first.type shouldBe PlatformEventTypes.EVIDENCE_ARTEFACT_RECORDED
        val data = mapper.readTree(written[0].first.dataJson) as ObjectNode
        contracts.schemaErrors(EVIDENCE_SCHEMA, data).shouldBeEmpty()

        val first =
            reader.evidenceArtefactRecorded(
                record(PlatformEventTypes.EVIDENCE_ARTEFACT_RECORDED, data.deepCopy()),
            )
        val again =
            reader.evidenceArtefactRecorded(
                record(PlatformEventTypes.EVIDENCE_ARTEFACT_RECORDED, data.deepCopy(), minorIncrement = false),
            )
        first.artefact shouldBe artefact
        // the same artefact twice is one (artefactType, reference, hash): evidence-svc records it once
        setOf(first.artefact, again.artefact) shouldHaveSize 1
        first.service shouldBe "payload-svc"
    }

    @Test
    fun `an artefact type added in a later minor version is kept as received`() {
        val data =
            mapper
                .createObjectNode()
                .put("artefactType", "SUPPLIER_STATEMENT")
                .put("reference", "stmt-1")
                .put("hash", EvidenceArtefact.sha256Hex("x".toByteArray()))
        val read = reader.evidenceArtefactRecorded(record(PlatformEventTypes.EVIDENCE_ARTEFACT_RECORDED, data))
        read.artefact.type shouldBe EvidenceArtefactType("SUPPLIER_STATEMENT")
        read.artefact.type.isKnown shouldBe false
    }

    @Test
    fun `the hash is the SHA-256 of the artefact bytes as 64 lowercase hex characters`() {
        EvidenceArtefact.sha256Hex("abc".toByteArray()) shouldBe
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertThrows<IllegalArgumentException> { EvidenceArtefact(EvidenceArtefactType.IMPORT, "r", "AB".repeat(32)) }
        assertThrows<IllegalArgumentException> { EvidenceArtefactType("rendition") }
    }

    @Test
    fun `another major version, another type or a missing required field is a contract violation`() {
        val data = policyData("STRICT")
        val text = record(PlatformEventTypes.POLICY_CHANGED, data)
        val v2 = text.replace("PolicyChanged.v1", "PolicyChanged.v2")
        assertThrows<ContractViolation> {
            reader.policyChanged(
                PlatformEventTypes.POLICY_CHANGED_TOPIC,
                LogRecord(1, v2),
            )
        }
        assertThrows<ContractViolation> { reader.connectionStatusChanged(text) }
        val missing =
            record(
                PlatformEventTypes.EVIDENCE_ARTEFACT_RECORDED,
                mapper.createObjectNode().put("artefactType", "IMPORT").put("reference", "r"),
            )
        assertThrows<ContractViolation> { reader.evidenceArtefactRecorded(missing) }
    }

    private fun context() =
        EventContext(
            tenant,
            EntityRef("E-001"),
            null,
            null,
            "rendition/doc-1",
            null,
            CorrelationId("corr-1"),
            CausationId("cause-1"),
        )

    companion object {
        private const val POLICY_SCHEMA = "service-runtime/schemas/policy-changed-payload.schema.json"
        private const val CONNECTION_SCHEMA = "service-runtime/schemas/connection-status-changed-payload.schema.json"
        private const val EVIDENCE_SCHEMA = "service-runtime/schemas/evidence-artefact-recorded-payload.schema.json"
    }
}
