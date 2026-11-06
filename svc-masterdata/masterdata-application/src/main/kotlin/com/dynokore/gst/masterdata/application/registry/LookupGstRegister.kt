package com.dynokore.gst.masterdata.application.registry

import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.counterparty.RecordCommand
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyContent
import com.dynokore.gst.masterdata.domain.counterparty.IdentifierInput
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.gst.masterdata.domain.registry.LookupEvidence
import com.dynokore.gst.masterdata.domain.registry.Reconciliation
import com.dynokore.gst.masterdata.domain.registry.RegisterFact
import com.dynokore.gst.masterdata.domain.registry.RegisterPolicy
import com.dynokore.gst.masterdata.domain.registry.RegisteredField
import com.dynokore.gst.masterdata.domain.registry.RegistryKind
import com.dynokore.gst.masterdata.domain.registry.reconcile
import com.dynokore.gst.masterdata.domain.registry.registerGstnToAdd
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.LocalDate

/** The status the register lookup carries when the register could not be reached (INV-REQ-326). */
const val REGISTER_UNAVAILABLE = "REGISTER_UNAVAILABLE"

/** The UEN to look up on behalf of [entity], and the counterparty it belongs to when the caller knows it. */
data class RegisterQuestion(
    val entity: String,
    val uen: String,
    val party: PartyRef? = null,
)

/** The register's answer and what it did to the counterparty record. */
data class RegisterAnswer(
    val status: String,
    val asOf: LocalDate?,
    val reconciliation: Reconciliation,
    val suggestion: RegisterSuggestion?,
    val gstnSuggestion: RegisterSuggestion? = null,
)

/**
 * INV-REQ-477, INV-REQ-801, INV-REQ-802, INV-REQ-1290: looks a UEN up on the Check GST Register. A result inside its
 * lifetime is served from the cache and makes no call; otherwise the register is asked outside any transaction and its
 * answer is cached with its lookup record and evidence. An outage never fails the call: the answer carries
 * `REGISTER_UNAVAILABLE` only (INV-Q-10177 d: no re-check is scheduled here). A difference of the registered name
 * from the counterparty's legal name, or the register's GSTN for a counterparty that holds none, is suggested,
 * applied with provenance or ignored by VP-29; a GSTN that conflicts with the register's (MDM-007) is never
 * overwritten and never suggested.
 */
