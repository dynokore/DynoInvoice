package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.productruntime.ProductEventTypes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** INV-AC-12496: the ON election of VP-21 written by the shared setPolicy against the real config-resolver. */
class OverSubmissionElectionTest {
    private val vp21 = VariationPointKey("VP-21")
    private val mapper = JsonMapper.builder().build()

    private fun run(block: (ElectionEnv, ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            block(ElectionEnv(env), env)
        }
    }

    /** The roles that hold [permission] in the pinned role-permission cells, in any deployment form. */
    private fun rolesHolding(permission: String): Set<String> =
        DeploymentForm.entries
            .flatMap { form ->
                RolePermissions.cells(form).filterValues { it[permission] != null }.keys
            }.toSet()

    private fun set(
        env: ProfileEnv,
        entity: EntityResponse,
        option: String,
        roles: Set<String>,
    ) = env.inTx {
        env.writer.setPolicy(
            SetPolicyCommand(
                vp21,
                PolicyScope.entity(env.tenants.current(), entity.entityRef),
                ScopeLevel.ENTITY,
                listOf(SelectedOption(option)),
                TODAY,
                "over-submission test",
                Actor("user:test", roles),
                CorrelationId("corr-${UUID.randomUUID()}"),
                CausationId("cause-${UUID.randomUUID()}"),
            ),
        )
    }

    private fun resolved(
        env: ProfileEnv,
        entity: EntityResponse,
    ) = env
        .inTx {
            env.reader.resolve(vp21, PolicyScope.entity(env.tenants.current(), entity.entityRef), TODAY)
        }.selected
        .single()
        .key

    private fun count(
        topic: String,
        entity: EntityResponse,
        fragment: String,
    ): Long =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select count(*) from tenancy.outbox where topic = ? and data::text like ? and data::text like ?",
                ).use { s ->
                    s.setString(1, topic)
                    s.setString(2, "%${entity.entityRef}%")
                    s.setString(3, "%$fragment%")
                    s.executeQuery().use {
                        it.next()
                        it.getLong(1)
                    }
                }
        }

    private fun versions(
        env: ProfileEnv,
        entity: EntityResponse,
    ) = env
        .inTx {
            env.context
                .getBean(com.dynokore.platform.configresolver.PolicyHistory::class.java)
                .versions(vp21, PolicyScope.entity(env.tenants.current(), entity.entityRef).target(ScopeLevel.ENTITY))
        }.size

    private fun refusal(block: () -> Unit): PolicyRefusal? =
        (runCatching(block).exceptionOrNull() as? PolicyRefused)?.refusal

    private fun profileEvents(env: ProfileEnv): Int =
        env.captured.envelopes.count { it.type == ProductEventTypes.ENTITY_PROFILE_CHANGED }

    private fun policyChanged(env: ProfileEnv): List<JsonNode> =
        env.captured.envelopes
            .filter { it.type.endsWith("PolicyChanged.v1") }
            .map { mapper.readTree(it.dataJson) }

    @Test
    fun `INV-AC-12496 VP-21 ON for one entity reads ON there and OFF elsewhere with one audit entry and one event`() {
        run { _, env ->
            val catalogue = env.context.getBean(VariationPointCatalogue::class.java)
            val row = checkNotNull(catalogue.find(vp21))
            assertThat(row.status).isEqualTo(VpStatus.AVAILABLE)
            val setters = rolesHolding("vp-21.set")
            assertThat(setters).isNotEmpty
            val entities = mutableListOf<EntityResponse>()
            env.withGroup(2) { _, _, own ->
                entities += own
                val e1 = own[0]
                val e2 = own[1]
                // (a) nothing set: the default OFF
                assertThat(resolved(env, e1)).isEqualTo("OFF")
                assertThat(resolved(env, e2)).isEqualTo("OFF")
                val profileEventsBefore = profileEvents(env)

                // (b) ON at E1 by an actor holding vp-21.set
                set(env, e1, "ON", setters)
                assertThat(resolved(env, e1)).isEqualTo("ON")
                assertThat(resolved(env, e2)).isEqualTo("OFF")
                // (c) one audit entry and one PolicyChanged outbox row for VP-21 of E1
                assertThat(count(AUDIT_TOPIC, e1, "VP-21")).isEqualTo(1)
                assertThat(count(POLICY_TOPIC, e1, "VP-21")).isEqualTo(1)
                val event = policyChanged(env).last { it["vpId"].asString() == row.key.value }
                assertThat(event["value"].toString()).contains("ON")
                assertThat(event.propertyNames().toList()).contains("scope", "vpId", "value", "effectiveDate", "source")

                // (d) OFF returns E1 to OFF
                set(env, e1, "OFF", setters)
                assertThat(resolved(env, e1)).isEqualTo("OFF")

                // (e) an actor without a role that holds vp-21.set is refused and nothing is stored
                val stored = versions(env, e1)
                val outsiders = rolesHolding("vp-76.set") - setters
                assertThat(outsiders).isNotEmpty
                assertThat(refusal { set(env, e1, "ON", outsiders) }).isEqualTo(PolicyRefusal.ROLE_NOT_ALLOWED)
                assertThat(versions(env, e1)).isEqualTo(stored)

                // (f) the option keys YES and on are refused UNKNOWN_OPTION and nothing is stored
                listOf("YES", "on").forEach { key ->
                    assertThat(refusal { set(env, e1, key, setters) })
                        .describedAs(key)
                        .isEqualTo(PolicyRefusal.UNKNOWN_OPTION)
                }
                assertThat(versions(env, e1)).isEqualTo(stored)

                // (g) no EntityProfileChanged event for any of these writes
                assertThat(profileEvents(env)).isEqualTo(profileEventsBefore)
            }
            // the third entity, of another tenant, still reads the default
            env.withGroup(1) { _, _, other -> assertThat(resolved(env, other.single())).isEqualTo("OFF") }
        }
    }

    @Test
    fun `INV-AC-12496 a rolled back transaction leaves no version no audit entry and no PolicyChanged`() {
        run { _, env ->
            val setters = rolesHolding("vp-21.set")
            env.withGroup(1) { _, _, own ->
                val e1 = own.single()
                runCatching {
                    env.inTx {
                        set(env, e1, "ON", setters)
                        error("roll back")
                    }
                }
                assertThat(versions(env, e1)).isZero()
                assertThat(count(AUDIT_TOPIC, e1, "VP-21")).isZero()
                assertThat(count(POLICY_TOPIC, e1, "VP-21")).isZero()
                assertThat(resolved(env, e1)).isEqualTo("OFF")
            }
        }
    }

    private companion object {
        const val AUDIT_TOPIC = "gst.platform.audit-entry-recorded.v1"
        const val POLICY_TOPIC = "gst.platform.policy-changed.v1"
    }
}
