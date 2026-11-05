package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.http.ApiRefusal
import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.application.registry.LookupEvidenceLog
import com.dynokore.gst.masterdata.application.registry.LookupGstRegister
import com.dynokore.gst.masterdata.application.registry.RegisterAccess
import com.dynokore.gst.masterdata.application.registry.RegisterCache
import com.dynokore.gst.masterdata.application.registry.RegisterClient
import com.dynokore.gst.masterdata.application.registry.RegisterPorts
import com.dynokore.gst.masterdata.application.registry.RegisterQuestion
import com.dynokore.gst.masterdata.application.registry.RegisterSuggestions
import com.dynokore.gst.masterdata.application.registry.RegistryPolicies
import com.dynokore.gst.masterdata.contract.model.RegisterSuggestion
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.registry.CacheLifetimes
import com.dynokore.gst.masterdata.domain.registry.Reconciliation
import com.dynokore.gst.masterdata.domain.registry.RegisterPolicy
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * INV-T-4946: the register lookup suggests, applies or ignores a difference by VP-29, and an MDM-007 conflict is never
 * overwritten or suggested (INV-REQ-1290). The register is a contract stub of the recorded wire shape, because the
 * simulator's fixtures carry no registered name. VP-29 is `planned` in the catalogue, so `PolicyReader` resolves its
 * default (SUGGEST_CONFIRM); the other two options are exercised through the same use case with the policy port
 * answering them (see the merge request, gap G1).
 */
class RegistrySuggestionTest {
    private val gstn = "M2-1234567-0"
    private val registerName = "ACME PTE. LTD."

    private fun registered(
        uen: String,
        name: String,
    ) = "{\"uen\":\"$uen\",\"gstRegistrationNumber\":\"$gstn\",\"status\":\"REGISTERED\",\"registeredName\":\"$name\"}"

    private fun partyNamed(
        env: RegistryEnv,
        uen: String,
        name: String,
        heldGstn: String? = null,
    ): String {
        val party = unique("CTP")
        val identifiers = listOfNotNull("UEN" to uen, heldGstn?.let { "GSTN" to it })
        env.ce.write(party, env.ce.request(*identifiers.toTypedArray(), name = name, address = ADDRESS))
        return party
    }

    private fun legalName(
        env: RegistryEnv,
        entity: String,
        uen: String,
    ) = env.ce
        .resolve(entity, "UEN", uen)
        .counterparty!!
        .legalName

    private fun versions(
        env: RegistryEnv,
        party: String,
    ) = env.ce
        .history(party)
        .items.size

