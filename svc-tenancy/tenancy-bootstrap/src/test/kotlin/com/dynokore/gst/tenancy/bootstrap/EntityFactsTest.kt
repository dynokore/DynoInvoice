package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.election.ElectionGateDecision
import com.dynokore.gst.tenancy.application.questionnaire.EntityFactStore
import com.dynokore.gst.tenancy.application.questionnaire.QuestionnairePermissions
import com.dynokore.gst.tenancy.contract.model.EntityFacts
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.QuestionnaireAnswersRequest
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The entity-scope extension of answerQuestionnaire and the stored binding of the election port (INV-T-4933, owner
 * answer INV-Q-9696 B; INV-AC-9769), through the real application context over PostgreSQL with forced row-level
 * security. The context keeps the stored binding of `EntityFactSource` (`inv.test.stored-facts=true`), so
 * `ElectionStatusQueries.status`, `ElectionGate.check` and the POSTING_DATE election read the stored row.
 */
class EntityFactsTest {
    private fun run(block: (ElectionEnv, ProfileEnv) -> Unit) {
        BoundaryFixture.runner().withPropertyValues("inv.test.stored-facts=true").run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            block(ElectionEnv(env), env)
        }
    }

    private fun ProfileEnv.facts(
        tenant: TenantId,
        entity: String?,
        facts: EntityFacts?,
        tenantRef: String = tenant.value.toString(),
    ) = allowQuestionnaire(tenants.current()).let {
        tenantsApi.answerQuestionnaire(
            tenantRef,
            UUID.randomUUID(),
            QuestionnaireAnswersRequest("2026.1", mapOf("OQ-1" to "one"), entity, facts),
        )
    }

    private fun ProfileEnv.stored(entity: String) = inTx { context.getBean(EntityFactStore::class.java).find(entity) }

    private fun ProfileEnv.storedBy(entity: String): List<Pair<String, String>> =
        BoundaryFixture.asSuperuser { c ->
            val sql = "select recorded_by, entity_ref from tenancy.entity_fact where entity_ref = ?"
            c.prepareStatement(sql).use { s ->
                s.setString(1, entity)
                s.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toList()
                }
            }
        }

    private fun ElectionEnv.outstanding(entity: EntityResponse) = status(entity).outstanding

    @Test
    fun `INV-AC-9769 the stored facts decide what is outstanding and whether POSTING_DATE may be elected`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                val ref = entity.entityRef
                // before any call: all four are outstanding and POSTING_DATE is refused
                assertThat(e.outstanding(entity))
                    .contains(ElectionKind.PURCHASE_DATE_BASIS, ElectionKind.APPORTIONMENT_MODE)
                assertThat(e.outstanding(entity)).hasSize(4)
                assertThat(e.reason { e.elect(entity, "POSTING_DATE") }).contains("claimsInputTaxOnPostingBasis")
                assertThat(env.stored(ref)).isNull()
                // recordsPurchases false: NOT_REQUIRED for the apportionment mode only
                env.facts(tenant, ref, EntityFacts(recordsPurchases = false))
                val afterPurchases = e.outstanding(entity)
                assertThat(afterPurchases).doesNotContain(ElectionKind.APPORTIONMENT_MODE)
                assertThat(afterPurchases).hasSize(3)
                // hasForeignCurrencyDocuments true: the exchange rate source stays outstanding
                env.facts(tenant, ref, EntityFacts(hasForeignCurrencyDocuments = true))
                assertThat(e.outstanding(entity)).isEqualTo(afterPurchases)
                assertThat(e.outstanding(entity)).contains(ElectionKind.EXCHANGE_RATE_SOURCE)
                // claimsInputTaxOnPostingBasis true: POSTING_DATE can be elected
                env.facts(tenant, ref, EntityFacts(claimsInputTaxOnPostingBasis = true))
                assertThat(e.elect(entity, "POSTING_DATE").body!!.election).isEqualTo("POSTING_DATE")
                assertThat(env.inTenant(tenant) { e.gate.check(ref, TODAY) })
                    .isInstanceOf(ElectionGateDecision.Refused::class.java)
            }
        }
    }

    @Test
    fun `INV-AC-9769 a call carrying only recordsPurchases leaves the stored claimsInputTaxOnPostingBasis unchanged`() {
        run { _, env ->
            env.withEntity { tenant, entity ->
                val ref = entity.entityRef
                env.facts(
                    tenant,
                    ref,
                    EntityFacts(claimsInputTaxOnPostingBasis = true, hasForeignCurrencyDocuments = false),
                )
                env.facts(tenant, ref, EntityFacts(recordsPurchases = true))
                val stored = env.stored(ref)!!
                assertThat(stored.claimsInputTaxOnPostingBasis).isTrue()
                assertThat(stored.hasForeignCurrencyDocuments).isFalse()
                assertThat(stored.recordsPurchases).isTrue()
                env.facts(tenant, ref, EntityFacts(recordsPurchases = false))
                assertThat(env.stored(ref)!!.recordsPurchases).isFalse()
                assertThat(env.storedBy(ref)).hasSize(1)
            }
        }
    }

    @Test
    fun `INV-AC-9769 the facts are recorded with the verified caller`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                e.people.caller = OTHER_ADMIN
                env.facts(tenant, entity.entityRef, EntityFacts(recordsPurchases = true))
                assertThat(env.storedBy(entity.entityRef)).containsExactly(OTHER_ADMIN to entity.entityRef)
            }
        }
    }

    @Test
    fun `INV-AC-9769 an entity of another tenant in the path of this tenant is 404 SRT-005 and stores nothing`() {
        run { e, env ->
            env.withEntity { _, other ->
                env.withEntity { tenant, _ ->
                    val refused = e.refusal { env.facts(tenant, other.entityRef, EntityFacts(recordsPurchases = true)) }
                    assertThat(refused).isEqualTo(404 to "SRT-005")
                    assertThat(env.storedBy(other.entityRef)).isEmpty()
                }
            }
        }
    }

    @Test
    fun `INV-AC-9769 a path tenantRef outside the caller scope is 404 SRT-005 and stores nothing`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                env.withTenant { other ->
                    val refused =
                        e.refusal {
                            env.facts(
                                tenant,
                                entity.entityRef,
                                EntityFacts(recordsPurchases = true),
                                other.value.toString(),
                            )
                        }
                    assertThat(refused).isEqualTo(404 to "SRT-005")
                    assertThat(env.storedBy(entity.entityRef)).isEmpty()
                }
            }
        }
    }

    @Test
    fun `INV-AC-9769 entityFacts without entityRef is 400 SRT-001 and stores nothing`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                val refused = e.refusal { env.facts(tenant, null, EntityFacts(recordsPurchases = true)) }
                assertThat(refused).isEqualTo(400 to "SRT-001")
                assertThat(env.storedBy(entity.entityRef)).isEmpty()
                assertThat(env.stored(entity.entityRef)).isNull()
            }
        }
    }
}
