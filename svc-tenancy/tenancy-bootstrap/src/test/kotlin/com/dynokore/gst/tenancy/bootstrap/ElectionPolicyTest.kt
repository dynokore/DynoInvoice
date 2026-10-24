package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.ElectionPolicyWriterGuard
import com.dynokore.gst.tenancy.adapters.election.ElectionWriteMarker
import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.DeploymentForm
import com.dynokore.platform.configresolver.OptionSpec
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.Strictness
import com.dynokore.platform.configresolver.UnknownVariationPoint
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointDefinition
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.test.util.ReflectionTestUtils
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** The shared setPolicy against the election variation points, the rounding basis VP-76, and the catalogue rows. */
class ElectionPolicyTest {
    private fun run(block: (ElectionEnv, ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            block(ElectionEnv(env), env)
        }
    }

    private fun refusalOf(block: () -> Unit): PolicyRefusal? =
        try {
            block()
            null
        } catch (refused: PolicyRefused) {
            refused.refusal
        }

    @Test
    fun `the shared setPolicy is refused for VP-22 VP-23 VP-24 and VP-25 with nothing stored and no audit entry`() {
        run { e, env ->
            env.withGroup(1) { _, group, entities ->
                val entity = entities.single()
                e.usualPeople(entity)
                val attempts =
                    mapOf(
                        "VP-22" to ("INVOICE_DATE" to null),
                        "VP-23" to ("CLAIMABLE_PORTION" to null),
                        "VP-24" to ("APPROVED_SOURCE" to "{\"source\":\"MAS\",\"basis\":\"DAILY\"}"),
                        "VP-25" to ("RELAY" to null),
                    )
                val audits = attempts.keys.associateWith { e.auditEntries(entity, it) }
                attempts.forEach { (vp, option) ->
                    val refusal =
                        refusalOf {
                            env.setPolicy(
                                vp,
                                ScopeLevel.ENTITY,
                                group,
                                entity,
                                option.first,
                                option.second,
                                "entity.admin",
                            )
                        }
                    assertThat(refusal).describedAs(vp).isEqualTo(PolicyRefusal.CONSTRAINT_REFUSED)
                    assertThat(e.auditEntries(entity, vp)).describedAs(vp).isEqualTo(audits.getValue(vp))
                }
                ElectionKind.entries.forEach { assertThat(e.history(entity, it)).isEmpty() }
            }
        }
    }

    @Test
    fun `the marker is for the key of the running election write only and is gone when the write ends`() {
        run { e, env ->
            env.withGroup(1) { _, group, entities ->
                val entity = entities.single()
                e.usualPeople(entity)

                fun vp23() =
                    env.setPolicy("VP-23", ScopeLevel.ENTITY, group, entity, "CLAIMABLE_PORTION", null, "entity.admin")
                // a marker set for VP-22 does not let VP-23 through
                assertThat(refusalOf { ElectionWriteMarker.allowing(VariationPointKey("VP-22")) { vp23() } })
                    .isEqualTo(PolicyRefusal.CONSTRAINT_REFUSED)
                // after an election operation finished
                e.elect(entity, "CLAIMABLE_PORTION")
                assertThat(refusalOf { vp23() }).isEqualTo(PolicyRefusal.CONSTRAINT_REFUSED)
                // and after one that threw
                runCatching { ElectionWriteMarker.allowing(VariationPointKey("VP-23")) { error("write failed") } }
                assertThat(ElectionWriteMarker.allows(VariationPointKey("VP-23"))).isFalse()
                assertThat(refusalOf { vp23() }).isEqualTo(PolicyRefusal.CONSTRAINT_REFUSED)
                assertThat(e.history(entity, ElectionKind.APPORTIONMENT_MODE).map { it.setBy }).containsExactly(ADMIN)
            }
        }
    }