class LookupGstRegister(
    private val tx: TransactionRunner,
    private val parties: CounterpartyRepository,
    private val registry: RegisterAccess,
    private val suggestions: RegisterSuggestions,
    private val record: RecordCounterparty,
    private val clock: Clock,
) {
    fun handle(question: RegisterQuestion): RegisterAnswer {
        val cached = tx.inTransaction { registry.served(question.entity, question.uen) }
        val outcome = cached?.let { Outcome(it.fact, it.recordKey) } ?: registry.ask(question)
        val fact = outcome.fact ?: return unavailable()
        return tx.inTransaction { reconcile(question, fact, outcome.recordKey) }
    }

    /**
     * The register's fact for [question] and nothing else (INV-REQ-316, MDM-007): the same cache and the same asking as
     * [handle], so a gap report never has a second lookup path, but no suggestion is stored and no counterparty is
     * written. Null when the register could not be reached (`REGISTER_UNAVAILABLE`); the lookup evidence of an asked
     * lookup is kept as for any lookup.
     */
    fun check(question: RegisterQuestion): RegisterFact? {
        val cached = tx.inTransaction { registry.served(question.entity, question.uen) }
        return cached?.fact ?: registry.ask(question).fact
    }

    private fun unavailable() = RegisterAnswer(REGISTER_UNAVAILABLE, null, Reconciliation.NoChange, null)

    private fun reconcile(
        question: RegisterQuestion,
        fact: RegisterFact,
        recordKey: String?,
    ): RegisterAnswer {
        val held = held(question)
        val policy = registry.policy(question.entity)
        val decision = held?.let { reconcile(it.second, fact, policy) } ?: Reconciliation.NoChange
        val party = held?.first
        val suggestion =
            when (decision) {
                is Reconciliation.Suggest -> {
                    suggestions.suggest(
                        draft(
                            question.entity,
                            checkNotNull(party),
                            recordKey,
                            RegisteredField.REGISTERED_NAME,
                            decision.registerName to decision.currentName,
                        ),
                    )
                }

                is Reconciliation.Apply -> {
                    apply(question.entity, checkNotNull(party), decision, recordKey)
                }

                else -> {
                    null
                }
            }
        val gstn =
            held
                ?.takeIf { decision !is Reconciliation.Conflict && policy != RegisterPolicy.OFF }
                ?.let { gstn(question.entity, it, fact, policy, recordKey) }
        return RegisterAnswer(fact.status, fact.asOf, decision, suggestion, gstn)
    }

    /** The GSTN the register holds for a counterparty that has none: suggested, or applied under `AUTO_APPLY`. */
    private fun gstn(
        entity: String,
        held: Pair<PartyRef, CounterpartyContent>,
        fact: RegisterFact,
        policy: RegisterPolicy,
        recordKey: String?,
    ): RegisterSuggestion? {
        val value = registerGstnToAdd(held.second, fact) ?: return null
        val draft = draft(entity, held.first, recordKey, RegisteredField.GSTN, value to null)
        return if (policy == RegisterPolicy.SUGGEST_CONFIRM) {
            suggestions.suggest(draft)
        } else {
            val change =
                CounterpartyChange(listOf(IdentifierInput(IdentifierScheme.GSTN, value)), null, null, null, null)
            record.handle(RecordCommand(entity, held.first, change, recordKey ?: "REGISTER", WriteOrigin.REGISTER))
            suggestions.recordApplied(draft)
        }
    }

    private fun draft(
        entity: String,
        party: PartyRef,
        recordKey: String?,
        field: RegisteredField,
        values: Pair<String, String?>,
    ) = SuggestionDraft(entity, party, field, values.first, values.second, recordKey, clock.instant())

    /** Writes the register name with the provenance of a register write and leaves the record of the applied value. */
    private fun apply(
        entity: String,
        party: PartyRef,
        decision: Reconciliation.Apply,
        recordKey: String?,
    ): RegisterSuggestion {
        val change = CounterpartyChange(emptyList(), decision.registerName, null, null, null)
        record.handle(RecordCommand(entity, party, change, recordKey ?: "REGISTER", WriteOrigin.REGISTER))
        return suggestions.recordApplied(
            draft(
                entity,
                party,
                recordKey,
                RegisteredField.REGISTERED_NAME,
                decision.registerName to decision.currentName,
            ),
        )
    }

    /** The counterparty the lookup is for and its current content: by reference, else by the UEN it holds. */
    private fun held(question: RegisterQuestion): Pair<PartyRef, CounterpartyContent>? {
        val version =
            question.party?.let { parties.current(it) }
                ?: parties.findByIdentifier(IdentifierScheme.UEN, question.uen)
        return version?.let { it.partyRef to it.content }
    }

    /** What asking or serving gave: the fact (null on an outage) and its lookup record. */
    class Outcome(
        val fact: RegisterFact?,
        val recordKey: String?,
    )
}

/** The ports of the register lookup: its cache, connection, policies and evidence log. */
data class RegisterPorts(
    val cache: RegisterCache,
    val client: RegisterClient,
    val policies: RegistryPolicies,
    val evidence: LookupEvidenceLog,
)

/** The cache, the connection, the policies and the evidence of the register lookup. */
class RegisterAccess(
    private val tx: TransactionRunner,
    ports: RegisterPorts,
    private val clock: Clock,
) {
    private val cache = ports.cache
    private val client = ports.client
    private val policies = ports.policies
    private val evidence = ports.evidence

    /** The cached answer when it is still inside its lifetime; call inside a transaction. */
    fun served(
        entity: String,
        uen: String,
    ): CachedRegister? {
        val lifetimes = policies.registerLifetimes(entity, clock.businessDate())
        return cache.find(uen)?.takeIf { lifetimes.serves(it.fact.found, it.lookedUpAt, clock.instant()) }
    }

    fun policy(entity: String) = policies.registerPolicy(entity, clock.businessDate())

    /** Asks the register outside any transaction, then stores the answer. */
    fun ask(question: RegisterQuestion): LookupGstRegister.Outcome {
        val reply = client.lookup(question.uen)
        val at = clock.instant()
        return tx.inTransaction {
            when (reply) {
                is RegisterReply.Answered -> {
                    val lookup = LookupEvidence(RegistryKind.REGISTER, question.uen, reply.raw, at)
                    val key = evidence.record(question.entity, lookup, null)
                    cache.store(CachedRegister(question.uen, reply.fact, at, key))
                    LookupGstRegister.Outcome(reply.fact, key)
                }

                RegisterReply.Unavailable -> {
                    val lookup =
                        LookupEvidence(RegistryKind.REGISTER, question.uen, "{\"outcome\":\"UNAVAILABLE\"}", at)
                    evidence.record(question.entity, lookup, null)
                    LookupGstRegister.Outcome(null, null)
                }
            }
        }
    }
}
