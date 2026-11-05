package com.dynokore.platform.productruntime

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-T-2876: the replica feeds, the platform-event catalogue and the fence participants as configuration. */
class ProductConfigurationTest {
    private val platformEventNames =
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
        )

    @Test
    fun `each replica takes its events and falls back to its owner (AD-26)`() {
        ReplicaFeeds.of(ReplicaKind.STRUCTURE).map { it.eventType } shouldContainExactly
            listOf(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED, ProductEventTypes.ENTITY_PROFILE_CHANGED)
        ReplicaFeeds.of(ReplicaKind.STRUCTURE).map { it.fallbackService }.toSet() shouldBe setOf("tenancy-svc")
        ReplicaFeeds.of(ReplicaKind.AUTHORITY).single().eventType shouldBe
            PlatformEventCatalogue.AUTHORITY_CHANGED.eventType
        ReplicaFeeds.of(ReplicaKind.AUTHORITY).single().topic shouldBe "gst.platform.authority-changed.v1"
        ReplicaFeeds.of(ReplicaKind.AUTHORITY).single().fallbackService shouldBe "access-svc"
        val policy = ReplicaFeeds.of(ReplicaKind.POLICY).single()
        policy.eventType shouldBe "com.dynokore.gst.platform.PolicyChanged.v1"
        policy.topic shouldBe "gst.platform.policy-changed.v1"
        policy.fallbackService shouldBe null
        val breakGlass = ReplicaFeeds.of(ReplicaKind.BREAK_GLASS_SESSION).single()
        breakGlass.eventType shouldBe "com.dynokore.gst.platform.BreakGlassSessionChanged.v1"
        breakGlass.topic shouldBe "gst.platform.break-glass-session-changed.v1"
        breakGlass.fallbackService shouldBe null
        ReplicaFeeds.ALL.map { it.replica } shouldContainExactly
            listOf(
                ReplicaKind.STRUCTURE,
                ReplicaKind.STRUCTURE,
                ReplicaKind.AUTHORITY,
                ReplicaKind.POLICY,
                ReplicaKind.BREAK_GLASS_SESSION,
            )
        ReplicaFeeds.forType("com.dynokore.gst.payload.PayloadReady.v1") shouldBe null
        ReplicaFeeds.forType("com.dynokore.gst.platform.BreakGlassSessionChange.v1") shouldBe null
    }

    @Test
    fun `INV-AC-9351 BreakGlassActionReported has its topic and event type and is last`() {
        PlatformEventCatalogue.BREAK_GLASS_ACTION_REPORTED.topic shouldBe "gst.platform.break-glass-action-reported.v1"
        PlatformEventCatalogue.BREAK_GLASS_ACTION_REPORTED.eventType shouldBe
            "com.dynokore.gst.platform.BreakGlassActionReported.v1"
        PlatformEventCatalogue.ALL.last() shouldBe PlatformEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_MEASURED
    }

    @Test
    fun `the platform-event catalogue is the platform events of INV-REQ-1226 b1`() {
        PlatformEventCatalogue.ALL.map { it.name } shouldContainExactly platformEventNames
        PlatformEventCatalogue.PRIVACY_NOTICE_DUE.topic shouldBe "gst.platform.privacy-notice-due.v1"
        PlatformEventCatalogue.ACCREDITATION_ALERT_RAISED.topic shouldBe "gst.platform.accreditation-alert-raised.v1"
        PlatformEventCatalogue.BREAK_GLASS_ALERT_RAISED.topic shouldBe "gst.platform.break-glass-alert-raised.v1"
        PlatformEventCatalogue.BREAK_GLASS_ALERT_RAISED.eventType shouldBe
            "com.dynokore.gst.platform.BreakGlassAlertRaised.v1"
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.BreakGlassAlertRaised.v1") shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.access.BreakGlassAlertRaised.v1") shouldBe false
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.PrivacyNoticeDue.v1") shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.AccreditationAlertRaised.v1") shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.control-plane.AccreditationAlertRaised.v1") shouldBe false
        PlatformEventCatalogue.REPLAY_REQUESTED.topic shouldBe null
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.JobRegistered.v1") shouldBe true
        PlatformEventCatalogue.ALL.last() shouldBe PlatformEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_MEASURED
        PlatformEventCatalogue.AUTHORITY_CHANGED.topic shouldBe "gst.platform.authority-changed.v1"
        PlatformEventCatalogue.AUTHORITY_CHANGED.eventType shouldBe "com.dynokore.gst.platform.AuthorityChanged.v1"
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.AuthorityChanged.v1") shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.access.AuthorityChanged.v1") shouldBe false
    }

    @Test
    fun `INV-AC-14296 the catalogue holds InstallationStatusChanged with its topic and type (INV-Q-14289)`() {
        PlatformEventCatalogue.INSTALLATION_STATUS_CHANGED.topic shouldBe "gst.platform.installation-status-changed.v1"
        PlatformEventCatalogue.INSTALLATION_STATUS_CHANGED.eventType shouldBe
            "com.dynokore.gst.platform.InstallationStatusChanged.v1"
        PlatformEventCatalogue.ALL.contains(PlatformEventCatalogue.INSTALLATION_STATUS_CHANGED) shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.InstallationStatusChanged.v1") shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.tenancy.InstallationStatusChanged.v1") shouldBe false
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.InstallationStatusChange.v1") shouldBe false
    }

    @Test
    fun `INV-AC-9303 the catalogue holds BreakGlassSessionChanged last with its topic and type`() {
        PlatformEventCatalogue.BREAK_GLASS_SESSION_CHANGED.topic shouldBe "gst.platform.break-glass-session-changed.v1"
        PlatformEventCatalogue.BREAK_GLASS_SESSION_CHANGED.eventType shouldBe
            "com.dynokore.gst.platform.BreakGlassSessionChanged.v1"
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.BreakGlassSessionChanged.v1") shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.access.BreakGlassSessionChanged.v1") shouldBe false
    }

    @Test
    fun `the catalogue holds TenantWideEvidenceIntegrityFailed before BreakGlassSessionChanged (INV-Q-12994)`() {
        PlatformEventCatalogue.TENANT_WIDE_EVIDENCE_INTEGRITY_FAILED.topic shouldBe
            "gst.platform.tenant-wide-evidence-integrity-failed.v1"
        PlatformEventCatalogue.TENANT_WIDE_EVIDENCE_INTEGRITY_FAILED.eventType shouldBe
            "com.dynokore.gst.platform.TenantWideEvidenceIntegrityFailed.v1"
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.TenantWideEvidenceIntegrityFailed.v1") shouldBe
            true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.evidence.TenantWideEvidenceIntegrityFailed.v1") shouldBe
            false
    }

    @Test
    fun `INV-AC-16554 the catalogue holds TenantWideArchiveVolumeMeasured last (INV-Q-14069)`() {
        PlatformEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_MEASURED.topic shouldBe
            "gst.platform.tenant-wide-archive-volume-measured.v1"
        PlatformEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_MEASURED.eventType shouldBe
            "com.dynokore.gst.platform.TenantWideArchiveVolumeMeasured.v1"
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.TenantWideArchiveVolumeMeasured.v1") shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.evidence.TenantWideArchiveVolumeMeasured.v1") shouldBe false
    }

    @Test
    fun `INV-AC-16503 the catalogue holds OperatorSessionKeysChanged with its topic and type (INV-Q-16498)`() {
        PlatformEventCatalogue.OPERATOR_SESSION_KEYS_CHANGED.topic shouldBe
            "gst.platform.operator-session-keys-changed.v1"
        PlatformEventCatalogue.OPERATOR_SESSION_KEYS_CHANGED.eventType shouldBe
            "com.dynokore.gst.platform.OperatorSessionKeysChanged.v1"
        PlatformEventCatalogue.ALL.contains(PlatformEventCatalogue.OPERATOR_SESSION_KEYS_CHANGED) shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.OperatorSessionKeysChanged.v1") shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.access.OperatorSessionKeysChanged.v1") shouldBe false
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.OperatorSessionKeyChanged.v1") shouldBe false
    }

    @Test
    fun `rules-svc arms and only the pack holders in the train are under the fence (INV-REQ-1166 b2)`() {
        TrainFence.participants(setOf("rules-svc", "payload-svc", "submission-svc", "tenancy-svc")) shouldContainExactly
            listOf("rules-svc", "payload-svc", "tenancy-svc")
        TrainFence.participants(setOf("submission-svc")) shouldContainExactly listOf("rules-svc")
        TrainFence.holdsPacks("notification-svc") shouldBe true
        TrainFence.holdsPacks("submission-svc") shouldBe false
    }
}
