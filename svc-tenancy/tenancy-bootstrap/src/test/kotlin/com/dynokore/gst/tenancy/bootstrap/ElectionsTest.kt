package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.election.ElectionGateDecision
import com.dynokore.gst.tenancy.application.election.ElectionOutcome
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionRequest
import com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.ElectionStatus
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.gst.tenancy.domain.IssuanceMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The entity elections (INV-REQ-323, INV-REQ-422, INV-REQ-505, INV-REQ-1283 to INV-REQ-1286) through the real context
 * over PostgreSQL with forced row-level security. Test names carry the criterion key.
 */
class ElectionsTest {
    private fun run(block: (ElectionEnv, ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            block(ElectionEnv(env), env)
        }
    }

    private fun rateValue(
        e: ElectionEnv,
        entity: com.dynokore.gst.tenancy.contract.model.EntityResponse,
        on: java.time.LocalDate,
    ) = e
        .status(entity, on)
        .elections
        .first { it.kind == ElectionKind.EXCHANGE_RATE_SOURCE }
        .value

    private val posting = com.dynokore.gst.tenancy.domain.ElectionKeys.POSTING_DATE

    // ---- purchase date basis and apportionment mode ----------------------------------------------------------

    @Test
    fun `INV-AC-9509 the date basis is outstanding until elected and POSTING_DATE needs the input tax fact`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                assertThat(e.status(entity).outstanding).contains(ElectionKind.PURCHASE_DATE_BASIS)
                assertThat(env.inTenant(tenant) { e.gate.check(entity.entityRef, TODAY) })
                    .isInstanceOf(ElectionGateDecision.Refused::class.java)
                // an entity that does not claim input tax on the posting basis: refused, stored election unchanged
                e.facts.set(entity.entityRef, "claimsInputTaxOnPostingBasis", FactAnswer.FALSE)
                val false1 = e.reason { e.elect(entity, posting) }
                assertThat(false1).contains("claimsInputTaxOnPostingBasis").contains("FALSE")
                assertThat(e.history(entity, ElectionKind.PURCHASE_DATE_BASIS)).isEmpty()
                e.facts.set(entity.entityRef, "claimsInputTaxOnPostingBasis", FactAnswer.TRUE)
                assertThat(e.elect(entity, posting).body!!.election).isEqualTo(posting)
                val stored = e.history(entity, ElectionKind.PURCHASE_DATE_BASIS).single()
                assertThat(stored.value).isEqualTo(posting)
                assertThat(stored.effectiveFrom).isEqualTo(TODAY)
                assertThat(stored.setBy).isEqualTo(ADMIN)
                assertThat(e.status(entity).outstanding).doesNotContain(ElectionKind.PURCHASE_DATE_BASIS)
            }
        }
    }

    @Test
    fun `INV-AC-9510 the apportionment mode is elected and a later change is refused for the two-step flow`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                assertThat(e.status(entity).outstanding).contains(ElectionKind.APPORTIONMENT_MODE)
                assertThat(env.inTenant(tenant) { e.gate.check(entity.entityRef, TODAY) })
                    .isInstanceOf(ElectionGateDecision.Refused::class.java)
                e.elect(entity, "CLAIMABLE_PORTION")
                val first = e.history(entity, ElectionKind.APPORTIONMENT_MODE).single()
                assertThat(first.value to first.setBy).isEqualTo("CLAIMABLE_PORTION" to ADMIN)
                assertThat(e.status(entity).outstanding).doesNotContain(ElectionKind.APPORTIONMENT_MODE)
                val audits = e.auditEntries(entity, "VP-23")
                val later = TODAY.plusDays(7)
                assertThat(first.reason).isEqualTo("VP-23 election by $ADMIN")
                val refused = e.reason { e.elect(entity, "ENTIRE_INVOICE", later) }
                assertThat(refused).contains("two-step flow of INV-T-10125")
                assertThat(e.refusal { e.elect(entity, "ENTIRE_INVOICE", later) }!!.first).isEqualTo(422)
                assertThat(e.refusal { e.elect(entity, "ENTIRE_INVOICE", TODAY) }!!.first).isEqualTo(422)
                assertThat(e.history(entity, ElectionKind.APPORTIONMENT_MODE).map { it.value })
                    .containsExactly("CLAIMABLE_PORTION")
                assertThat(e.auditEntries(entity, "VP-23")).isEqualTo(audits)
            }
        }
    }

    @Test
    fun `INV-AC-9514 neither the date basis nor the apportionment mode has a default and a change is refused`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.usualPeople(entity)
                assertThat(e.status(entity).outstanding)
                    .contains(ElectionKind.PURCHASE_DATE_BASIS, ElectionKind.APPORTIONMENT_MODE)
                assertThat(e.history(entity, ElectionKind.PURCHASE_DATE_BASIS)).isEmpty()
                assertThat(e.history(entity, ElectionKind.APPORTIONMENT_MODE)).isEmpty()
                e.elect(entity, "INVOICE_DATE")
                val onlyOne = e.status(entity).outstanding
                assertThat(onlyOne)
                    .contains(ElectionKind.APPORTIONMENT_MODE)
                    .doesNotContain(ElectionKind.PURCHASE_DATE_BASIS)
                e.elect(entity, "CLAIMABLE_PORTION")
                assertThat(e.status(entity).outstanding)
                    .doesNotContain(ElectionKind.PURCHASE_DATE_BASIS, ElectionKind.APPORTIONMENT_MODE)
                val audits = e.auditEntries(entity, "VP-22")
                val later = TODAY.plusDays(2)
                // a change is refused 422 POL-014 whatever the dropped approvedBy field would have named
                e.facts.set(entity.entityRef, "claimsInputTaxOnPostingBasis", FactAnswer.TRUE)
                assertThat(e.refusal { e.elect(entity, posting, later) }!!.first).isEqualTo(422)
                assertThat(e.refusal { e.elect(entity, posting, TODAY) }!!.first).isEqualTo(422)
                assertThat(e.history(entity, ElectionKind.PURCHASE_DATE_BASIS).map { it.value })
                    .containsExactly("INVOICE_DATE")
                assertThat(e.auditEntries(entity, "VP-22")).isEqualTo(audits)
            }
        }
    }

    @Test
    fun `recordElection takes exactly the four keys and every other text is 400 with nothing stored`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.usualPeople(entity)
                val unknown = listOf("CASH_ACCOUNTING", "invoice_date", "INVOICE_DATE ", "RELAY", "", " POSTING_DATE")
                unknown.forEach { key ->
                    assertThat(e.refusal { e.elect(entity, key) }).isEqualTo(400 to "SRT-001")
                }
                assertThat(e.history(entity, ElectionKind.PURCHASE_DATE_BASIS)).isEmpty()
                assertThat(e.history(entity, ElectionKind.APPORTIONMENT_MODE)).isEmpty()
                assertThat(e.history(entity, ElectionKind.ISSUANCE_MODE)).isEmpty()
            }
        }
    }

    @Test
    fun `an election needs entity admin and an entity of another tenant is 404 before the port is asked`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.usualPeople(entity)
                e.people.caller = NOBODY
                assertThat(e.refusal { e.elect(entity, "INVOICE_DATE") }).isEqualTo(403 to "SRT-004")
                e.people.caller = ADMIN
                val asked = e.facts.asked.size
                val other = entity.copy(entityRef = "ENT-OF-ANOTHER-TENANT")
                assertThat(e.refusal { e.elect(other, "POSTING_DATE") }).isEqualTo(404 to "SRT-005")
                assertThat(e.refusal { e.mode(other, SetIssuanceModeRequest.Mode.RELAY) }).isEqualTo(404 to "SRT-005")
                assertThat(e.facts.asked.size).isEqualTo(asked)
            }
        }
    }

    // ---- the entity facts ---------------------------------------------------------------------------------------

    @Test
    fun `the status reports NOT_REQUIRED only for a fact recorded false`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.usualPeople(entity)
                // the default binding: NOT_RECORDED for every fact is OUTSTANDING (fail closed)
                val states = e.status(entity).elections.associate { it.kind to it.status }
                assertThat(states.values.toSet()).containsExactly(ElectionStatus.OUTSTANDING)
                e.facts.set(entity.entityRef, "recordsPurchases", FactAnswer.FALSE)
                val purchases = e.status(entity).elections.associate { it.kind to it.status }
                assertThat(purchases[ElectionKind.APPORTIONMENT_MODE]).isEqualTo(ElectionStatus.NOT_REQUIRED)
                assertThat(purchases[ElectionKind.ISSUANCE_MODE]).isEqualTo(ElectionStatus.OUTSTANDING)
                assertThat(purchases[ElectionKind.PURCHASE_DATE_BASIS]).isEqualTo(ElectionStatus.OUTSTANDING)
                assertThat(purchases[ElectionKind.EXCHANGE_RATE_SOURCE]).isEqualTo(ElectionStatus.OUTSTANDING)
                e.facts.set(entity.entityRef, "recordsPurchases", FactAnswer.TRUE)
                assertThat(
                    e
                        .status(entity)
                        .elections
                        .first { it.kind == ElectionKind.APPORTIONMENT_MODE }
                        .status,
                ).isEqualTo(ElectionStatus.OUTSTANDING)
                // hasForeignCurrencyDocuments FALSE does not change the apportionment mode
                e.facts.set(entity.entityRef, "hasForeignCurrencyDocuments", FactAnswer.FALSE)
                val foreign = e.status(entity).elections.associate { it.kind to it.status }
                assertThat(foreign[ElectionKind.EXCHANGE_RATE_SOURCE]).isEqualTo(ElectionStatus.NOT_REQUIRED)
                assertThat(foreign[ElectionKind.APPORTIONMENT_MODE]).isEqualTo(ElectionStatus.OUTSTANDING)
            }
        }
    }

    @Test
    fun `POSTING_DATE is refused for a fact not recorded and INVOICE_DATE is not affected`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.usualPeople(entity)
                val reason = e.reason { e.elect(entity, posting) }
                assertThat(reason).contains("claimsInputTaxOnPostingBasis").contains("NOT_RECORDED")
                assertThat(e.elect(entity, "INVOICE_DATE").body!!.election).isEqualTo("INVOICE_DATE")
            }
        }
    }

    // ---- exchange-rate source ------------------------------------------------------------------------------------

    private fun packActive(
        e: ElectionEnv,
        tenant: com.dynokore.platform.tenancykernel.TenantId,
    ) {
        e.staged(tenant, "SCOPE-SG", "1.0")
        e.activated(tenant, "SCOPE-SG", "1.0", TODAY.minusDays(30))
    }

    @Test
    fun `INV-AC-9511 the rate source is outstanding, takes the listed source and basis only and refuses another`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                packActive(e, tenant)
                assertThat(e.status(entity).outstanding).contains(ElectionKind.EXCHANGE_RATE_SOURCE)
                assertThat(env.inTenant(tenant) { e.gate.check(entity.entityRef, TODAY) })
                    .isInstanceOf(ElectionGateDecision.Refused::class.java)
                assertThat(e.rate(entity, "MAS", RateSourceElectionRequest.Basis.DAILY).body!!.source).isEqualTo("MAS")
                val stored = e.history(entity, ElectionKind.EXCHANGE_RATE_SOURCE).single()
                assertThat(listOf(stored.value, stored.basis, stored.setBy)).containsExactly("MAS", "DAILY", ADMIN)
                assertThat(e.status(entity).outstanding).doesNotContain(ElectionKind.EXCHANGE_RATE_SOURCE)
                // a source not on the list, and a lower-case look-alike
                listOf("ECB", "mas", "MAS ").forEach { source ->
                    val next = TODAY.plusDays(1)
                    val daily = RateSourceElectionRequest.Basis.DAILY
                    val reason = e.reason { e.rate(entity, source, daily, next) }
                    assertThat(reason).contains("not on the approved list")
                }
                val kept = e.history(entity, ElectionKind.EXCHANGE_RATE_SOURCE).single()
                assertThat(kept.value to kept.basis).isEqualTo("MAS" to "DAILY")
            }
        }
    }

    @Test
    fun `INV-AC-1511 INV-AC-9515 a change of the rate source is refused for the two-step flow`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.staged(tenant, "SCOPE-SG", "1.0", sources = TWO_SOURCES)
                e.activated(tenant, "SCOPE-SG", "1.0", TODAY.minusDays(30))
                e.rate(entity, "MAS", RateSourceElectionRequest.Basis.DAILY, TODAY)
                val audits = e.auditEntries(entity, "VP-24")
                val july = TODAY.plusDays(30)
                val monthly = RateSourceElectionRequest.Basis.MONTHLY_AVERAGE
                val daily = RateSourceElectionRequest.Basis.DAILY
                // another source later, another basis with the same source, another source on the stored date
                assertThat(e.refusal { e.rate(entity, "BNM", monthly, july) }!!.first).isEqualTo(422)
                assertThat(e.refusal { e.rate(entity, "MAS", monthly, july) }!!.first).isEqualTo(422)
                assertThat(e.refusal { e.rate(entity, "BNM", daily, TODAY) }!!.first).isEqualTo(422)
                assertThat(e.reason { e.rate(entity, "BNM", monthly, july) }).contains("two-step flow of INV-T-10125")
                val kept = e.history(entity, ElectionKind.EXCHANGE_RATE_SOURCE).single()
                assertThat(kept.value to kept.basis).isEqualTo("MAS" to "DAILY")
                assertThat(kept.reason).isEqualTo("VP-24 election by $ADMIN")
                assertThat(e.auditEntries(entity, "VP-24")).isEqualTo(audits)
            }
        }
    }

    @Test
    fun `a first election needs no approver and a repeat of the same election stores nothing`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.usualPeople(entity)
                e.elect(entity, "CLAIMABLE_PORTION")
                val audits = e.auditEntries(entity, "VP-23")
                e.elect(entity, "CLAIMABLE_PORTION")
                assertThat(e.auditEntries(entity, "VP-23")).isEqualTo(audits)
                assertThat(e.history(entity, ElectionKind.APPORTIONMENT_MODE)).hasSize(1)
            }
        }
    }
}
