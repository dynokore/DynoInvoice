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
            val row = checkNotNull(env.context.getBean(VariationPointCatalogue::class.java).find(vp21))
            assertThat(row.status).isEqualTo(VpStatus.AVAILABLE)
            val setters = rolesHolding("vp-21.set")
            assertThat(setters).isNotEmpty
            env.withGroup(2) { _, _, own ->
                val profileEventsBefore = profileEvents(env)
                onThenOff(env, own[0], own[1], setters, row.key.value)
                refusals(env, own[0], setters)
                // (g) no EntityProfileChanged event for any of these writes
                assertThat(profileEvents(env)).isEqualTo(profileEventsBefore)
            }
            // the third entity, of another tenant, still reads the default
            env.withGroup(1) { _, _, other -> assertThat(resolved(env, other.single())).isEqualTo("OFF") }
        }
    }

    /** Cases (a) to (d): the default OFF, ON at E1 with one audit entry and one PolicyChanged, then OFF again. */
    private fun onThenOff(
        env: ProfileEnv,
        e1: EntityResponse,
        e2: EntityResponse,
        setters: Set<String>,
        vpId: String,
    ) {
        assertThat(resolved(env, e1)).isEqualTo("OFF")
        assertThat(resolved(env, e2)).isEqualTo("OFF")
        set(env, e1, "ON", setters)
        assertThat(resolved(env, e1)).isEqualTo("ON")
        assertThat(resolved(env, e2)).isEqualTo("OFF")
        assertThat(count(AUDIT_TOPIC, e1, "VP-21")).isEqualTo(1)
        assertThat(count(POLICY_TOPIC, e1, "VP-21")).isEqualTo(1)
        val event = policyChanged(env).last { it["vpId"].asString() == vpId }
        assertThat(event["value"].toString()).contains("ON")
        assertThat(event.propertyNames().toList()).contains("scope", "vpId", "value", "effectiveDate", "source")
        set(env, e1, "OFF", setters)
        assertThat(resolved(env, e1)).isEqualTo("OFF")
    }

    /** Cases (e) and (f): an actor without the permission and unknown option keys are refused, nothing is stored. */
    private fun refusals(
        env: ProfileEnv,
        e1: EntityResponse,
        setters: Set<String>,
    ) {
        val stored = versions(env, e1)
        val audits = count(AUDIT_TOPIC, e1, "VP-21")
        val changes = count(POLICY_TOPIC, e1, "VP-21")
        val outsiders = rolesHolding("vp-76.set") - setters
        assertThat(outsiders).isNotEmpty
        assertThat(outsiders.intersect(setters)).isEmpty()
        listOf(outsiders, emptySet(), setOf("entity.admin "), setOf("Entity.Admin")).forEach { roles ->
            assertThat(refusal { set(env, e1, "ON", roles) })
                .describedAs(roles.toString())
                .isEqualTo(PolicyRefusal.ROLE_NOT_ALLOWED)
            assertThat(versions(env, e1)).isEqualTo(stored)
            assertThat(count(AUDIT_TOPIC, e1, "VP-21")).isEqualTo(audits)
            assertThat(count(POLICY_TOPIC, e1, "VP-21")).isEqualTo(changes)
        }
        listOf("YES", "on").forEach { key ->
            assertThat(refusal { set(env, e1, key, setters) })
                .describedAs(key)
                .isEqualTo(PolicyRefusal.UNKNOWN_OPTION)
        }
        assertThat(versions(env, e1)).isEqualTo(stored)
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
