package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.ElectionPolicyWriterGuard
import com.dynokore.gst.tenancy.adapters.election.GstSchemeEventDecorator
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.UnknownVariationPoint
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.productruntime.ProductEventTypes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID

/** VP-53 (GST schemes the entity applies) written by the shared setPolicy; the decorator publishes the scheme set. */
class SchemeElectionTest {
    private val vp53 = VariationPointKey("VP-53")
    private val prefix = "gstScheme."

    private fun run(block: (ElectionEnv, ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            block(ElectionEnv(env), env)
        }
    }

    /** The scheme keys of the catalogue row VP-53, read from the pinned catalogue (never listed in this test). */
    private fun schemeKeys(env: ProfileEnv): List<String> {
        val row = checkNotNull(env.context.getBean(VariationPointCatalogue::class.java).find(vp53))
        return row.options.map { it.key }.filter { it != NONE }
    }

    private fun select(
        env: ProfileEnv,
        entity: EntityResponse,
        keys: List<String>,
        on: LocalDate,
        key: VariationPointKey = vp53,
    ) = env.inTx {
        env.writer.setPolicy(
            SetPolicyCommand(
                key,
                PolicyScope.entity(env.tenants.current(), entity.entityRef),
                ScopeLevel.ENTITY,
                keys.map { SelectedOption(it) },
                on,
                "scheme test",
                Actor("user:test", setOf("entity.admin")),
                CorrelationId("corr-${UUID.randomUUID()}"),
                CausationId("cause-${UUID.randomUUID()}"),
            ),
        )
    }

    private fun schemeEvents(env: ProfileEnv): List<JsonNode> =
        ElectionEnv(env).profileEvents().filter { event ->
            event["changedAttributes"].values().any { it["attribute"].asString().startsWith(prefix) }
        }

    private fun JsonNode.changes(): Map<String, Triple<String?, String, Unit>> =
        this["changedAttributes"].values().associate {
            it["attribute"].asString() to Triple(it["oldValue"]?.asString(), it["newValue"].asString(), Unit)
        }

    private fun JsonNode.profileSchemes(): List<Triple<String, String, String>> =
        this["node"]["profile"].values().filter { it["attribute"].asString().startsWith(prefix) }.map {
            Triple(it["attribute"].asString(), it["value"].asString(), it["effectiveDate"].asString())
        }

    @Test
    fun `the catalogue row VP-53 is available and the test reads its keys from it`() {
        run { _, env ->
            val row = checkNotNull(env.context.getBean(VariationPointCatalogue::class.java).find(vp53))
            assertThat(row.status).isEqualTo(VpStatus.AVAILABLE)
            assertThat(schemeKeys(env)).hasSize(EXPECTED_SCHEME_KEYS)
            assertThat(row.exclusiveKeys).containsExactly(NONE)
        }
    }

    @Test
    fun `a test list shorter than the 14 non-NONE keys of the row fails the count assertion`() {
        run { _, env ->
            val keys = schemeKeys(env)
            assertThat(keys.drop(1)).hasSize(EXPECTED_SCHEME_KEYS - 1)
            assertThat(runCatching { assertThat(keys.drop(1)).hasSize(EXPECTED_SCHEME_KEYS) }.isFailure).isTrue()
        }
    }

    @Test
    fun `selections publish one ON or OFF entry per changed key and a repeat publishes nothing`() {
        run { _, env ->
            env.withGroup(1) { _, _, entities ->
                val entity = entities.single()
                val (first, second) = schemeKeys(env)
                val day1 = TODAY
                val day2 = TODAY.plusDays(1)
                val day3 = TODAY.plusDays(2)

                // (a) a first selection of two keys
                select(env, entity, listOf(first, second), day1)
                val a = schemeEvents(env).single()
                assertThat(a.changes().mapValues { it.value.first to it.value.second })
                    .containsOnly(
                        org.assertj.core.api.Assertions
                            .entry(prefix + first, null to "ON"),
                        org.assertj.core.api.Assertions
                            .entry(prefix + second, null to "ON"),
                    )
                assertThat(a.profileSchemes())
                    .containsExactlyInAnyOrder(
                        Triple(prefix + first, "ON", day1.toString()),
                        Triple(prefix + second, "ON", day1.toString()),
                    )

                // (b) reduced to one of them: the dropped key goes OFF with oldValue ON, the kept key has no entry
                select(env, entity, listOf(first), day2)
                val b = schemeEvents(env).last()
                assertThat(schemeEvents(env)).hasSize(2)
                assertThat(b.changes().mapValues { it.value.first to it.value.second })
                    .containsOnly(
                        org.assertj.core.api.Assertions
                            .entry(prefix + second, "ON" to "OFF"),
                    )
                assertThat(b.profileSchemes()).contains(Triple(prefix + second, "OFF", day2.toString()))

                // (c) NONE turns the remaining key OFF and there is no entry named gstScheme.NONE
                select(env, entity, listOf(NONE), day3)
                val c = schemeEvents(env).last()
                assertThat(schemeEvents(env)).hasSize(3)
                assertThat(c.changes().mapValues { it.value.first to it.value.second })
                    .containsOnly(
                        org.assertj.core.api.Assertions
                            .entry(prefix + first, "ON" to "OFF"),
                    )

                // (d) the same selection again publishes no event
                select(env, entity, listOf(NONE), day3.plusDays(1))
                assertThat(schemeEvents(env)).hasSize(3)

                // (h) the look-alike attribute names are never produced
                val attributes =
                    env.captured.envelopes
                        .filter { it.type == ProductEventTypes.ENTITY_PROFILE_CHANGED }
                        .flatMap { event ->
                            val tree = JSON.readTree(event.dataJson)
                            tree["changedAttributes"].values().map { it["attribute"].asString() } +
                                tree["node"]["profile"].values().map { it["attribute"].asString() }
                        }
                assertThat(attributes).doesNotContain("gstSchemes", "gstScheme.self_billing", "gstScheme.NONE")
            }
        }
    }

