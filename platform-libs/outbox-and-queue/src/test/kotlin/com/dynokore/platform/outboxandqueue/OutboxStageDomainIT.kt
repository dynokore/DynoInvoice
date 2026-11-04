package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * INV-AC-16722 (INV-Q-16720 A): `stage` builds the row of a domain event for a real tenant, unsequenced, and still
 * refuses a domain event under the platform tenant, a sequenced context, an uncatalogued type and a tenant-wide
 * entity; it writes no row. PostgreSQL 17 and 18.
 */
@Tag("integration")
class OutboxStageDomainIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-16722 a domain event is staged for the context tenant while another is bound`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val tenant = UUID.randomUUID()
            val context = realContext(tenant, "stage-${UUID.randomUUID()}")
            val event = OutboundEvent(TestCatalogue.PAYLOAD_READY, """{"ready":true}""")

            val staged = kit.inTx(UUID.randomUUID()) { kit.writer.stage(listOf(event), context) }.single()
            assertEquals(0, harness.rows("payload.outbox", "subject", context.aggregateKey), "stage wrote a row")
            assertEquals(TenantId(tenant), staged.envelope.tenant)
            assertEquals(PAYLOAD_READY_TOPIC, staged.topic)
            assertNull(staged.envelope.docSequence)

            val written = kit.inTx(tenant) { kit.writer.append(listOf(event), context) }.single()
            assertEquals(written, staged.envelope.copy(id = written.id))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-16722 a domain event under the platform tenant is refused`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val context =
                realContext(UUID.randomUUID(), "stage-platform").copy(
                    tenant = TenantId.PLATFORM,
                    entity = EntityRef.PLATFORM,
                )
            val event = OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}")
            assertThrows(IllegalArgumentException::class.java) {
                kit.inPlatformTx { kit.writer.stage(listOf(event), context) }
            }
            assertEquals(0, harness.rows("payload.outbox", "subject", context.aggregateKey))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-16722 a Doc UUID or an aggregate version is refused`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val tenant = UUID.randomUUID()
            val event = OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}")
            assertThrows(IllegalArgumentException::class.java) {
                val document = kit.docContext(tenant, UUID.randomUUID())
                kit.inTx(tenant) { kit.writer.stage(listOf(event), document) }
            }
            val versioned = realContext(tenant, "stage-versioned").copy(aggregateVersion = 1)
            assertThrows(IllegalArgumentException::class.java) {
                kit.inTx(tenant) { kit.writer.stage(listOf(event), versioned) }
            }
            assertEquals(0, harness.rows("payload.outbox", "subject", versioned.aggregateKey))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-16722 an uncatalogued type and the tenant-wide entity are refused`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val tenant = UUID.randomUUID()
            val context = realContext(tenant, "stage-refused")
            assertThrows(EmissionNotPermitted::class.java) {
                kit.inTx(tenant) {
                    kit.writer.stage(listOf(OutboundEvent("com.dynokore.gst.payload.Unknown.v1", "{}")), context)
                }
            }
            val wide = context.copy(entity = EntityRef.TENANT_WIDE)
            assertThrows(EmissionNotPermitted::class.java) {
                kit.inTx(tenant) {
                    kit.writer.stage(listOf(OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}")), wide)
                }
            }
            assertEquals(0, harness.rows("payload.outbox", "subject", context.aggregateKey))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-16722 a batch with one uncatalogued event throws and stages nothing`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.payload
            val tenant = UUID.randomUUID()
            val context = realContext(tenant, "stage-batch")
            val batch =
                listOf(
                    OutboundEvent(TestCatalogue.PAYLOAD_READY, "{}"),
                    OutboundEvent("com.dynokore.gst.payload.Unknown.v1", "{}"),
                )
            assertThrows(EmissionNotPermitted::class.java) {
                kit.inTx(tenant) { kit.writer.stage(batch, context) }
            }
            assertEquals(0, harness.rows("payload.outbox", "subject", context.aggregateKey))
        }
    }

    private fun realContext(
        tenant: UUID,
        key: String,
    ) = EventContext(
        tenant = TenantId(tenant),
        entity = EntityRef("entity-1"),
        docUuid = null,
        transactionDate = null,
        aggregateKey = key,
        aggregateVersion = null,
        correlation = CorrelationId("corr-$key"),
        causation = CausationId("cmd-$key"),
    )

    private companion object {
        const val PAYLOAD_READY_TOPIC = "gst.payload.payload-ready.v1"
    }
}
