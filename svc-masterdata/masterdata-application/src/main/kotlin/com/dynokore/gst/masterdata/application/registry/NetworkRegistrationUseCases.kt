package com.dynokore.gst.masterdata.application.registry

import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.counterparty.RecordCommand
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.IdentifierInput
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.gst.masterdata.domain.registry.NetworkRegistration
import com.dynokore.gst.masterdata.domain.registry.RegisteredField
import com.dynokore.gst.masterdata.domain.registry.RegistrationState
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock

/** The item as stored and, when the directory could not be reached, the code MDM-008. */
data class NetworkRegistrationView(
    val item: NetworkRegistration,
    val degradationCode: String?,
)

/** `getNetworkRegistration` (INV-REQ-320): the stored item and no directory call; never confirmed is outstanding. */
class ReadNetworkRegistration(
    private val tx: TransactionRunner,
    private val items: NetworkRegistrations,
) {
    fun handle(entity: String): NetworkRegistration =
        tx.inTransaction { items.find(entity) ?: NetworkRegistration.outstanding(entity) }
}

/**
 * `confirmNetworkRegistration` (INV-REQ-320, INV-AC-9501): looks up the entity's own Peppol ID, bypassing the cache,
 * and stores CONFIRMED with the AP reference only when the directory found it; a not-found answer stores
 * OUTSTANDING; an unreachable directory leaves the stored state and returns it with MDM-008. An entity with no
 * identity triple has no own Peppol ID to look up and stays outstanding. The System never registers the entity:
 * [DirectoryClient] only reads.
 */
class ConfirmNetworkRegistration(
    private val tx: TransactionRunner,
    private val items: NetworkRegistrations,
    private val ownIds: OwnPeppolIds,
    private val client: DirectoryClient,
    private val clock: Clock,
) {
    fun handle(entity: String): NetworkRegistrationView {
        val peppolId = tx.inTransaction { ownIds.of(entity, clock.businessDate()) }
        val stored = tx.inTransaction { items.find(entity) ?: NetworkRegistration.outstanding(entity) }
        val reply = peppolId?.let { client.lookup(it) } ?: return NetworkRegistrationView(stored, null)
        val at = clock.instant()
        val found =
            when (reply) {
                is DirectoryReply.Found -> {
                    NetworkRegistration(entity, RegistrationState.CONFIRMED, reply.apReference, at, at)
                }

                is DirectoryReply.NotFound -> {
                    NetworkRegistration(entity, RegistrationState.OUTSTANDING, null, null, at)
                }

                DirectoryReply.Unreachable -> {
                    null
                }
            }
        // an unreachable directory leaves the stored state as it is and says MDM-008
        found?.let { item -> tx.inTransaction { items.save(item) } }
        return NetworkRegistrationView(found ?: stored, if (found == null) MDM_008 else null)
    }
}

/** `listRegisterSuggestions` and `confirmRegisterSuggestion` (INV-REQ-1290, INV-AC-9496). */
class RegisterSuggestionUseCases(
    private val tx: TransactionRunner,
    private val suggestions: RegisterSuggestions,
    private val parties: CounterpartyRepository,
    private val record: RecordCounterparty,
    private val clock: Clock,
) {
    fun list(
        party: PartyRef?,
        state: SuggestionState?,
        after: SuggestionCursor?,
        limit: Int,
    ): List<RegisterSuggestion> = tx.inTransaction { suggestions.list(party, state, after, limit) }

    /**
     * Applies the register value to the counterparty and confirms the suggestion: the legal name, or an identifier with
     * scheme `GSTN` and provenance `INTERNAL` whose evidence is the lookup record (INV-Q-10173 A). Null when the
     * suggestion is unknown; [SuggestionNotPending] (409) when it is not PENDING, so a repeat is refused;
     * [SuggestionGstnConflict] (409, MDM-007) when the counterparty now holds a GSTN that differs, nothing written.
     */
    fun confirm(suggestionRef: String): RegisterSuggestion? =
        tx.inTransaction {
            val found = suggestions.find(suggestionRef) ?: return@inTransaction null
            if (found.state == SuggestionState.PENDING && conflicts(found)) throw SuggestionGstnConflict(suggestionRef)
            if (found.state != SuggestionState.PENDING || !suggestions.markConfirmed(suggestionRef, clock.instant())) {
                throw SuggestionNotPending(suggestionRef)
            }
            val evidence = found.evidenceRef ?: suggestionRef
            record.handle(RecordCommand(found.entity, found.party, changeOf(found), evidence, WriteOrigin.REGISTER))
            found.copy(state = SuggestionState.CONFIRMED)
        }

    /** A GSTN suggestion conflicts when the counterparty now holds a GSTN that differs from the suggested one. */
    private fun conflicts(found: RegisterSuggestion): Boolean {
        if (found.field != RegisteredField.GSTN) return false
        val content = parties.current(found.party)?.content
        val held = content?.identifiers?.firstOrNull { it.scheme == IdentifierScheme.GSTN }
        return held != null && held.value.trim() != found.registerValue.trim()
    }

    private fun changeOf(found: RegisterSuggestion): CounterpartyChange =
        when (found.field) {
            RegisteredField.REGISTERED_NAME -> {
                CounterpartyChange(emptyList(), found.registerValue, null, null, null)
            }

            RegisteredField.GSTN -> {
                val gstn = IdentifierInput(IdentifierScheme.GSTN, found.registerValue)
                CounterpartyChange(listOf(gstn), null, null, null, null)
            }
        }
}

/** A confirmation refused with 409, with nothing written. */
sealed class SuggestionRefused(
    val suggestionRef: String,
    message: String,
) : RuntimeException(message)

/** The suggestion is not PENDING: refused with 409. */
class SuggestionNotPending(
    suggestionRef: String,
) : SuggestionRefused(suggestionRef, "suggestion $suggestionRef is not pending")

/** The counterparty now holds a GSTN that differs from the suggested one: refused with 409 MDM-007, nothing written. */
class SuggestionGstnConflict(
    suggestionRef: String,
) : SuggestionRefused(suggestionRef, "suggestion $suggestionRef conflicts with the GSTN the counterparty holds")
