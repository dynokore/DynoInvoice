package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import java.time.LocalDate
import java.util.UUID

/** The direction of a document as recorded; the spellings of the published canonical-version-created schema. */
enum class RoutingDirection { SALE, PURCHASE }

/** How the document reached the platform; the spellings of the published canonical-version-created schema. */
enum class RoutingNetworkPath { ISSUED_VIA_NETWORK, RECEIVED_VIA_NETWORK, RELAY_ISSUANCE, NOT_ON_NETWORK }

/** The submission type of a self-billing assignment (INV-REQ-415). */
enum class SelfBillingSubmissionType { TYPE_1A, TYPE_1B, TYPE_2, TYPE_3 }

/** The party-to-type assignment of a self-billed document, copied from the rules result without reading it. */
data class SelfBillingAssignmentData(
    val direction: RoutingDirection,
    val submissionType: SelfBillingSubmissionType,
)

/**
 * The routing facts submission-svc needs at derivation step 9 (INV-REQ-415, INV-REQ-408, INV-REQ-373, INV-REQ-319).
 * Each member is supplied by the caller from the source the published schema names; the writer derives none. An
 * unsupplied optional member stays null.
 */
data class RoutingFactsData(
    val direction: RoutingDirection,
    val networkPath: RoutingNetworkPath,
    val counterpartyOnNetwork: Boolean? = null,
    val debitNote: Boolean? = null,
    val selfBillingAssignment: SelfBillingAssignmentData? = null,
)

/** A rule pack version applied to derive a canonical version. */
data class AppliedPackVersion(
    val packIdentifier: String,
    val version: String,
)

/**
 * The facts of one `CanonicalVersionCreated` event, supplied by the caller and never guessed by the writer. The
 * docsequence is not here: the publisher adapter reserves it. [aggregateId] null means absent.
 */
data class CanonicalVersionCreatedFacts(
    val docUuid: UUID,
    val entityRef: String,
    val version: Int,
    val transactionDate: LocalDate,
    val prescribedPeriodRef: String,
    val scopeVerdict: String,
    val appliedPackVersions: List<AppliedPackVersion>,
    val traceReference: String,
    val canonicalDocument: String,
    val aggregateId: String?,
    val routingFacts: RoutingFactsData,
) {
    init {
        require(traceReference.isNotBlank()) { "traceReference is blank" }
        require(prescribedPeriodRef.isNotBlank()) { "prescribedPeriodRef is blank" }
        require(aggregateId == null || aggregateId.isNotBlank()) { "aggregateId is blank" }
    }
}

/** Stages a `CanonicalVersionCreated` fact set in the caller's transaction (transactional outbox). */
interface CanonicalVersionCreatedPublisher {
    fun stage(facts: CanonicalVersionCreatedFacts)
}

/** The aggregate reference stored for a document, or null when it has none. */
interface DocumentAggregateRefs {
    fun aggregateIdOf(docUuid: UUID): String?

    /** Sets the reference of [docUuid]; true only when this call set the column, false when it changed no row. */
    fun bind(
        docUuid: UUID,
        aggregateId: String,
    ): Boolean
}

/** Why a version is written without an event. The writer cannot verify the reason; the call site chooses it. */
enum class UnannouncedReason { NO_SCOPE_VERDICT, NO_PRESCRIBED_PERIOD }

/**
 * The single writer of document versions: a version is written together with its `CanonicalVersionCreated` fact set,
 * or, when it cannot yet be announced, without one. It opens no transaction: every operation runs inside the
 * caller's transaction, and an exception of the store or the publisher propagates unchanged.
 */
class CanonicalVersionWriter(
    private val store: DocumentStore,
    private val publisher: CanonicalVersionCreatedPublisher,
    private val aggregateRefs: DocumentAggregateRefs,
) {
    fun createFirst(
        document: NewDocument,
        facts: CanonicalVersionCreatedFacts,
    ) {
        val aggregateId = facts.aggregateId
        require(aggregateId == null || facts.docUuid == document.docUuid) {
            "facts docUuid ${facts.docUuid} differs from document ${document.docUuid}"
        }
        store.insert(document)
        if (aggregateId != null) {
            check(aggregateRefs.bind(document.docUuid, aggregateId)) {
                "aggregate reference of ${document.docUuid} was not set"
            }
        }
        publisher.stage(facts)
    }

    fun next(
        docUuid: UUID,
        expectedVersion: Int,
        version: NewVersion,
        facts: CanonicalVersionCreatedFacts,
    ): UpdateOutcome {
        val outcome = store.createVersion(docUuid, expectedVersion, version)
        if (outcome is UpdateOutcome.Updated) {
            publisher.stage(
                facts.copy(
                    version = outcome.version.toInt(),
                    aggregateId = facts.aggregateId ?: aggregateRefs.aggregateIdOf(docUuid),
                ),
            )
        }
        return outcome
    }

    /** Writes version 1 without an event; [reason] is the caller's statement of why it cannot be announced. */
    @Suppress("UNUSED_PARAMETER")
    fun createFirstUnannounced(
        document: NewDocument,
        reason: UnannouncedReason,
    ) {
        store.insert(document)
    }

    /** Writes the next version without an event and returns the store's outcome unchanged. */
    @Suppress("UNUSED_PARAMETER")
    fun nextUnannounced(
        docUuid: UUID,
        expectedVersion: Int,
        version: NewVersion,
        reason: UnannouncedReason,
    ): UpdateOutcome = store.createVersion(docUuid, expectedVersion, version)
}
