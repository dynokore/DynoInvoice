package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.domain.revocation.KeyUse
import com.dynokore.gst.access.domain.revocation.VerificationKey
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * The test of the channel `accessOperatorSessionKeysChanged` (INV-T-4154, INV-Q-16473 A): the payload this service
 * writes validates against the AsyncAPI schema of its channel, and every item carries its `use`.
 */
class OperatorSessionKeysChangedPayloadTest {
    private val mapper = JsonMapper.builder().build()
    private val captured = CapturedEvents()
    private val outbox = OperatorSessionKeysChangedOutbox(captured, mapper)
    private val schemaPath = "gst/contracts/service-runtime/schemas/operator-session-keys-changed-payload.schema.json"
    private val schema =
        checkNotNull(javaClass.classLoader.getResourceAsStream(schemaPath)).use {
            SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(it.readBytes().toString(Charsets.UTF_8))
        }

    private fun key(
        kid: String,
        use: KeyUse,
    ) = VerificationKey(
        kid,
        "ES256",
        "TUZrd0V3WUhLb1pJemowQ0FRWUlLb1pJemowREFRY0RRZ0FF",
        Instant.parse("2026-11-02T00:00:00Z"),
        Instant.parse("2026-11-02T02:15:00Z"),
        use,
    )

    @Test
    fun `the payload of an operator key and a machine key validates against the channel schema`() {
        val payload =
            outbox.payload(
                listOf(
                    key("0123456789abcdef", KeyUse.OPERATOR_SESSION),
                    key("fedcba9876543210", KeyUse.MACHINE_ACCESS),
                ),
            )
        assertTrue(schema.validate(mapper.readTree(payload.toString())).isEmpty())
        assertEquals(
            listOf("OPERATOR_SESSION", "MACHINE_ACCESS"),
            payload["keys"].toList().map { it["use"].asString() },
        )
    }

    @Test
    fun `an item without use does not appear and the kid ordering is stable`() {
        val payload = outbox.payload(listOf(key("b", KeyUse.OPERATOR_SESSION), key("a", KeyUse.OPERATOR_SESSION)))
        assertEquals(listOf("a", "b"), payload["keys"].toList().map { it["kid"].asString() })
        assertTrue(payload["keys"].toList().all { it.has("use") })
    }

    @Test
    fun `the event type and the channel are the contract names compared by equality`() {
        assertEquals(
            "com.dynokore.gst.platform.OperatorSessionKeysChanged.v1",
            OperatorSessionKeysChangedOutbox.EVENT_TYPE,
        )
        assertEquals("accessOperatorSessionKeysChanged", OperatorSessionKeysChangedOutbox.CHANNEL)
    }

    @Test
    fun `no key and more than four keys are refused`() {
        assertThrows(IllegalStateException::class.java) { outbox.payload(emptyList()) }
        val five = (1..5).map { key("k$it", KeyUse.OPERATOR_SESSION) }
        assertThrows(IllegalStateException::class.java) { outbox.payload(five) }
    }

    @Test
    fun `the record is a platform event under the platform tenant and entity with the constant aggregate key`() {
        outbox.publish(listOf(key("0123456789abcdef", KeyUse.OPERATOR_SESSION)))
        val record = captured.appended.single()
        assertEquals("com.dynokore.gst.platform.OperatorSessionKeysChanged.v1", record.event.type)
        assertEquals(PlatformEventCatalogue.OPERATOR_SESSION_KEYS_CHANGED.eventType, record.event.type)
        assertEquals("gst.platform.operator-session-keys-changed.v1", OperatorSessionKeysChangedOutbox.TOPIC)
        assertEquals(PlatformEventCatalogue.OPERATOR_SESSION_KEYS_CHANGED.topic, OperatorSessionKeysChangedOutbox.TOPIC)
        assertEquals(TenantId.PLATFORM, record.context.tenant)
        assertEquals(EntityRef.PLATFORM, record.context.entity)
        assertEquals("operator-session-keys", record.context.aggregateKey)
        val wire =
            com.dynokore.platform.outboxandqueue
                .EnvelopeJson()
                .write(record.envelope)
        assertFalse(wire.contains("tenantid"))
        assertFalse(wire.contains("entityref"))
    }

    @Test
    fun `look-alike types differ and a use of operator_session fails the schema`() {
        listOf(
            "com.dynokore.gst.access.OperatorSessionKeysChanged.v1",
            "com.dynokore.gst.platform.OperatorSessionKeyChanged.v1",
        ).forEach { assertTrue(it != OperatorSessionKeysChangedOutbox.EVENT_TYPE, it) }
        val bad =
            """{"keys":[{"kid":"a","use":"operator_session","alg":"ES256","publicKey":"QQ==",""" +
                """"validFrom":"2026-11-02T00:00:00Z","validUntil":"2026-11-02T02:15:00Z"}]}"""
        assertTrue(schema.validate(bad, com.networknt.schema.InputFormat.JSON).isNotEmpty())
    }

    @Test
    fun `INV-AC-9147 probe a machine key item that lost its use reads as an operator key, so none is ever written so`() {
        val payload = outbox.payload(listOf(key("fedcba9876543210", KeyUse.MACHINE_ACCESS)))
        val item = payload["keys"][0] as tools.jackson.databind.node.ObjectNode
        assertTrue(item.has("use"))
        // the channel schema leaves `use` optional, an item without it being an operator-session key: the writer must set it
        val stripped = item.deepCopy().also { it.remove("use") }
        assertTrue(schema.validate(mapper.readTree("""{"keys":[$stripped]}""")).isEmpty())
        assertFalse(stripped.has("use"))
        assertEquals("MACHINE_ACCESS", item["use"].asString())
    }

    @Test
    fun `INV-AC-9147 probe a machine key item is written with use MACHINE_ACCESS and never with OPERATOR_SESSION`() {
        val payload = outbox.payload(listOf(key("fedcba9876543210", KeyUse.MACHINE_ACCESS)))
        assertEquals("MACHINE_ACCESS", payload["keys"][0]["use"].asString())
    }
}
