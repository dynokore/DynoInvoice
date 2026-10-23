package com.dynokore.gst.access.application.consent

import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import java.time.Instant
import java.time.LocalDate

/**
 * Persistence of agent consents. Every read is live and runs under the bound tenant: the database policy, not the
 * application, restricts the rows (INV-REQ-866). Nothing here caches (INV-REQ-865, INV-REQ-1214).
 */
interface ConsentRepository {
    fun find(ref: ConsentRef): Consent?

    /** Locks the consent row until the transaction ends; the withdrawal and a concurrent check never interleave. */
    fun lock(ref: ConsentRef): Consent?

    /** The live consent of [client], whatever its agent, or null: at most one is live per client entity. */
    fun findLiveForClient(client: EntityRef): Consent?

    fun insert(consent: Consent)

    /** Replaces [before] by [after]; throws the platform's StaleAggregate when [before] is no longer current. */
    fun update(
        before: Consent,
        after: Consent,
    )

    /** The next value of access-svc's one monotonic authority sequence. */
    fun nextAuthorityVersion(): Long

    fun nextRef(): ConsentRef
}

/** The full state, after a change, of the consent for one client entity (the `consentRecord` of AuthorityChanged). */
data class ConsentAuthorityRecord(
    val tenant: TenantRef,
    val consent: Consent,
    val authorityVersion: Long,
)

/** Publishes AuthorityChanged with a consent record in the command transaction (INV-API-3325). */
fun interface ConsentChangePublisher {
    fun publish(record: ConsentAuthorityRecord)
}

/** One document ingested but not yet submitted, as the document-list port supplies it (INV-REQ-955). */
data class UnsubmittedDocument(
    val documentRef: String,
    val dueDate: LocalDate,
    val blockingReason: String,
)

/** What the document-list port answered: the documents, or that the list cannot be had. */
sealed interface DocumentList {
    data class Listed(
        val documents: List<UnsubmittedDocument>,
    ) : DocumentList

    /** The port is unbound (profile `prod` before Wave 3, INV-Q-4900) or failed: the statement says so. */
    data object Unavailable : DocumentList
}

/**
 * The documents of a client entity that are ingested and not submitted. Bound by the service that owns the documents
 * from Wave 3; until then the unbound variant answers [DocumentList.Unavailable] (coordinator ruling INV-Q-4900).
 */
fun interface UnsubmittedDocuments {
    fun forClient(client: EntityRef): DocumentList

    companion object {
        /** The port before its binding: every statement names the list as unavailable. */
        val UNBOUND = UnsubmittedDocuments { DocumentList.Unavailable }
    }
}

/** The handover statement of a withdrawal: what the client must now discharge itself (INV-REQ-955). */
data class HandoverStatement(
    val ref: String,
    val consentRef: ConsentRef,
    val client: EntityRef,
    val agent: IdentityRef,
    val documents: DocumentList,
    val producedAt: Instant,
)

interface HandoverStatements {
    fun insert(statement: HandoverStatement)

    fun find(ref: String): HandoverStatement?

    fun nextRef(): String
}

/** Whether agent tenancy is on in this installation (VP-74, INV-REQ-2757); read live from the policy. */
fun interface AgentTenancy {
    fun enabled(): Boolean
}