    @Test
    fun `an option key that is not in the catalogue row is refused UNKNOWN_OPTION and nothing is published`() {
        run { _, env ->
            env.withGroup(1) { _, _, entities ->
                val entity = entities.single()
                val before = env.captured.envelopes.size
                val refusal =
                    runCatching { select(env, entity, listOf("self_billing"), TODAY) }.exceptionOrNull()
                        as PolicyRefused
                assertThat(refusal.refusal).isEqualTo(PolicyRefusal.UNKNOWN_OPTION)
                assertThat(env.captured.envelopes).hasSize(before)
                assertThat(schemeEvents(env)).isEmpty()
            }
        }
    }

    @Test
    fun `a setPolicy of the look-alike keys VP-530 vp-53 and VP-5 publishes no scheme event`() {
        run { _, env ->
            env.withGroup(1) { _, _, entities ->
                val entity = entities.single()
                val first = schemeKeys(env).first()
                // VP-530 is a well-formed key the catalogue does not register
                val lookAlike = VariationPointKey("VP-530")
                assertThat(runCatching { select(env, entity, listOf(first), TODAY, lookAlike) }.exceptionOrNull())
                    .isInstanceOf(UnknownVariationPoint::class.java)
                // vp-53 and VP-5 cannot even be built as a variation point key
                listOf("vp-53", "VP-5").forEach { id ->
                    assertThat(runCatching { VariationPointKey(id) }.exceptionOrNull())
                        .describedAs(id)
                        .isInstanceOf(IllegalArgumentException::class.java)
                }
                assertThat(schemeEvents(env)).isEmpty()
            }
        }
    }

    @Test
    fun `the decorator and the guard are both installed on the writer bean once`() {
        run { _, env ->
            val chain =
                generateSequence<Any>(env.writer) { next -> delegateOf(next) }
                    .toList()
            assertThat(chain.count { it is GstSchemeEventDecorator }).isEqualTo(1)
            assertThat(chain.count { it is ElectionPolicyWriterGuard }).isEqualTo(1)
        }
    }

    @Test
    fun `INV-AC-1260 every option of the scheme and over-submission rows works and a planned option is refused`() {
        run { _, env ->
            val catalogue = env.context.getBean(VariationPointCatalogue::class.java)
            env.withGroup(1) { _, _, entities ->
                val entity = entities.single()
                var checked = 0
                listOf("VP-21", "VP-53").forEach { id ->
                    val definition = checkNotNull(catalogue.find(VariationPointKey(id)))
                    assertThat(definition.status).describedAs(id).isEqualTo(VpStatus.AVAILABLE)
                    definition.options.forEachIndexed { index, option ->
                        val on = TODAY.plusDays(index.toLong() + 1)
                        select(env, entity, listOf(option.key), on, definition.key)
                        val resolved =
                            env.inTx {
                                env.reader.resolve(
                                    definition.key,
                                    PolicyScope.entity(env.tenants.current(), entity.entityRef),
                                    on,
                                )
                            }
                        assertThat(resolved.selected.map { it.key })
                            .describedAs("$id ${option.key}")
                            .containsExactly(option.key)
                        checked++
                    }
                }
                assertThat(checked).isEqualTo(2 + EXPECTED_SCHEME_KEYS + 1)
                val planned =
                    com.dynokore.platform.productruntime.catalogue
                        .GstVariationPointCatalogue()
                        .all()
                        .first {
                            it.status == VpStatus.PLANNED && ScopeLevel.ENTITY in it.scopeLevels &&
                                it.options.isNotEmpty()
                        }
                val refusal =
                    runCatching {
                        env.inTx {
                            env.writer.setPolicy(
                                SetPolicyCommand(
                                    planned.key,
                                    PolicyScope.entity(env.tenants.current(), entity.entityRef),
                                    ScopeLevel.ENTITY,
                                    listOf(SelectedOption(planned.options.first().key)),
                                    TODAY,
                                    "planned row test",
                                    Actor("user:test", planned.setBy),
                                    CorrelationId("corr-${UUID.randomUUID()}"),
                                    CausationId("cause-${UUID.randomUUID()}"),
                                ),
                            )
                        }
                    }.exceptionOrNull()
                assertThat((refusal as? PolicyRefused)?.refusal)
                    .describedAs(planned.key.value)
                    .isEqualTo(PolicyRefusal.PLANNED)
            }
        }
    }

    private fun delegateOf(writer: Any): Any? =
        runCatching { ReflectionTestUtils.getField(writer, "delegate") }.getOrNull()

    private companion object {
        const val NONE = "NONE"
        const val EXPECTED_SCHEME_KEYS = 14
        val JSON =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
    }
}
