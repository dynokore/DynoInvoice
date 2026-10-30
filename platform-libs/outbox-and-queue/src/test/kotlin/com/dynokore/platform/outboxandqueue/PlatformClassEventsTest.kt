package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** The platform events of INV-REQ-1226 b1 (INV-Q-7336, INV-Q-8839): a closed set of exact names. */
class PlatformClassEventsTest {
    private val json = EnvelopeJson()
    private val tenant = TenantId(UUID.randomUUID())
    private val names =
        listOf(
            "AuditEntryRecorded",
            "JobRunRecorded",
            "JobRegistered",
            "ConnectionStatusChanged",
            "EvidenceArtefactRecorded",
            "PolicyChanged",
            "PackReadinessAcknowledged",
            "ReplayRequested",
            "ReplayCompleted",
            "PrivacyNoticeDue",
            "AccreditationAlertRaised",
            "BreakGlassAlertRaised",
            "AuthorityChanged",
            "TenantWideEvidenceIntegrityFailed",
        )
    private val refused =
        listOf(
            "com.dynokore.gst.control-plane.AccreditationAlertRaised.v1",
            "com.dynokore.gst.platform.AccreditationAlertRaisedX.v1",
            "com.dynokore.gst.platform.privacyNoticeDue.v1",
            "com.dynokore.gst.evidence.PrivacyNoticeDue.v1",
            "com.dynokore.gst.access.BreakGlassAlertRaised.v1",
            "com.dynokore.gst.platform.BreakGlassAlertRaisedX.v1",
            "com.dynokore.gst.platform.breakGlassAlertRaised.v1",
            "com.dynokore.gst.evidence.BreakGlassAlertRaised.v1",
            "com.dynokore.gst.access.AuthorityChanged.v1",
            "com.dynokore.gst.platform.AuthorityChangedX.v1",
            "com.dynokore.gst.platform.authorityChanged.v1",
            "com.dynokore.gst.evidence.AuthorityChanged.v1",
            "com.dynokore.gst.evidence.EvidenceIntegrityFailed.v1",
            "com.dynokore.gst.evidence.TenantWideEvidenceIntegrityFailed.v1",
            "com.dynokore.gst.platform.TenantWideEvidenceIntegrityFailedX.v1",
            "com.dynokore.gst.platform.tenantWideEvidenceIntegrityFailed.v1",
            "com.dynokore.gst.platform.EvidenceIntegrityFailed.v1",
        )

    private fun envelope(
        type: String,
        tenant: TenantId,
        entity: EntityRef,
    ) = Envelope(
        id = EventId(UuidV7().next(Instant.parse("2026-10-01T00:00:00Z")).toString()),
        source = "/gst/payload-svc",
        type = type,
        subject = "s-1",
        time = Instant.parse("2026-10-01T00:00:00Z"),
        tenant = tenant,
        entity = entity,
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "payload-svc-1",
        correlation = CorrelationId("c"),
        causation = CausationId("k"),
        dataJson = """{"v":1}""",
    )

    @Test
    fun `INV-AC-1404 PrivacyNoticeDue is tenant-wide, omits entityref and reads back tenant-wide`() {
        val e = envelope("com.dynokore.gst.platform.PrivacyNoticeDue.v1", tenant, EntityRef.TENANT_WIDE)
        val text = json.write(e)
        assertFalse(text.contains("entityref"), text)
        assertEquals(e, json.read(text))
        assertEquals(EntityRef.TENANT_WIDE, json.read(text).entity)
    }

    @Test
    fun `INV-AC-8821 BreakGlassAlertRaised is tenant-wide, omits entityref and reads back tenant-wide`() {
        val e = envelope("com.dynokore.gst.platform.BreakGlassAlertRaised.v1", tenant, EntityRef.TENANT_WIDE)
        val text = json.write(e)
        assertFalse(text.contains("entityref"), text)
        assertEquals(e, json.read(text))
        assertEquals(EntityRef.TENANT_WIDE, json.read(text).entity)
    }

    @Test
    fun `INV-AC-8821 BreakGlassAlertRaised look-alike, case and other-package types are refused`() {
        refused.subList(4, 8).forEach {
            assertThrows(IllegalArgumentException::class.java, { envelope(it, tenant, EntityRef.TENANT_WIDE) }, it)
        }
    }

