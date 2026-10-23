package com.dynokore.gst.access.domain.consent

import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import java.time.LocalDate

/**
 * The scope items of an agent consent, each granted or withheld by the client signatory (INV-REQ-912, VP-10). No item
 * implies another and none is granted by default (INV-REQ-1271).
 */
enum class ScopeItem(
    val wire: String,
) {
    SUBMIT_INVOICE_DATA("SUBMIT_INVOICE_DATA"),
    FILE_GST_RETURN("FILE_GST_RETURN"),
    MAPPING_APPROVAL("MAPPING_APPROVAL"),
}

enum class ConsentStatus { LIVE, WITHDRAWN }

/** The opaque reference of a consent. */
@JvmInline
value class ConsentRef(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) {
            "a reference of the consent is required, 1 to $MAX_LENGTH characters"
        }
    }

    private companion object {
        const val MAX_LENGTH = 64
    }
}

/** What the client signatory granted: the scope items, from which day, and who authorised it. */
data class ConsentTerms(
    val items: Set<ScopeItem>,
    val validFrom: LocalDate,
    val authorisingPersonEmail: String,
)

/**
 * An agent's consent for one client entity (INV-REQ-713, INV-REQ-865). A withdrawal is irreversible: a withdrawn
 * consent is never live again, and a new consent is a new record. Immutable (CS-07).
 */
data class Consent(
    val ref: ConsentRef,
    val client: EntityRef,
    val agent: IdentityRef,
    val terms: ConsentTerms,
    val status: ConsentStatus,
    val handoverStatementRef: String?,
    val version: Long,
) {
    init {
        require(version >= 0) { "version must not be negative" }
        require((status == ConsentStatus.WITHDRAWN) == (handoverStatementRef != null)) {
            "a withdrawn consent names its handover statement, a live one does not"
        }
    }

    val items: Set<ScopeItem> get() = terms.items

    /** Live on [on]: not withdrawn and already valid. */
    fun isLiveOn(on: LocalDate): Boolean = status == ConsentStatus.LIVE && !on.isBefore(terms.validFrom)

    /** Whether [item] is granted by this consent on [on]. */
    fun allows(
        item: ScopeItem,
        on: LocalDate,
    ): Boolean = isLiveOn(on) && item in terms.items

    /** The same consent, withdrawn. A withdrawn consent is returned unchanged (idempotent, API-15). */
    fun withdrawn(statementRef: String): Consent =
        if (status == ConsentStatus.WITHDRAWN) {
            this
        } else {
            copy(status = ConsentStatus.WITHDRAWN, handoverStatementRef = statementRef, version = version + 1)
        }
}

/**
 * Whether an agent may perform an operation that needs [item] on a client entity (INV-REQ-913). A pure function of the
 * consent record it is given, so it cannot serve a cached copy; the caller reads the record live. No record, a record
 * of another agent or client, a withdrawn or not yet valid record, or a record without the item all refuse.
 */
object AgentConsentPolicy {
    fun permits(
        consent: Consent?,
        agent: IdentityRef,
        client: EntityRef,
        item: ScopeItem,
        on: LocalDate,
    ): Boolean = consent != null && consent.agent == agent && permitsClient(consent, client, item, on)

    /**
     * The same decision without the comparison against the consent's agent identity, for a consent-derived grant held
     * by someone other than the agent operator, such as the `tax.approver` (INV-REQ-996; ruling INV-Q-9320 A).
     */
    fun permitsClient(
        consent: Consent?,
        client: EntityRef,
        item: ScopeItem,
        on: LocalDate,
    ): Boolean = consent != null && consent.client == client && consent.allows(item, on)
}
