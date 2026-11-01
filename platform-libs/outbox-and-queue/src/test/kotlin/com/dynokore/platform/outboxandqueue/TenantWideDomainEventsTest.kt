package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The envelope accepts the tenant-wide entity for the one domain event AccessPointRouteChanged and for no other
 * domain event (INV-Q-15474 A, INV-REQ-1162, INV-AC-15489): exact type string, still under a real tenant.
 */
class TenantWideDomainEventsTest {
    private val json = EnvelopeJson()
    private val tenant = TenantId(UUID.randomUUID())

    private fun envelope(
        type: String,
        tenant: TenantId = this.tenant,
        entity: EntityRef = EntityRef.TENANT_WIDE,
    ) = Envelope(
        id = EventId(UuidV7().next(Instant.parse("2026-10-01T00:00:00Z")).toString()),
        source = "/gst/" + type.removePrefix("com.dynokore.gst.").substringBefore('.'),
        type = type,
        subject = "ap-connection-1",
        time = Instant.parse("2026-10-01T00:00:00Z"),
        tenant = tenant,
        entity = entity,
        docUuid = null,
        transactionDate = LocalDate.of(2026, 9, 30),
        docSequence = null,
        instanceId = "transport-svc-1",
        correlation = CorrelationId("c"),
        causation = CausationId("k"),
        dataJson = """{"version":1}""",
    )

    @Test
    fun `INV-AC-15489 the allowed type with a real tenant and the tenant-wide entity round-trips without entityref`() {
        val written = envelope(ALLOWED)
        val text = json.write(written)
        assertFalse(text.contains("entityref") || text.contains("00000000-0000"), text)
        assertEquals(written, json.read(text))
        assertEquals(EntityRef.TENANT_WIDE, json.read(text).entity)
        assertTrue(Envelope.allowsTenantWide(ALLOWED))
        assertFalse(Envelope.isPlatformType(ALLOWED))
    }

    @Test
    fun `INV-AC-15489 the allowed type is refused under the platform tenant`() {
        assertThrows(IllegalArgumentException::class.java) { envelope(ALLOWED, tenant = TenantId.PLATFORM) }
        assertThrows(IllegalArgumentException::class.java) {
            envelope(ALLOWED, tenant = TenantId.PLATFORM, entity = EntityRef.PLATFORM)
        }
    }

    @Test
    fun `INV-AC-15489 every look-alike and other domain event is refused with the tenant-wide entity`() {
        REFUSED.forEach { type ->
            assertFalse(Envelope.allowsTenantWide(type), type)
            assertThrows(IllegalArgumentException::class.java, { envelope(type) }, type)
        }
    }

    @Test
    fun `INV-AC-15489 a record without entityref reads tenant-wide only for the allowed type`() {
        val allowed = json.write(envelope(ALLOWED))
        assertEquals(EntityRef.TENANT_WIDE, json.read(allowed).entity)
        REFUSED.forEach { type ->
            val record = allowed.replace(ALLOWED, type)
            val refusal = assertThrows(ContractViolation::class.java, { json.read(record) }, type)
            assertTrue(refusal.message!!.contains("entityref is required"), "$type: ${refusal.message}")
        }
    }

    @Test
    fun `INV-AC-15489 a record naming the reserved tenant-wide id explicitly is refused`() {
        val reserved = "\"entityref\":\"${EntityRef.TENANT_WIDE}\",\"instanceid\""
        val named = json.write(envelope(ALLOWED)).replace("\"instanceid\"", reserved)
        assertThrows(ContractViolation::class.java) { json.read(named) }
    }

    companion object {
        const val ALLOWED = "com.dynokore.gst.transport.AccessPointRouteChanged.v1"
        val REFUSED =
            listOf(
                "com.dynokore.gst.transport.AccessPointRouteChangedX.v1",
                "com.dynokore.gst.transport.accessPointRouteChanged.v1",
                "com.dynokore.gst.transport.AccessPointRouteChanged.v2",
                "com.dynokore.gst.platform.AccessPointRouteChanged.v1",
                "com.dynokore.gst.tenancy.AccessPointRouteChanged.v1",
                "com.dynokore.gst.transport.AcknowledgementReceived.v1",
            )
    }
}

/** INV-AC-15489 on PostgreSQL: the outbox writer stores the allowed type tenant-wide and refuses every other type. */
@Tag("integration")
class TenantWideDomainEventsIT {
    private val tenantWide = "00000000-0000-0000-0000-000000000000"

    private fun catalogue(): EventCatalogue {
        val types = listOf(TenantWideDomainEventsTest.ALLOWED) + TenantWideDomainEventsTest.REFUSED
        val yaml =
            "topics:\n" +
                types
                    .mapIndexed { i, type ->
                        "  - topic: gst.transport.t$i.v1\n    event_type: $type\n    content_topic: true\n" +
                            "    retained_while_resolvable: false\n    record_topic: false\n" +
                            "    producers:\n      - payload-svc\n    subscribers:\n      - submission-svc\n"
                    }.joinToString("")
        return TopicsYamlCatalogue.parse(yaml)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-15489 the writer stores the allowed type tenant-wide and refuses the other types`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = ServiceKit(harness, "payload", "payload-svc", catalogue())
            val tenant = UUID.randomUUID()
            val context =
                EventContext(
                    tenant = TenantId(tenant),
                    entity = EntityRef.TENANT_WIDE,
                    docUuid = null,
                    transactionDate = LocalDate.of(2026, 9, 30),
                    aggregateKey = "ap-1",
                    aggregateVersion = 1,
                    correlation = CorrelationId("corr"),
                    causation = CausationId("cmd"),
                )
            TenantWideDomainEventsTest.REFUSED.forEach { type ->
                assertThrows(EmissionNotPermitted::class.java, {
                    kit.inTx(tenant) { kit.writer.append(listOf(OutboundEvent(type, "{}")), context) }
                }, type)
            }
            assertEquals(0, harness.count("select count(*) from payload.outbox"))
            kit.inTx(tenant) {
                kit.writer.append(listOf(OutboundEvent(TenantWideDomainEventsTest.ALLOWED, "{}")), context)
            }
            assertEquals(
                1,
                harness.count(
                    "select count(*) from payload.outbox where entity_ref = '$tenantWide' and tenant_id = '$tenant'",
                ),
            )
            assertEquals(1, harness.count("select count(*) from payload.outbox"))
        }
    }
}