    @Test
    fun `VP-76 passes to the writer and look-alike keys are answered by the writer or never reach the guard`() {
        run { e, env ->
            env.withGroup(1) { _, group, entities ->
                val entity = entities.single()
                e.usualPeople(entity)
                // VP-76 reaches the writer: refused for its role, not by the guard's constraint refusal
                assertThat(
                    refusalOf {
                        env.setPolicy(
                            "VP-76",
                            ScopeLevel.ENTITY,
                            group,
                            entity,
                            "PER_LINE",
                            null,
                            "entity.admin",
                        )
                    },
                ).isEqualTo(PolicyRefusal.ROLE_NOT_ALLOWED)
                // VP-230 is not an election key: the writer answers it as an unknown variation point
                assertThat(
                    runCatching { env.setPolicy("VP-230", ScopeLevel.ENTITY, group, entity, "X", null, "entity.admin") }
                        .exceptionOrNull(),
                ).isInstanceOf(UnknownVariationPoint::class.java)
                // vp-23 is not a variation point key at all
                assertThat(runCatching { VariationPointKey("vp-23") }.exceptionOrNull())
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        }
    }

    @Test
    fun `the writer held by the policy command servlet and by the election store is the guard`() {
        // the end-user principal is the one port the context runner leaves unbound; the servlet needs it to mount
        BoundaryFixture
            .runner()
            .withBean(
                EndUserPrincipalResolver::class.java,
                { EndUserPrincipalResolver { null } },
            ).run { context ->
                context.startupFailure?.let { throw it }
                val env = ProfileEnv(context)
                assertThat(env.writer).isInstanceOf(ElectionPolicyWriterGuard::class.java)
                val servlets =
                    env.context
                        .getBeansOfType(ServletRegistrationBean::class.java)
                        .values
                        .mapNotNull { it.servlet }
                        .filter { it.javaClass.simpleName == "PolicyCommandServlet" }
                assertThat(servlets).describedAs("PolicyCommandServlet registration").hasSize(1)
                servlets.forEach { servlet ->
                    assertThat(
                        ReflectionTestUtils.getField(servlet, "writer"),
                    ).isInstanceOf(ElectionPolicyWriterGuard::class.java)
                }
                val store = env.context.getBean(ElectionPolicyStore::class.java)
                assertThat(
                    ReflectionTestUtils.getField(store, "writer"),
                ).isInstanceOf(ElectionPolicyWriterGuard::class.java)
            }
    }

    @Test
    fun `INV-AC-2769 the rounding basis defaults to two decimals HALF_UP per invoice and is read as one option`() {
        run { _, env ->
            env.withGroup(1) { tenant, group, entities ->
                val entity = entities.single()
                val scope = PolicyScope.entity(tenant, entity.entityRef, group = group.groupRef)
                val vp76 = VariationPointKey("VP-76")

                fun read() = env.inTx { env.reader.resolve(vp76, scope, TODAY) }.selected.single()

                fun set(
                    option: String,
                    json: String? = null,
                    role: String = "tax.approver",
                ) = env.setPolicy("VP-76", ScopeLevel.ENTITY, group, entity, option, json, role)
                assertThat(read().key).isEqualTo("TWO_DP_HALF_UP_PER_INVOICE")
                // an entity with the basis per line, summed after rounding
                set("PER_LINE")
                assertThat(read().key).isEqualTo("PER_LINE")
                // a different scale, mode and level are read together as the same triple
                set("CUSTOM", "{\"scale\":3,\"mode\":\"HALF_EVEN\",\"level\":\"LINE\"}")
                val custom = read()
                val triple = JsonMapper.builder().build().readTree(custom.valueJson)
                assertThat(custom.key).isEqualTo("CUSTOM")
                assertThat(triple["scale"].asInt()).isEqualTo(3)
                assertThat(triple["mode"].asString()).isEqualTo("HALF_EVEN")
                assertThat(triple["level"].asString()).isEqualTo("LINE")
                // scale 5 is out of bounds; entity.admin is not the setting role
                val tooFine = "{\"scale\":5,\"mode\":\"HALF_UP\",\"level\":\"INVOICE\"}"
                assertThat(refusalOf { set("CUSTOM", tooFine) }).isEqualTo(PolicyRefusal.OUT_OF_BOUNDS)
                assertThat(
                    refusalOf { set("PER_LINE", role = "entity.admin") },
                ).isEqualTo(PolicyRefusal.ROLE_NOT_ALLOWED)
            }
        }
    }

    // ---- the catalogue rows (INV-AC-1260) -----------------------------------------------------------------------

    private fun scopeOf(
        tenant: TenantId,
        entityRef: String,
        level: ScopeLevel,
    ) = if (level == ScopeLevel.CLIENT_ENTITY) {
        PolicyScope.entity(tenant, entityRef, clientEntity = "CLIENT-1")
    } else {
        PolicyScope.entity(tenant, entityRef)
    }

    private fun writeShared(
        env: ProfileEnv,
        definition: VariationPointDefinition,
        option: String,
        scope: PolicyScope,
    ) = env.inTx {
        env.writer.setPolicy(
            SetPolicyCommand(
                definition.key,
                scope,
                definition.scopeLevels.single(),
                listOf(SelectedOption(option)),
                TODAY,
                "catalogue row test",
                Actor("user:test", definition.setBy),
                CorrelationId("corr-${UUID.randomUUID()}"),
                CausationId("cause-${UUID.randomUUID()}"),
            ),
        )
    }

    private fun kindOf(id: String) = ElectionKind.entries.single { it.variationPoint == id }

    private fun electedOn(
        id: String,
        option: String,
    ) = TODAY.plusDays((kindOf(id).ordinal + 1).toLong() * 3 + option.length)

    private fun elect(
        e: ElectionEnv,
        entity: EntityResponse,
        id: String,
        option: String,
    ) {
        val on = electedOn(id, option)
        if (id == "VP-25") {
            e.mode(entity, SetIssuanceModeRequest.Mode.valueOf(option), on)
        } else {
            e.facts.set(entity.entityRef, "claimsInputTaxOnPostingBasis", FactAnswer.TRUE)
            e.elect(entity, option, on, if (e.history(entity, kindOf(id)).isEmpty()) null else APPROVER)
        }
    }

    private class Row(
        val e: ElectionEnv,
        val env: ProfileEnv,
        val tenant: TenantId,
        val entity: EntityResponse,
    )

    private fun checkOption(
        row: Row,
        definition: VariationPointDefinition,
        option: OptionSpec,
        scope: PolicyScope,
    ) {
        val id = definition.key.value
        val owned = id in setOf("VP-25", "VP-22", "VP-23")
        val refusal =
            if (owned && definition.status == VpStatus.AVAILABLE) {
                elect(row.e, row.entity, id, option.key)
                assertThat(row.e.history(row.entity, kindOf(id)).map { it.value }).contains(option.key)
                null
            } else {
                refusalOf { writeShared(row.env, definition, option.key, scope) }
            }
        when {
            definition.status == VpStatus.PLANNED -> {
                assertThat(refusal).describedAs(id).isNotNull()
            }

            option.strictness == Strictness.LOOSER -> {
                assertThat(refusal).describedAs(id).isEqualTo(PolicyRefusal.RELAXATION_REQUIRED)
            }

            refusal == null && !owned -> {
                val resolved = row.env.inTx { row.env.reader.resolve(definition.key, scope, TODAY) }
                assertThat(resolved.selected.single().key).describedAs(id).isEqualTo(option.key)
            }

            else -> {
                assertThat(refusal ?: PolicyRefusal.CONSTRAINT_REFUSED).isEqualTo(PolicyRefusal.CONSTRAINT_REFUSED)
            }
        }
    }

    @Test
    fun `INV-AC-1260 every option of VP-25 VP-22 VP-23 VP-13 VP-14 and VP-19 yields its documented behaviour`() {
        run { e, env ->
            val catalogue = env.context.getBean(VariationPointCatalogue::class.java)
            env.withEntity { tenant, entity ->
                e.usualPeople(entity)
                e.routeChanged(tenant, "AP-1", listOf(entity.entityRef), "POLLING", 1)
                var checked = 0
                listOf("VP-25", "VP-22", "VP-23", "VP-13", "VP-14", "VP-19").forEach { id ->
                    val definition =
                        checkNotNull(catalogue.find(VariationPointKey(id))) { "$id is not in the catalogue" }
                    val scope = scopeOf(tenant, entity.entityRef, definition.scopeLevels.single())
                    definition.options.forEach { option ->
                        checkOption(Row(e, env, tenant, entity), definition, option, scope)
                        checked++
                    }
                }
                assertThat(checked).isGreaterThanOrEqualTo(12)
                assertThat(DeploymentForm.entries).isNotEmpty()
            }
        }
    }
}
