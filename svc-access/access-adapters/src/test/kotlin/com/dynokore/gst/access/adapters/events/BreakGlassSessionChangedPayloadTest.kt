package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.BreakGlassSessionStatus
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** INV-AC-16956: the payload of the platform event BreakGlassSessionChanged (INV-Q-9119 A, INV-AC-9303). */
class BreakGlassSessionChangedPayloadTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val appended = mutableListOf<Pair<List<OutboundEvent>, EventContext>>()
    private val writer =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> {
                appended += events to context
                return emptyList()
            }
        }
    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor() = "user:test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val mapper = JsonMapper.builder().build()
    private val outbox = BreakGlassSessionChangedOutbox(writer, tenants, mapper)
    private val formatAssertions = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()
    private val schema =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "gst/contracts/service-runtime/schemas/break-glass-session-changed-payload.schema.json",
            ),
        ).use { it.readBytes().toString(Charsets.UTF_8) }
            .let {
                SchemaRegistry
                    .withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
                        builder.schemaRegistryConfig(formatAssertions)
                    }.getSchema(it)
            }
    private val expiresAt = Instant.parse("2026-11-05T05:00:00Z")

    private fun session(
        ref: String,
        approvers: List<String>,
        sealed: Boolean,
    ) = BreakGlassSession(
        ref = ref,
        tenantRef = "tenant-1",
        sessionHours = 4,
        unlockedAt = expiresAt.minusSeconds(4 * 3600L),
        expiresAt = expiresAt,
        approvedBy = approvers,
        sealed = sealed,
    )

    private fun valid(json: String) = schema.validate(json, InputFormat.JSON).isEmpty()

    private fun payload(
        status: String = "OPEN",
        expires: String = "2026-11-05T05:00:00Z",
        approvers: String = """"a","b"""",
    ) =
        """{"unlockRef":"BG-1","tenantRef":"tenant-1","status":"$status","expiresAt":"$expires","approvedBy":[$approvers]}"""

    @Test
    fun `INV-AC-16956 two approvers and OPEN append one valid event under the real tenant`() {
        outbox.changed(session("BG-1", listOf("u-2", "u-1"), false), BreakGlassSessionStatus.OPEN)
        val (events, context) = appended.single()
        val event = events.single()
        assertEquals("com.dynokore.gst.platform.BreakGlassSessionChanged.v1", event.type)
        assertTrue(valid(event.dataJson), event.dataJson)
        assertEquals(tenant, context.tenant)
        assertEquals(EntityRef.TENANT_WIDE, context.entity)
        val node = mapper.readTree(event.dataJson)
        assertEquals("BG-1", node.get("unlockRef").asString())
        assertEquals("tenant-1", node.get("tenantRef").asString())
        assertEquals("OPEN", node.get("status").asString())
        assertEquals("2026-11-05T05:00:00Z", node.get("expiresAt").asString())
        assertEquals(listOf("u-2", "u-1"), node.get("approvedBy").values().map { it.asString() })
    }

    @Test
    fun `INV-AC-16956 three approvers and SEALED append one valid event`() {
        outbox.changed(session("BG-2", listOf("c", "a", "b"), true), BreakGlassSessionStatus.SEALED)
        val event = appended.single().first.single()
        assertTrue(valid(event.dataJson), event.dataJson)
        val node = mapper.readTree(event.dataJson)
        assertEquals("BG-2", node.get("unlockRef").asString())
        assertEquals("SEALED", node.get("status").asString())
        assertEquals(listOf("c", "a", "b"), node.get("approvedBy").values().map { it.asString() })
        assertEquals(tenant, appended.single().second.tenant)
    }

    @Test
    fun `INV-AC-16956 the status UNLOCKED does not validate`() {
        assertTrue(valid(payload()))
        assertTrue(!valid(payload(status = "UNLOCKED")))
    }

    @Test
    fun `INV-AC-16956 expiresAt without a zone does not validate`() {
        assertTrue(!valid(payload(expires = "2026-11-05T05:00:00")))
    }

    @Test
    fun `INV-AC-16956 approvedBy of one item and of four items do not validate`() {
        assertTrue(valid(payload(approvers = """"a","b","c"""")))
        assertTrue(!valid(payload(approvers = """"a"""")))
        assertTrue(!valid(payload(approvers = """"a","b","c","d"""")))
    }

    @Test
    fun `INV-AC-16956 the domain type with the tenant-wide entity is refused by the envelope`() {
        val refused =
            assertThrows(IllegalArgumentException::class.java) {
                Envelope(
                    id = EventId(UUID.randomUUID().toString()),
                    source = "/gst/access-svc",
                    type = "com.dynokore.gst.access.BreakGlassSessionChanged.v1",
                    subject = "break-glass:BG-1",
                    time = Instant.now(),
                    tenant = tenant,
                    entity = EntityRef.TENANT_WIDE,
                    docUuid = null,
                    transactionDate = null,
                    docSequence = null,
                    instanceId = "access-svc-0",
                    correlation = CorrelationId("c"),
                    causation = CausationId("c"),
                    dataJson = payload(),
                )
            }
        assertTrue(refused.message!!.isNotBlank())
    }
}
