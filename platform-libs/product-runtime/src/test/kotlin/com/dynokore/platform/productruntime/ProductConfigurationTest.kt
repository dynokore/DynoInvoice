package com.dynokore.platform.productruntime

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-T-2876: the replica feeds, the platform-event catalogue and the fence participants as configuration. */
class ProductConfigurationTest {
    @Test
    fun `each replica takes its events and falls back to its owner (AD-26)`() {
        ReplicaFeeds.of(ReplicaKind.STRUCTURE).map { it.eventType } shouldContainExactly
            listOf(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED, ProductEventTypes.ENTITY_PROFILE_CHANGED)
        ReplicaFeeds.of(ReplicaKind.STRUCTURE).map { it.fallbackService }.toSet() shouldBe setOf("tenancy-svc")
        ReplicaFeeds.of(ReplicaKind.AUTHORITY).single().eventType shouldBe ProductEventTypes.AUTHORITY_CHANGED
        ReplicaFeeds.of(ReplicaKind.AUTHORITY).single().fallbackService shouldBe "access-svc"
        val policy = ReplicaFeeds.of(ReplicaKind.POLICY).single()
        policy.eventType shouldBe "com.dynokore.gst.platform.PolicyChanged.v1"
        policy.topic shouldBe "gst.platform.policy-changed.v1"
        policy.fallbackService shouldBe null
        ReplicaFeeds.forType("com.dynokore.gst.payload.PayloadReady.v1") shouldBe null
    }

    @Test
    fun `the platform-event catalogue is the eleven platform events of INV-REQ-1226 b1`() {
        PlatformEventCatalogue.ALL.map { it.name } shouldContainExactly
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
            )
        PlatformEventCatalogue.PRIVACY_NOTICE_DUE.topic shouldBe "gst.platform.privacy-notice-due.v1"
        PlatformEventCatalogue.ACCREDITATION_ALERT_RAISED.topic shouldBe "gst.platform.accreditation-alert-raised.v1"
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.PrivacyNoticeDue.v1") shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.AccreditationAlertRaised.v1") shouldBe true
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.control-plane.AccreditationAlertRaised.v1") shouldBe false
        PlatformEventCatalogue.REPLAY_REQUESTED.topic shouldBe null
        PlatformEventCatalogue.isPlatform("com.dynokore.gst.platform.JobRegistered.v1") shouldBe true
        PlatformEventCatalogue.isPlatform(ProductEventTypes.AUTHORITY_CHANGED) shouldBe false
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
