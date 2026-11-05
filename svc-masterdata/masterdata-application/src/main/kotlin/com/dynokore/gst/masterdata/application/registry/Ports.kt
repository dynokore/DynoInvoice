package com.dynokore.gst.masterdata.application.registry

import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.registry.CacheLifetimes
import com.dynokore.gst.masterdata.domain.registry.LookupEvidence
import com.dynokore.gst.masterdata.domain.registry.NetworkRegistration
import com.dynokore.gst.masterdata.domain.registry.RegisterFact
import com.dynokore.gst.masterdata.domain.registry.RegisterPolicy
import com.dynokore.gst.masterdata.domain.registry.RegisteredField
import java.time.Instant
import java.time.LocalDate

/** The answer of the SG Peppol Directory and SMP for one Peppol ID, as the connection translates it. */
sealed interface DirectoryReply {
    /** The participant is registered; [apReference] is the AP's reference when the answer carries one. */
    data class Found(
        val apReference: String?,
        val raw: String,
    ) : DirectoryReply

    /** The participant is not on the directory. */
    data class NotFound(
        val raw: String,
    ) : DirectoryReply

    /** The directory or SMP could not be reached within the deadline, or answered with a failure. */
    data object Unreachable : DirectoryReply
}

/** The connection to the SG Peppol Directory and SMP (adapter-runtime binding); it never writes to the directory. */
fun interface DirectoryClient {
    fun lookup(peppolId: String): DirectoryReply
}

/** The answer of the IRAS Check GST Register for one UEN. */
sealed interface RegisterReply {
    data class Answered(
        val fact: RegisterFact,
        val raw: String,
    ) : RegisterReply

    /** The register could not be reached within the deadline, or answered with a failure. */
    data object Unavailable : RegisterReply
}

/** The connection to the IRAS Check GST Register (adapter-runtime binding). */
fun interface RegisterClient {
    fun lookup(uen: String): RegisterReply
}

/**
 * The lifetimes and the register policy as they resolve through config-resolver's `PolicyReader` on [on]: VP-38
 * (directory), VP-65 (register), VP-29 (what the register lookup does with a difference).
 */
interface RegistryPolicies {
    fun directoryLifetimes(
        entity: String,
        on: LocalDate,
    ): CacheLifetimes

    fun registerLifetimes(
        entity: String,
        on: LocalDate,
    ): CacheLifetimes

    fun registerPolicy(
        entity: String,
        on: LocalDate,
    ): RegisterPolicy
}

/** A directory answer kept for its lifetime; [recordKey] names the lookup record that holds the response. */
data class CachedDirectory(
    val peppolId: String,
    val found: Boolean,
    val apReference: String?,
    val lookedUpAt: Instant,
    val recordKey: String?,
)

/** A register answer kept for its lifetime; [recordKey] names the lookup record that holds the response. */
data class CachedRegister(
    val uen: String,
    val fact: RegisterFact,
    val lookedUpAt: Instant,
    val recordKey: String?,
)

/** The directory cache of the bound tenant: one entry per Peppol ID, replaced by a later lookup. */
interface DirectoryCache {
    fun find(peppolId: String): CachedDirectory?

    fun store(entry: CachedDirectory)
}

/** The register cache of the bound tenant: one entry per UEN, replaced by a later lookup. */
interface RegisterCache {
    fun find(uen: String): CachedRegister?

    fun store(entry: CachedRegister)
}

/**
 * Stores one lookup as evidence (queried value, response, timestamp) and publishes `EvidenceArtefactRecorded` for it in
 * the caller's transaction (INV-REQ-802 b2). Returns the key of the lookup record, the `reference` of the event.
 * [degradation] is the catalogue code the lookup ended in (MDM-008), or null.
 */
fun interface LookupEvidenceLog {
    fun record(
        entity: String,
        evidence: LookupEvidence,
        degradation: String?,
    ): String
}

/** A register value suggested for a counterparty, awaiting the operator (INV-REQ-1290). */
data class RegisterSuggestion(
    val suggestionRef: String,
    val entity: String,
    val party: PartyRef,
    val field: RegisteredField,
    val registerValue: String,
    val currentValue: String?,
    val state: SuggestionState,
    val suggestedAt: Instant,
    val evidenceRef: String?,
)

enum class SuggestionState { PENDING, CONFIRMED, SUPERSEDED }

/** What a register lookup found for [party] of [entity]: the register value, the held one and the lookup behind it. */
data class SuggestionDraft(
    val entity: String,
    val party: PartyRef,
    val field: RegisteredField,
    val registerValue: String,
    val currentValue: String?,
    val evidenceRef: String?,
    val at: Instant,
)

interface RegisterSuggestions {
    /**
     * Stores a PENDING suggestion of the draft's field and supersedes an earlier pending one of the same
     * counterparty and field; a pending one with the same register value is returned as it is.
     */
    fun suggest(draft: SuggestionDraft): RegisterSuggestion

    /**
     * Stores the register value an automatic application wrote (VP-29 `AUTO_APPLY`) as a CONFIRMED suggestion: the
     * provenance record of the applied value (what the register said, what it replaced, which lookup it came from).
     */
    fun recordApplied(draft: SuggestionDraft): RegisterSuggestion

    fun find(suggestionRef: String): RegisterSuggestion?

    /** The suggestions after [after] (a `suggestedAt` and a reference), ascending, at most [limit]. */
    fun list(
        party: PartyRef?,
        state: SuggestionState?,
        after: SuggestionCursor?,
        limit: Int,
    ): List<RegisterSuggestion>

    /** PENDING to CONFIRMED; false when the suggestion was not pending any more. */
    fun markConfirmed(
        suggestionRef: String,
        at: Instant,
    ): Boolean
}

/** Where the previous page of [RegisterSuggestions.list] ended. */
data class SuggestionCursor(
    val suggestedAt: Instant,
    val suggestionRef: String,
)

/** The network-registration item of each entity of the bound tenant. */
interface NetworkRegistrations {
    fun find(entity: String): NetworkRegistration?

    fun save(item: NetworkRegistration)
}

/** The Peppol participant ID of the entity's own registration: the sender ID of the triple in force on [on]. */
fun interface OwnPeppolIds {
    fun of(
        entity: String,
        on: LocalDate,
    ): String?
}