    @Test
    fun `INV-AC-9359 AuthorityChanged is tenant-wide for a real tenant and reads back tenant-wide`() {
        val e = envelope("com.dynokore.gst.platform.AuthorityChanged.v1", tenant, EntityRef.TENANT_WIDE)
        val text = json.write(e)
        assertFalse(text.contains("entityref"), text)
        assertTrue(text.contains("tenantid"), text)
        assertEquals(e, json.read(text))
        assertEquals(EntityRef.TENANT_WIDE, json.read(text).entity)
    }

    @Test
    fun `INV-AC-9359 AuthorityChanged under the platform tenant reads back as the platform pair`() {
        val e = envelope("com.dynokore.gst.platform.AuthorityChanged.v1", TenantId.PLATFORM, EntityRef.PLATFORM)
        val text = json.write(e)
        assertFalse(text.contains("tenantid") || text.contains("entityref"), text)
        val back = json.read(text)
        assertEquals(TenantId.PLATFORM, back.tenant)
        assertEquals(EntityRef.PLATFORM, back.entity)
    }

    @Test
    fun `INV-AC-9359 AuthorityChanged old access type, look-alike, case and other-package types are refused`() {
        val types = refused.takeLast(4)
        types.forEach {
            assertThrows(IllegalArgumentException::class.java, { envelope(it, tenant, EntityRef.TENANT_WIDE) }, it)
        }
        types.forEach {
            assertThrows(
                IllegalArgumentException::class.java,
                { envelope(it, TenantId.PLATFORM, EntityRef.PLATFORM) },
                it,
            )
        }
    }

    @Test
    fun `INV-AC-2340 AccreditationAlertRaised is tenantless and reads back as the platform pair`() {
        val type = "com.dynokore.gst.platform.AccreditationAlertRaised.v1"
        val e = envelope(type, TenantId.PLATFORM, EntityRef.PLATFORM)
        val text = json.write(e)
        assertFalse(text.contains("tenantid") || text.contains("entityref"), text)
        val back = json.read(text)
        assertEquals(TenantId.PLATFORM, back.tenant)
        assertEquals(EntityRef.PLATFORM, back.entity)
    }

    @Test
    fun `INV-AC-2340 look-alike and other-package types are refused under the platform tenant`() {
        refused.take(2).forEach {
            assertThrows(
                IllegalArgumentException::class.java,
                { envelope(it, TenantId.PLATFORM, EntityRef.PLATFORM) },
                it,
            )
        }
    }

    @Test
    fun `INV-AC-1404 look-alike and other-package types are refused with the tenant-wide entity`() {
        refused.subList(2, 4).forEach {
            assertThrows(IllegalArgumentException::class.java, { envelope(it, tenant, EntityRef.TENANT_WIDE) }, it)
        }
    }

    @Test
    fun `INV-AC-13291 TenantWideEvidenceIntegrityFailed is tenant-wide, omits entityref and reads back tenant-wide`() {
        val e =
            envelope("com.dynokore.gst.platform.TenantWideEvidenceIntegrityFailed.v1", tenant, EntityRef.TENANT_WIDE)
        val text = json.write(e)
        assertFalse(text.contains("entityref"), text)
        assertTrue(text.contains("tenantid"), text)
        assertEquals(e, json.read(text))
        assertEquals(EntityRef.TENANT_WIDE, json.read(text).entity)
    }

    @Test
    fun `INV-AC-13291 TenantWideEvidenceIntegrityFailed domain, look-alike, case and sibling types are refused`() {
        refused.takeLast(5).forEach {
            assertThrows(IllegalArgumentException::class.java, { envelope(it, tenant, EntityRef.TENANT_WIDE) }, it)
        }
    }

    @Test
    fun `isPlatformType is true for exactly the platform types and false for the refusal types`() {
        names.forEach { assertTrue(Envelope.isPlatformType("com.dynokore.gst.platform.$it.v1"), it) }
        refused.forEach { assertFalse(Envelope.isPlatformType(it), it) }
    }
}
