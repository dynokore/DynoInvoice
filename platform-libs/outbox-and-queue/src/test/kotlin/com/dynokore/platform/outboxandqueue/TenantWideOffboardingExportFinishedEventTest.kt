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
 * The envelope accepts the tenant-wide entity for the domain event OffboardingExportFinished, as for
 * AccessPointRouteChanged, PlanLimitExceeded and UnsubmittedDocumentCountMeasured, and for no other domain
 * event (delegated ruling of tooling/gap22956-gap.md option A, INV-REQ-1162, INV-AC-23211): exact type
 * string, still under a real tenant.
 */
class TenantWideOffboardingExportFinishedEventTest {
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
        subject = "export-1",
        time = Instant.parse("2026-10-01T00:00:00Z"),
        tenant = tenant,
        entity = entity,
        docUuid = null,
        transactionDate = LocalDate.of(2026, 9, 30),
        docSequence = null,
        instanceId = "submission-svc-1",
        correlation = CorrelationId("c"),
        causation = CausationId("k"),
        dataJson = """{"version":1}""",
    )

    @Test
    fun `INV-AC-23211 the export event with a real tenant and the tenant-wide entity round-trips without entityref`() {
        val written = envelope(ALLOWED)
        val text = json.write(written)
        assertFalse(text.contains("entityref") || text.contains("00000000-0000"), text)
        assertEquals(written, json.read(text))
        assertEquals(EntityRef.TENANT_WIDE, json.read(text).entity)
        assertTrue(Envelope.allowsTenantWide(ALLOWED))
        assertFalse(Envelope.isPlatformType(ALLOWED))
    }

    @Test
    fun `INV-AC-23211 OffboardingExportFinished is refused under the platform tenant`() {
        assertThrows(IllegalArgumentException::class.java) { envelope(ALLOWED, tenant = TenantId.PLATFORM) }
        assertThrows(IllegalArgumentException::class.java) {
            envelope(ALLOWED, tenant = TenantId.PLATFORM, entity = EntityRef.PLATFORM)
        }
    }

    @Test
    fun `INV-AC-23211 every look-alike and other domain event is refused with the tenant-wide entity`() {
        REFUSED.forEach { type ->
            assertFalse(Envelope.allowsTenantWide(type), type)
            assertThrows(IllegalArgumentException::class.java, { envelope(type) }, type)
        }
    }

    @Test
    fun `INV-AC-23211 a record without entityref reads tenant-wide only for the allowed type`() {
        val allowed = json.write(envelope(ALLOWED))
        assertEquals(EntityRef.TENANT_WIDE, json.read(allowed).entity)
        REFUSED.forEach { type ->
            val record = allowed.replace(ALLOWED, type)
            val refusal = assertThrows(ContractViolation::class.java, { json.read(record) }, type)
            assertTrue(refusal.message!!.contains("entityref is required"), "$type: ${refusal.message}")
        }
    }

    @Test
    fun `INV-AC-23211 the three earlier exact types are still accepted with the tenant-wide entity`() {
        listOf(ACCESS_POINT, PLAN_LIMIT, UNSUBMITTED).forEach { type ->
            val written = envelope(type)
            assertEquals(written, json.read(json.write(written)), type)
            assertTrue(Envelope.allowsTenantWide(type), type)
            assertThrows(IllegalArgumentException::class.java, { envelope(type, tenant = TenantId.PLATFORM) }, type)
        }
    }

    companion object {
        const val ALLOWED = "com.dynokore.gst.evidence.OffboardingExportFinished.v1"
        const val ACCESS_POINT = "com.dynokore.gst.transport.AccessPointRouteChanged.v1"
        const val PLAN_LIMIT = "com.dynokore.gst.tenancy.PlanLimitExceeded.v1"
        const val UNSUBMITTED = "com.dynokore.gst.submission.UnsubmittedDocumentCountMeasured.v1"
        val REFUSED =
            listOf(
                "com.dynokore.gst.evidence.OffboardingExportFinishedX.v1",
                "com.dynokore.gst.evidence.offboardingExportFinished.v1",
                "com.dynokore.gst.evidence.OffboardingExportFinished.v2",
                "com.dynokore.gst.platform.OffboardingExportFinished.v1",
                "com.dynokore.gst.tenancy.OffboardingExportFinished.v1",
                "com.dynokore.gst.tenancy.OffboardingExportRequested.v1",
            )
    }
}

/** INV-AC-23211 on PostgreSQL: the outbox writer stores the export event tenant-wide and refuses every look-alike. */
@Tag("integration")
class TenantWideOffboardingExportFinishedEventIT {
    private val tenantWide = "00000000-0000-0000-0000-000000000000"

    private fun catalogue(): EventCatalogue {
        val types =
            listOf(TenantWideOffboardingExportFinishedEventTest.ALLOWED) +
                TenantWideOffboardingExportFinishedEventTest.REFUSED
        val yaml =
            "topics:\n" +
                types
                    .mapIndexed { i, type ->
                        "  - topic: gst.evidence.t$i.v1\n    event_type: $type\n    content_topic: true\n" +
                            "    retained_while_resolvable: false\n    record_topic: false\n" +
                            "    producers:\n      - payload-svc\n    subscribers:\n      - submission-svc\n"
                    }.joinToString("")
        return TopicsYamlCatalogue.parse(yaml)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-23211 the writer stores the export event tenant-wide and refuses the look-alikes`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = ServiceKit(harness, "payload", "payload-svc", catalogue())
            val tenant = UUID.randomUUID()
            val context =
                EventContext(
                    tenant = TenantId(tenant),
                    entity = EntityRef.TENANT_WIDE,
                    docUuid = null,
                    transactionDate = LocalDate.of(2026, 9, 30),
                    aggregateKey = "export-1",
                    aggregateVersion = 1,
                    correlation = CorrelationId("corr"),
                    causation = CausationId("cmd"),
                )
            TenantWideOffboardingExportFinishedEventTest.REFUSED.forEach { type ->
                assertThrows(EmissionNotPermitted::class.java, {
                    kit.inTx(tenant) { kit.writer.append(listOf(OutboundEvent(type, "{}")), context) }
                }, type)
            }
            assertEquals(0, harness.count("select count(*) from payload.outbox"))
            kit.inTx(tenant) {
                val allowed = OutboundEvent(TenantWideOffboardingExportFinishedEventTest.ALLOWED, "{}")
                kit.writer.append(listOf(allowed), context)
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