    @Test
    fun `INV-AC-2276 INV-AC-1610 the default suggests the register name and applies it only on confirmation`() =
        RegistryStub().use { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                val uen = unique("U")
                stub.found(uen, registered(uen, registerName))
                val party = partyNamed(env, uen, "Acme Pte Ltd", heldGstn = gstn)
                val before = versions(env, party)
                val answer = env.lookup(entity, uen, party)
                // not applied: the record equals its value before the lookup
                assertThat(versions(env, party)).isEqualTo(before)
                assertThat(legalName(env, entity, uen)).isEqualTo("Acme Pte Ltd")
                // suggested: field REGISTERED_NAME, PENDING, carrying the register name, with the lookup as evidence
                assertThat(answer.reconciliation).isEqualTo(Reconciliation.Suggest(registerName, "Acme Pte Ltd"))
                assertThat(answer.gstnSuggestion).isNull()
                val suggestion = env.suggestionsOf(party).single()
                assertThat(suggestion.registerValue).isEqualTo(registerName)
                assertThat(suggestion.field).isEqualTo(RegisterSuggestion.Field.REGISTERED_NAME)
                assertThat(suggestion.state).isEqualTo(RegisterSuggestion.State.PENDING)
                assertThat(suggestion.currentValue).isEqualTo("Acme Pte Ltd")
                assertThat(env.records("REGISTER")).hasSize(1)
                assertThat(suggestion.evidenceRef).isEqualTo(env.records("REGISTER").single().key)
                assertThat(env.evidenceEvents()).hasSize(1)
                // a second lookup inside the lifetime makes no call and stores no second suggestion
                val gets = stub.gets()
                env.lookup(entity, uen, party)
                assertThat(stub.gets()).isEqualTo(gets)
                assertThat(env.suggestionsOf(party, "PENDING")).hasSize(1)
                // confirmation applies it, and a repeat is refused 409
                assertThat(env.confirm(suggestion.suggestionId).state).isEqualTo(RegisterSuggestion.State.CONFIRMED)
                assertThat(versions(env, party)).isEqualTo(before + 1)
                assertThat(env.versionsNamed(party, registerName)).isEqualTo(1)
                assertThatThrownBy { env.confirm(suggestion.suggestionId) }
                    .isInstanceOfSatisfying(ApiRefusal::class.java) {
                        // INV-Q-10177 c: a suggestion that is not PENDING is 409 SRT-008
                        assertThat(it.code.code).isEqualTo("SRT-008")
                        assertThat(it.code.httpStatus).isEqualTo(409)
                    }
                assertThatThrownBy { env.confirm("RSG-unknown") }
                    .isInstanceOfSatisfying(ApiRefusal::class.java) {
                        assertThat(it.code).isEqualTo(ClosedErrorCode.NOT_FOUND)
                    }
            }
        }

    @Test
    fun `INV-AC-1610 INV-AC-2276 an MDM-007 conflict is never overwritten or suggested and the lookup is evidence`() =
        RegistryStub().use { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                val uen = unique("U")
                stub.found(uen, registered(uen, registerName))
                val party = partyNamed(env, uen, "Acme Pte Ltd", heldGstn = "M2-9999999-9")
                val before = versions(env, party)
                val answer = env.lookup(entity, uen, party)
                assertThat(answer.reconciliation).isEqualTo(Reconciliation.Conflict("M2-9999999-9", gstn))
                assertThat(answer.suggestion).isNull()
                assertThat(versions(env, party)).isEqualTo(before)
                assertThat(legalName(env, entity, uen)).isEqualTo("Acme Pte Ltd")
                assertThat(env.suggestionsOf(party)).isEmpty()
                assertThat(env.records("REGISTER")).hasSize(1)
                assertThat(env.evidenceEvents()).hasSize(1)
            }
        }

    @Test
    fun `INV-AC-2276 the other options apply with a provenance record or do nothing, a conflict stays`() =
        RegistryStub().use { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                val uen = unique("U")
                val conflicting = unique("U")
                stub.found(uen, registered(uen, registerName))
                stub.found(conflicting, registered(conflicting, "OTHER PTE. LTD."))
                val party = partyNamed(env, uen, "Acme Pte Ltd", heldGstn = gstn)
                val clash = partyNamed(env, conflicting, "Other Pte Ltd", heldGstn = "M2-9999999-9")
                val auto = access(env, RegisterPolicy.AUTO_APPLY)
                val off = access(env, RegisterPolicy.OFF)
                // OFF: nothing applied, nothing suggested, the lookup is still evidence
                val ignored = env.ce.inTenant { off.handle(RegisterQuestion(entity, uen, PartyRef(party))) }
                assertThat(ignored.reconciliation).isEqualTo(Reconciliation.NoChange)
                assertThat(legalName(env, entity, uen)).isEqualTo("Acme Pte Ltd")
                assertThat(env.records("REGISTER")).hasSize(1)
                // AUTO_APPLY: the non-conflicting difference is applied and leaves a provenance record
                env.advance(Duration.ofHours(25))
                val applied = env.ce.inTenant { auto.handle(RegisterQuestion(entity, uen, PartyRef(party))) }
                assertThat(applied.reconciliation).isEqualTo(Reconciliation.Apply(registerName, "Acme Pte Ltd"))
                assertThat(env.versionsNamed(party, registerName)).isEqualTo(1)
                val record = checkNotNull(applied.suggestion)
                assertThat(record.state.name).isEqualTo("CONFIRMED")
                assertThat(record.evidenceRef).isEqualTo(env.records("REGISTER").last().key)
                // the conflict under every option: equal to its value before, no suggestion
                val clashBefore = versions(env, clash)
                listOf(auto, off, access(env, RegisterPolicy.SUGGEST_CONFIRM)).forEach { use ->
                    val answer = env.ce.inTenant { use.handle(RegisterQuestion(entity, conflicting, PartyRef(clash))) }
                    assertThat(answer.reconciliation).isInstanceOf(Reconciliation.Conflict::class.java)
                    assertThat(versions(env, clash)).isEqualTo(clashBefore)
                    assertThat(legalName(env, entity, conflicting)).isEqualTo("Other Pte Ltd")
                }
                assertThat(env.suggestionsOf(clash)).isEmpty()
            }
        }

    @Test
    fun `INV-AC-1610 a REGISTERED GSTN is suggested for a counterparty with none and applied only on confirmation`() =
        RegistryStub().use { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                val uen = unique("U")
                // the counterparty's name equals the register's, so the GSTN is the only difference
                stub.found(uen, registered(uen, "Acme Pte Ltd"))
                val party = partyNamed(env, uen, "Acme Pte Ltd")
                val before = versions(env, party)
                val answer = env.lookup(entity, uen, party)
                assertThat(versions(env, party)).isEqualTo(before)
                assertThat(answer.suggestion).isNull()
                val suggestion = env.suggestionsOf(party).single()
                assertThat(suggestion.field).isEqualTo(RegisterSuggestion.Field.GSTN)
                assertThat(suggestion.registerValue).isEqualTo(gstn)
                assertThat(suggestion.state).isEqualTo(RegisterSuggestion.State.PENDING)
                assertThat(suggestion.currentValue).isNull()
                assertThat(suggestion.evidenceRef).isEqualTo(env.records("REGISTER").single().key)
                assertThat(env.confirm(suggestion.suggestionId).state).isEqualTo(RegisterSuggestion.State.CONFIRMED)
                val identifier =
                    env.ce
                        .resolve(entity, "UEN", uen)
                        .counterparty!!
                        .resolvedIdentifiers
                        .single { it.scheme == "GSTN" }
                assertThat(identifier.value).isEqualTo(gstn)
                assertThat(identifier.provenance!!.providedBy.value).isEqualTo("INTERNAL")
                assertThat(identifier.provenance!!.evidenceRef).isEqualTo(suggestion.evidenceRef)
            }
        }

    @Test
    fun `INV-AC-1610 a confirmation after the counterparty gained a different GSTN is refused`() =
        RegistryStub().use { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                val uen = unique("U")
                stub.found(uen, registered(uen, "Acme Pte Ltd"))
                val party = partyNamed(env, uen, "Acme Pte Ltd")
                env.lookup(entity, uen, party)
                val pending = env.suggestionsOf(party).single()
                // the counterparty gains a different GSTN after the suggestion was made
                env.ce.write(
                    party,
                    env.ce.request("UEN" to uen, "GSTN" to "M2-7654321-0", name = "Acme Pte Ltd", address = ADDRESS),
                )
                val before = versions(env, party)
                assertThatThrownBy { env.confirm(pending.suggestionId) }.isInstanceOf(ApiRefusal::class.java)
                assertThat(versions(env, party)).isEqualTo(before)
                assertThat(env.suggestionsOf(party, "PENDING")).hasSize(1)
                // a new lookup for the counterparty that now holds a different GSTN suggests nothing and writes nothing
                env.advance(Duration.ofHours(25))
                val answer = env.lookup(entity, uen, party)
                assertThat(answer.reconciliation).isEqualTo(Reconciliation.Conflict("M2-7654321-0", gstn))
                assertThat(answer.gstnSuggestion).isNull()
                assertThat(versions(env, party)).isEqualTo(before)
            }
        }

    @Test
    fun `INV-AC-2276 AUTO_APPLY writes the name and GSTN and never sets providedBy to REGISTER`() =
        RegistryStub().use { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                val uen = unique("U")
                stub.found(uen, registered(uen, registerName))
                // a counterparty with no identifier at all: the name write must leave providedBy as it was
                val bare = unique("CTP")
                env.ce.write(bare, env.ce.request(name = "Bare Pte Ltd", address = ADDRESS))
                val held =
                    env.ce
                        .resolve(entity, null, null, partyRef = bare)
                        .counterparty!!
                        .providedBy
                val answer =
                    env.ce.inTenant {
                        access(env, RegisterPolicy.AUTO_APPLY).handle(RegisterQuestion(entity, uen, PartyRef(bare)))
                    }
                assertThat(answer.reconciliation).isInstanceOf(Reconciliation.Apply::class.java)
                val after = env.ce.resolve(entity, null, null, partyRef = bare).counterparty!!
                assertThat(after.legalName).isEqualTo(registerName)
                assertThat(after.providedBy).isEqualTo(held)
                assertThat(after.providedBy.value).isNotEqualTo("REGISTER")
                // the GSTN the counterparty did not hold is applied with provenance INTERNAL and the lookup as evidence
                val gstnIdentifier = after.resolvedIdentifiers.single { it.scheme == "GSTN" }
                assertThat(gstnIdentifier.value).isEqualTo(gstn)
                assertThat(gstnIdentifier.provenance!!.providedBy.value).isEqualTo("INTERNAL")
                assertThat(gstnIdentifier.provenance!!.evidenceRef).isEqualTo(env.records("REGISTER").last().key)
            }
        }

    /** The use case of the context with the VP-29 answer replaced; everything else is the context's own. */
    private fun access(
        env: RegistryEnv,
        policy: RegisterPolicy,
    ): LookupGstRegister {
        val c = env.context
        val policies =
            object : RegistryPolicies {
                override fun directoryLifetimes(
                    entity: String,
                    on: LocalDate,
                ) = CacheLifetimes.DEFAULT

                override fun registerLifetimes(
                    entity: String,
                    on: LocalDate,
                ) = CacheLifetimes.DEFAULT

                override fun registerPolicy(
                    entity: String,
                    on: LocalDate,
                ) = policy
            }
        val tx = c.getBean(TransactionRunner::class.java)
        val clock = c.getBean(Clock::class.java)
        val registry =
            RegisterAccess(
                tx,
                RegisterPorts(
                    c.getBean(RegisterCache::class.java),
                    c.getBean(RegisterClient::class.java),
                    policies,
                    c.getBean(LookupEvidenceLog::class.java),
                ),
                clock,
            )
        return LookupGstRegister(
            tx,
            c.getBean(CounterpartyRepository::class.java),
            registry,
            c.getBean(RegisterSuggestions::class.java),
            c.getBean(RecordCounterparty::class.java),
            clock,
        )
    }
}
