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
            "InstallationStatusChanged",
            "OperatorSessionKeysChanged",
            "TenantWideEvidenceIntegrityFailed",
            "BreakGlassSessionChanged",
            "BreakGlassActionReported",
            "TenantWideArchiveVolumeMeasured",
            "AuthorityHeadObserved",
            "CertificationMarkRequested",
            "ConnectionCredentialExpiryReminderRequested",
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
            "com.dynokore.gst.access.BreakGlassSessionChanged.v1",
            "com.dynokore.gst.platform.BreakGlassSessionChangedX.v1",
            "com.dynokore.gst.platform.breakGlassSessionChanged.v1",
            "com.dynokore.gst.evidence.BreakGlassSessionChanged.v1",
            "com.dynokore.gst.tenancy.BreakGlassActionReported.v1",
            "com.dynokore.gst.platform.BreakGlassActionReportedX.v1",
            "com.dynokore.gst.platform.breakGlassActionReported.v1",
            "com.dynokore.gst.evidence.BreakGlassActionReported.v1",
            "com.dynokore.gst.evidence.EvidenceIntegrityFailed.v1",
            "com.dynokore.gst.evidence.TenantWideEvidenceIntegrityFailed.v1",
            "com.dynokore.gst.platform.TenantWideEvidenceIntegrityFailedX.v1",
            "com.dynokore.gst.platform.tenantWideEvidenceIntegrityFailed.v1",
            "com.dynokore.gst.platform.EvidenceIntegrityFailed.v1",
            "com.dynokore.gst.evidence.TenantWideArchiveVolumeMeasured.v1",
            "com.dynokore.gst.platform.TenantWideArchiveVolumeMeasuredX.v1",
            "com.dynokore.gst.platform.tenantWideArchiveVolumeMeasured.v1",
            "com.dynokore.gst.platform.ArchiveVolumeMeasured.v1",
            "com.dynokore.gst.evidence.ArchiveVolumeMeasured.v1",
            "com.dynokore.gst.access.AuthorityHeadObserved.v1",
            "com.dynokore.gst.platform.AuthorityHeadObserve.v1",
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
        val types = refused.subList(8, 12)
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
    fun `INV-AC-14296 name set holds InstallationStatusChanged and not the look-alike`() {
        assertTrue(Envelope.isPlatformType("com.dynokore.gst.platform.InstallationStatusChanged.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.InstallationStatusChange.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.installationStatusChanged.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.InstallationStatusChangedX.v1"))
    }

    @Test
    fun `INV-AC-14296 InstallationStatusChanged under the platform tenant is accepted and reads back`() {
        val e =
            envelope("com.dynokore.gst.platform.InstallationStatusChanged.v1", TenantId.PLATFORM, EntityRef.PLATFORM)
        val text = json.write(e)
        assertFalse(text.contains("tenantid") || text.contains("entityref"), text)
        val back = json.read(text)
        assertEquals(TenantId.PLATFORM, back.tenant)
        assertEquals(EntityRef.PLATFORM, back.entity)
    }

    @Test
    fun `INV-AC-14296 InstallationStatusChanged tenancy namespace and look-alike are refused under platform tenant`() {
        listOf(
            "com.dynokore.gst.tenancy.InstallationStatusChanged.v1",
            "com.dynokore.gst.platform.InstallationStatusChange.v1",
        ).forEach {
            assertThrows(
                IllegalArgumentException::class.java,
                { envelope(it, TenantId.PLATFORM, EntityRef.PLATFORM) },
                it,
            )
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
        refused.subList(20, 25).forEach {
            assertThrows(IllegalArgumentException::class.java, { envelope(it, tenant, EntityRef.TENANT_WIDE) }, it)
        }
    }

    @Test
    fun `INV-AC-9303 BreakGlassSessionChanged is tenant-wide for a real tenant and reads back tenant-wide`() {
        val e = envelope("com.dynokore.gst.platform.BreakGlassSessionChanged.v1", tenant, EntityRef.TENANT_WIDE)
        val text = json.write(e)
        assertFalse(text.contains("entityref"), text)
        assertTrue(text.contains("tenantid"), text)
        assertEquals(e, json.read(text))
        assertEquals(EntityRef.TENANT_WIDE, json.read(text).entity)
    }

    @Test
    fun `INV-AC-9303 BreakGlassSessionChanged old access type, look-alike, case and other-package types are refused`() {
        refused.subList(12, 16).forEach {
            assertThrows(IllegalArgumentException::class.java, { envelope(it, tenant, EntityRef.TENANT_WIDE) }, it)
        }
    }

    @Test
    fun `INV-AC-9351 BreakGlassActionReported is tenant-wide for a real tenant and reads back tenant-wide`() {
        val e = envelope("com.dynokore.gst.platform.BreakGlassActionReported.v1", tenant, EntityRef.TENANT_WIDE)
        val text = json.write(e)
        assertFalse(text.contains("entityref"), text)
        assertTrue(text.contains("tenantid"), text)
        assertEquals(e, json.read(text))
        assertEquals(EntityRef.TENANT_WIDE, json.read(text).entity)
    }

    @Test
    fun `INV-AC-9351 BreakGlassActionReported old type, look-alike, case and other package are refused`() {
        refused.subList(16, 20).forEach {
            assertThrows(IllegalArgumentException::class.java, { envelope(it, tenant, EntityRef.TENANT_WIDE) }, it)
        }
    }

    @Test
    fun `INV-AC-16554 TenantWideArchiveVolumeMeasured is tenant-wide, omits entityref and reads back tenant-wide`() {
        val e = envelope("com.dynokore.gst.platform.TenantWideArchiveVolumeMeasured.v1", tenant, EntityRef.TENANT_WIDE)
        val text = json.write(e)
        assertFalse(text.contains("entityref"), text)
        assertTrue(text.contains("tenantid"), text)
        assertEquals(e, json.read(text))
        assertEquals(EntityRef.TENANT_WIDE, json.read(text).entity)
    }

    @Test
    fun `INV-AC-16554 TenantWideArchiveVolumeMeasured domain, look-alike, case and sibling types are refused`() {
        refused.takeLast(5).forEach {
            assertThrows(IllegalArgumentException::class.java, { envelope(it, tenant, EntityRef.TENANT_WIDE) }, it)
        }
    }

    @Test
    fun `INV-AC-16503 name set holds OperatorSessionKeysChanged and not the look-alike`() {
        assertTrue(Envelope.isPlatformType("com.dynokore.gst.platform.OperatorSessionKeysChanged.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.OperatorSessionKeyChanged.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.operatorSessionKeysChanged.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.OperatorSessionKeysChangedX.v1"))
    }

    @Test
    fun `INV-AC-16503 OperatorSessionKeysChanged under the platform tenant is built and reads back platform pair`() {
        val e =
            envelope("com.dynokore.gst.platform.OperatorSessionKeysChanged.v1", TenantId.PLATFORM, EntityRef.PLATFORM)
        val text = json.write(e)
        assertFalse(text.contains("tenantid") || text.contains("entityref"), text)
        val back = json.read(text)
        assertEquals(TenantId.PLATFORM, back.tenant)
        assertEquals(EntityRef.PLATFORM, back.entity)
    }

    @Test
    fun `INV-AC-16503 OperatorSessionKeysChanged access type and look-alike are refused under platform tenant`() {
        listOf(
            "com.dynokore.gst.access.OperatorSessionKeysChanged.v1",
            "com.dynokore.gst.platform.OperatorSessionKeyChanged.v1",
        ).forEach {
            assertThrows(
                IllegalArgumentException::class.java,
                { envelope(it, TenantId.PLATFORM, EntityRef.PLATFORM) },
                it,
            )
        }
    }

    @Test
    fun `INV-AC-17082 name set holds AuthorityHeadObserved and not the look-alike`() {
        assertTrue(Envelope.isPlatformType("com.dynokore.gst.platform.AuthorityHeadObserved.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.AuthorityHeadObserve.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.authorityHeadObserved.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.AuthorityHeadObservedX.v1"))
    }

    @Test
    fun `INV-AC-17082 AuthorityHeadObserved under the platform tenant is built and reads back platform pair`() {
        val e = envelope("com.dynokore.gst.platform.AuthorityHeadObserved.v1", TenantId.PLATFORM, EntityRef.PLATFORM)
        val text = json.write(e)
        assertFalse(text.contains("tenantid") || text.contains("entityref"), text)
        val back = json.read(text)
        assertEquals(TenantId.PLATFORM, back.tenant)
        assertEquals(EntityRef.PLATFORM, back.entity)
    }

    @Test
    fun `INV-AC-17082 AuthorityHeadObserved access type and look-alike are refused under platform tenant`() {
        listOf(
            "com.dynokore.gst.access.AuthorityHeadObserved.v1",
            "com.dynokore.gst.platform.AuthorityHeadObserve.v1",
        ).forEach {
            assertThrows(
                IllegalArgumentException::class.java,
                { envelope(it, TenantId.PLATFORM, EntityRef.PLATFORM) },
                it,
            )
        }
    }

    @Test
    fun `INV-AC-18180 name set holds CertificationMarkRequested and not the look-alike`() {
        assertTrue(Envelope.isPlatformType("com.dynokore.gst.platform.CertificationMarkRequested.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.CertificationMarkRequest.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.certificationMarkRequested.v1"))
        assertFalse(Envelope.isPlatformType("com.dynokore.gst.platform.CertificationMarkRequestedX.v1"))
    }

    @Test
    fun `INV-AC-18180 CertificationMarkRequested under the platform tenant is built and reads back platform pair`() {
        val e =
            envelope("com.dynokore.gst.platform.CertificationMarkRequested.v1", TenantId.PLATFORM, EntityRef.PLATFORM)
        val text = json.write(e)
        assertFalse(text.contains("tenantid") || text.contains("entityref"), text)
        val back = json.read(text)
        assertEquals(TenantId.PLATFORM, back.tenant)
        assertEquals(EntityRef.PLATFORM, back.entity)
    }

    @Test
    fun `INV-AC-18180 CertificationMarkRequested tenancy type and look-alike are refused under platform tenant`() {
        listOf(
            "com.dynokore.gst.tenancy.CertificationMarkRequested.v1",
            "com.dynokore.gst.platform.CertificationMarkRequest.v1",
        ).forEach {
            assertThrows(
                IllegalArgumentException::class.java,
                { envelope(it, TenantId.PLATFORM, EntityRef.PLATFORM) },
                it,
            )
        }
    }

    @Test
    fun `INV-AC-19565 name set holds ConnectionCredentialExpiryReminderRequested and not the look-alike`() {
        val base = "com.dynokore.gst.platform"
        assertTrue(Envelope.isPlatformType("$base.ConnectionCredentialExpiryReminderRequested.v1"))
        assertFalse(Envelope.isPlatformType("$base.ConnectionCredentialExpiryReminderRequest.v1"))
        assertFalse(Envelope.isPlatformType("$base.connectionCredentialExpiryReminderRequested.v1"))
        assertFalse(Envelope.isPlatformType("$base.ConnectionCredentialExpiryReminderRequestedX.v1"))
    }

    @Test
    fun `INV-AC-19565 ConnectionCredentialExpiryReminderRequested is tenant-wide for a real tenant`() {
        val e =
            envelope(
                "com.dynokore.gst.platform.ConnectionCredentialExpiryReminderRequested.v1",
                tenant,
                EntityRef.TENANT_WIDE,
            )
        val back = json.read(json.write(e))
        assertEquals(tenant, back.tenant)
        assertEquals(EntityRef.TENANT_WIDE, back.entity)
    }

    @Test
    fun `INV-AC-19565 ConnectionCredentialExpiryReminderRequested is built under the platform tenant`() {
        val e =
            envelope(
                "com.dynokore.gst.platform.ConnectionCredentialExpiryReminderRequested.v1",
                TenantId.PLATFORM,
                EntityRef.PLATFORM,
            )
        val text = json.write(e)
        assertFalse(text.contains("tenantid") || text.contains("entityref"), text)
        val back = json.read(text)
        assertEquals(TenantId.PLATFORM, back.tenant)
        assertEquals(EntityRef.PLATFORM, back.entity)
    }

    @Test
    fun `INV-AC-19565 ConnectionCredentialExpiryReminderRequested masterdata type is refused tenant-wide`() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) {
            envelope(
                "com.dynokore.gst.masterdata.ConnectionCredentialExpiryReminderRequested.v1",
                tenant,
                EntityRef.TENANT_WIDE,
            )
        }
    }

    @Test
    fun `isPlatformType is true for exactly the platform types and false for the refusal types`() {
        names.forEach { assertTrue(Envelope.isPlatformType("com.dynokore.gst.platform.$it.v1"), it) }
        refused.forEach { assertFalse(Envelope.isPlatformType(it), it) }
    }
}
