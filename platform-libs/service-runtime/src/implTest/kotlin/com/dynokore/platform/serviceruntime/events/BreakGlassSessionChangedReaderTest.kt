package com.dynokore.platform.serviceruntime.events

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionChanged
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/** The tolerant reader of `BreakGlassSessionChanged` (INV-Q-9119 A). */
class BreakGlassSessionChangedReaderTest {
    private val mapper = ObjectMapper()
    private val json = EnvelopeJson(mapper)
    private val reader = PlatformEventReader(mapper)
    private val tenant = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a"))
    private val expires = "2026-10-31T04:00:00Z"

    private fun payload(): ObjectNode =
        mapper.createObjectNode().apply {
            put("unlockRef", "BG-1")
            put("tenantRef", tenant.value.toString())
            put("status", "OPEN")
            put("expiresAt", expires)
            set("approvedBy", mapper.createArrayNode().add("identity:USR-004").add("identity:USR-005"))
        }

    private fun record(
        data: ObjectNode,
        type: String = PlatformEventTypes.BREAK_GLASS_SESSION_CHANGED,
    ): String =
        json.write(
            Envelope(
                EventId(UUID.randomUUID().toString()),
                "/gst/access-svc",
                type,
                "agg-1",
                Instant.parse("2026-10-31T00:00:00Z"),
                tenant,
                EntityRef("E-001"),
                null,
                null,
                null,
                "svc-1",
                CorrelationId("corr-1"),
                CausationId("cause-1"),
                mapper.writeValueAsString(data),
            ),
        )

    @Test
    fun `the type constant is the platform type`() {
        PlatformEventTypes.BREAK_GLASS_SESSION_CHANGED shouldBe "com.dynokore.gst.platform.BreakGlassSessionChanged.v1"
    }

    @Test
    fun `a payload with an extra property is read, the envelope overload agrees`() {
        val data = payload().put("addedInV1_3", "unknown")
        val expected =
            BreakGlassSessionChanged(
                "BG-1",
                tenant,
                "OPEN",
                Instant.parse(expires),
                listOf("identity:USR-004", "identity:USR-005"),
            )
        reader.breakGlassSessionChanged(record(data)) shouldBe expected
        reader.breakGlassSessionChanged(json.read(record(data))) shouldBe expected
    }

    @Test
    fun `an unknown status text is passed on`() {
        reader.breakGlassSessionChanged(record(payload().put("status", "SUSPENDED"))).status shouldBe "SUSPENDED"
    }

    @Test
    fun `another type, another major version or a look-alike namespace is a ContractViolation`() {
        listOf(
            "com.dynokore.gst.access.BreakGlassSessionChanged.v1",
            "com.dynokore.gst.platform.BreakGlassSessionChanged.v2",
            "com.dynokore.gst.platform.PolicyChanged.v1",
        ).forEach { type ->
            shouldThrow<ContractViolation> { reader.breakGlassSessionChanged(record(payload(), type)) }
            shouldThrow<ContractViolation> { reader.breakGlassSessionChanged(json.read(record(payload(), type))) }
        }
    }

    @Test
    fun `a missing required field is a ContractViolation`() {
        listOf("unlockRef", "tenantRef", "status", "expiresAt", "approvedBy").forEach { field ->
            shouldThrow<ContractViolation> {
                reader.breakGlassSessionChanged(
                    record(payload().also { it.remove(field) }),
                )
            }
        }
    }

    @Test
    fun `approvedBy that is a string or holds a non-string, and a bad instant or tenant, are a ContractViolation`() {
        shouldThrow<ContractViolation> {
            reader.breakGlassSessionChanged(record(payload().put("approvedBy", "identity:USR-004")))
        }
        shouldThrow<ContractViolation> {
            reader.breakGlassSessionChanged(record(payload().set("approvedBy", mapper.createArrayNode().add(7))))
        }
        shouldThrow<ContractViolation> {
            reader.breakGlassSessionChanged(
                record(payload().put("expiresAt", "2026-10-31")),
            )
        }
        shouldThrow<ContractViolation> { reader.breakGlassSessionChanged(record(payload().put("expiresAt", "later"))) }
        shouldThrow<ContractViolation> { reader.breakGlassSessionChanged(record(payload().put("tenantRef", "T1"))) }
    }
}
