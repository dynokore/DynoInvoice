package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.GroupResponse
import com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * INV-AC-21460: the purchaseDateBasis entry is carried in the profile every publisher builds (the ProfileExtras bean)
 * and in the replay of EntityProfileChanged; all values are compared by equality.
 */
class PurchaseDateBasisReplayTest {
    private val json = JsonMapper.builder().build()
    private val attribute = "purchaseDateBasis"

    private class Seed(
        val t1: TenantId,
        val t2: TenantId,
        val e1: EntityResponse,
        val e2: EntityResponse,
        val e3: EntityResponse,
        val g1: GroupResponse,
    )

    private fun exempt(from: String) =
        SetEntityStatusRequest(
            SetEntityStatusRequest.StatusKind.PARTIAL_EXEMPTION,
            day(from),
            partiallyExempt = true,
            basis = "partial exemption review",
        )

    private fun run(block: (ProfileEnv, Seed) -> Unit) {
        // the writer's business date is 2026-01-01, so both election dates of the criterion are writable (not back-dated)
        BoundaryFixture.runner().withBean(Clock::class.java, { Clock.fixed(Instant.parse("2026-01-01T01:00:00Z"), ZoneOffset.UTC) }).run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            val store = context.getBean(ElectionPolicyStore::class.java)

            fun elect(
                tenant: TenantId,
                group: GroupResponse,
                entity: EntityResponse,
                value: String,
                from: String,
            ) = env.inTenant(tenant) {
                env.inTx {
                    store.write(
                        entity.entityRef,
                        group.groupRef,
                        ElectionKind.PURCHASE_DATE_BASIS,
                        value,
                        null,
                        day(from),
                        "identity:USR-001",
                        "seed",
                    )
                }
            }
            val (t2, e3) =
                env.withTenant { tenant ->
                    val group = env.group(tenant)
                    val entity = env.entity(tenant, group)
                    env.set(entity, exempt("2026-03-01"))
                    elect(tenant, group, entity, "INVOICE_DATE", "2026-02-01")
                    tenant to entity
                }
            env.withTenant { t1 ->
                val g1 = env.group(t1)
                val e1 = env.entity(t1, g1)
                val e2 = env.entity(t1, g1)
                env.set(e1, exempt("2026-03-01"))
                env.set(e2, exempt("2026-03-01"))
                elect(t1, g1, e1, "INVOICE_DATE", "2026-01-01")
                elect(t1, g1, e1, "POSTING_DATE", "2026-07-01")
                block(env, Seed(t1, t2, e1, e2, e3, g1))
            }
        }
    }

    private fun states(
        env: ProfileEnv,
        tenant: TenantId,
    ): Map<String, JsonNode> {
        val source =
            env.context
                .getBeansOfType(ReplayStateSource::class.java)
                .values
                .single { it.eventType == "com.dynokore.gst.tenancy.EntityProfileChanged.v1" }
        val out = LinkedHashMap<String, JsonNode>()
        var cursor: String? = null
        do {
            val page = env.inTenant(tenant) { env.inTx { source.page(null, cursor, 1) } }
            page.items.forEach { out[it.aggregateKey] = json.readTree(it.dataJson) }
            cursor = page.next
        } while (cursor != null)
        return out
    }

    private fun basisOf(profile: JsonNode): List<Triple<String, String, String>> =
        profile
            .toList()
            .filter { it["attribute"].asString() == attribute }
            .map { Triple(it["attribute"].asString(), it["value"].asString(), it["effectiveDate"].asString()) }

    @Test
    fun `INV-AC-21460 R1 the replay for tenant T1 carries both entries of E-001 and none for E-002`() {
        run { env, s ->
            val states = states(env, s.t1)
            assertThat(states.keys).containsExactlyInAnyOrder(s.e1.entityRef, s.e2.entityRef)
            val e1 = states.getValue(s.e1.entityRef)
            val expected =
                listOf(
                    Triple(attribute, "INVOICE_DATE", "2026-01-01"),
                    Triple(attribute, "POSTING_DATE", "2026-07-01"),
                )
            assertThat(basisOf(e1["node"]["profile"])).isEqualTo(expected)
            assertThat(
                e1["changedAttributes"]
                    .toList()
                    .filter { it["attribute"].asString() == attribute }
                    .map { it["attribute"].asString() to it["newValue"].asString() },
            ).isEqualTo(listOf(attribute to "INVOICE_DATE", attribute to "POSTING_DATE"))
            assertThat(basisOf(states.getValue(s.e2.entityRef)["node"]["profile"])).isEmpty()
            assertThat(
                states
                    .getValue(s.e2.entityRef)["changedAttributes"]
                    .toList()
                    .map { it["attribute"].asString() },
            ).doesNotContain(attribute)
            // the other families are unchanged: the status entry of E-001 is still carried
            assertThat(
                e1["node"]["profile"]
                    .toList()
                    .filter { it["attribute"].asString() != attribute }
                    .map { it["effectiveDate"].asString() },
            ).isEqualTo(listOf("2026-03-01"))
            assertThat(states.containsKey(s.e3.entityRef)).isFalse()
        }
    }

    @Test
    fun `INV-AC-21460 R2 the replay for tenant T2 carries exactly the one entry of E-003 and nothing of E-001`() {
        run { env, s ->
            val states = states(env, s.t2)
            assertThat(states.keys).containsExactly(s.e3.entityRef)
            assertThat(basisOf(states.getValue(s.e3.entityRef)["node"]["profile"]))
                .isEqualTo(listOf(Triple(attribute, "INVOICE_DATE", "2026-02-01")))
            assertThat(states.containsKey(s.e1.entityRef)).isFalse()
        }
    }

    @Test
    fun `INV-AC-21460 R3 the lifecycle publish of E-001 carries both entries in node profile`() {
        run { env, s ->
            env.captured.envelopes.clear()
            env.set(s.e1, exempt("2026-05-01"))
            val published =
                env.captured.envelopes
                    .filter { it.type == ProductEventTypes.ENTITY_PROFILE_CHANGED }
                    .map { json.readTree(it.dataJson) }
                    .single { it["entityRef"].asString() == s.e1.entityRef }
            assertThat(basisOf(published["node"]["profile"])).isEqualTo(
                listOf(
                    Triple(attribute, "INVOICE_DATE", "2026-01-01"),
                    Triple(attribute, "POSTING_DATE", "2026-07-01"),
                ),
            )
        }
    }
}
