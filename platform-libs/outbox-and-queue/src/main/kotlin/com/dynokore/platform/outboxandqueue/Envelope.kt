package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate

/**
 * The common event envelope of FR-K.3 / FR-M.11 in CloudEvents 1.0 terms, plus the
 * payload as JSON text (INV-REQ-1162).
 *
 * A tenantless platform event carries `TenantId.PLATFORM` and [EntityRef.PLATFORM] here (INV-Q-2927
 * option D); its CloudEvents form leaves `tenantid` and `entityref` out.
 *
 * @property id the idempotency key (CloudEvents `id`).
 * @property source `/gst/<emitting service>`; [instanceId] names the instance.
 * @property subject the aggregate key events are ordered under.
 * @property docUuid present when the event concerns a document.
 * @property docSequence per-aggregate monotonic sequence (FR-AH.10); absent when the event
 *   is not document-scoped.
 * @property testMode true when the event was produced in test mode: the CloudEvents attribute `testmode`
 *   (INV-Q-12635, INV-REQ-829). It is written only when true and read strictly; absent means false.
 */
data class Envelope(
    val id: EventId,
    val source: String,
    val type: String,
    val subject: String,
    val time: Instant,
    val tenant: TenantId,
    val entity: EntityRef,
    val docUuid: DocUuid?,
    val transactionDate: LocalDate?,
    val docSequence: Long?,
    val instanceId: String,
    val correlation: CorrelationId,
    val causation: CausationId,
    val dataJson: String,
    val testMode: Boolean = false,
) {
    init {
        require(source.matches(SOURCE_PATTERN)) { "source must be /gst/<service>, was '$source'" }
        require(type.matches(TYPE_PATTERN)) { "event type must be com.dynokore.gst.<service>.<Name>.v<N>, was '$type'" }
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(instanceId.isNotBlank()) { "instance id must not be blank" }
        require(docSequence == null || docSequence >= 1) { "docsequence starts at 1" }
        require(pairingHolds(tenant, entity)) { PLATFORM_PAIR }
        require(!entity.isTenantWide || allowsTenantWide(type)) {
            "only a platform event type or the domain events AccessPointRouteChanged and PlanLimitExceeded " +
                "may carry the tenant-wide entity (FR-K.3, INV-Q-3165, INV-Q-15474), was '$type'"
        }
        require(!tenant.isPlatform || isPlatformType(type)) {
            "only a platform event type may be tenantless (FR-K.3, INV-Q-3018), was '$type'"
        }
    }

    /** The emitting service named by [source]. */
    val service: String get() = source.removePrefix("/gst/")

    companion object {
        private const val PLATFORM_TYPE_PREFIX = "com.dynokore.gst.platform."

        /** INV-REQ-1226 behaviour 1: the closed platform class, exact names only (INV-Q-14069 added the last). */
        private val PLATFORM_EVENTS =
            setOf(
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
                "RulePackStaged",
                "PackCurrencyChanged",
                "ConnectionCredentialExpiryReminderRequested",
            )

        /**
         * True for a platform event type (`com.dynokore.gst.platform.<Name>.v<N>` with `<Name>` one of
         * [PLATFORM_EVENTS] (ReplayRequested and ReplayCompleted by INV-Q-3674, PrivacyNoticeDue
         * and AccreditationAlertRaised by INV-Q-7336, BreakGlassAlertRaised by INV-Q-8839, AuthorityChanged by
         * INV-Q-9229, TenantWideEvidenceIntegrityFailed by INV-Q-12994, BreakGlassSessionChanged by INV-Q-9119,
         * BreakGlassActionReported by INV-Q-9313, TenantWideArchiveVolumeMeasured by INV-Q-14069,
         * OperatorSessionKeysChanged by INV-Q-16498, InstallationStatusChanged by INV-Q-14289,
         * AuthorityHeadObserved by INV-Q-17070,
         * CertificationMarkRequested by the INV-T-16409 design question option C,
         * ConnectionCredentialExpiryReminderRequested by the INV-T-18776 design question option A,
         * RulePackStaged and PackCurrencyChanged by the INV-T-4976 design question option A (INV-T-22365); twenty-four
         * names in all)): the
         * only types that may be written under `TenantId.PLATFORM` and travel without `tenantid` and
         * `entityref` (INV-Q-3018). A domain event always carries a real tenant (FR-K.3, INV-REQ-1162).
         */
        fun isPlatformType(type: String): Boolean =
            type.startsWith(PLATFORM_TYPE_PREFIX) &&
                type.removePrefix(PLATFORM_TYPE_PREFIX).substringBefore(".v") in PLATFORM_EVENTS

        /**
         * The domain event types that may carry the tenant-wide entity, exact strings only (INV-Q-15474 A):
         * `AccessPointRouteChanged` concerns an Access Point connection, not one registered entity;
         * `PlanLimitExceeded` concerns the tenant's plan, not one registered entity (delegated ruling of
         * tooling/t4933-gap.md Round 11, option A; INV-REQ-1162).
         */
        private val TENANT_WIDE_DOMAIN_EVENTS =
            setOf(
                "com.dynokore.gst.transport.AccessPointRouteChanged.v1",
                "com.dynokore.gst.tenancy.PlanLimitExceeded.v1",
            )

        /**
         * True when [type] may carry the tenant-wide entity: a platform type ([isPlatformType]) or a member of
         * the closed exact-type set [TENANT_WIDE_DOMAIN_EVENTS] (FR-K.3, INV-Q-15474). Such a domain event
         * is not a platform type: it still needs a real tenant.
         */
        fun allowsTenantWide(type: String): Boolean = isPlatformType(type) || type in TENANT_WIDE_DOMAIN_EVENTS

        private val SOURCE_PATTERN = Regex("^/gst/[a-z-]+$")
        private val TYPE_PATTERN = Regex("^com\\.dynokore\\.gst\\.[a-z-]+\\.[A-Za-z]+\\.v[0-9]+$")
    }
}
