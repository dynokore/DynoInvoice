package com.dynokore.platform.productruntime

import com.dynokore.gst.catalogue.AckCondition
import com.dynokore.gst.catalogue.BoundValue
import com.dynokore.gst.catalogue.TypedValue
import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.catalogue.VpOption
import com.dynokore.platform.configresolver.Acknowledger
import com.dynokore.platform.configresolver.AcknowledgerCondition
import com.dynokore.platform.configresolver.Bound
import com.dynokore.platform.configresolver.CatalogueListingUnavailable
import com.dynokore.platform.configresolver.DeploymentForm
import com.dynokore.platform.configresolver.Strictness
import com.dynokore.platform.configresolver.TypedValueSpec
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.dynokore.gst.catalogue.Acknowledger as GstAcknowledger
import com.dynokore.gst.catalogue.DeploymentForm as GstForm

/** INV-T-2876: the gst-catalogue binding maps every registered row unchanged (INV-Q-2934, INV-Q-3119, INV-Q-3134). */
class CatalogueAdapterTest {
    private val catalogue = GstVariationPointCatalogue("submission-svc")

    @Test
    fun `every catalogue row maps with its scope levels, options, strictness and bounds unchanged`() {
        VariationPoint.entries.size shouldBe catalogue.all().size
        VariationPoint.entries.forEach { vp ->
            val def = requireNotNull(catalogue.find(VariationPointKey(vp.id))) { "${vp.id} is not mapped" }
            def.vpClass.name shouldBe vp.vpClass.name
            def.status.name shouldBe vp.status.name
            def.modifiers.map { it.name }.toSet() shouldBe vp.classModifiers.map { it.name }.toSet()
            def.scopeLevels.map { it.name } shouldContainExactly vp.scopeLevels.orEmpty().map { it.name }
            def.setBy.toList() shouldContainExactly vp.setBy
            def.options.map { it.key } shouldContainExactly vp.optionSpecs.orEmpty().map { it.key }
            def.options.zip(vp.optionSpecs.orEmpty()).forEach { (mapped, source) ->
                mapped.strictness.name shouldBe source.strictness.name
                assertSameTyped(vp.id, mapped.typedValue, source.typedValue)
            }
            def.defaultByForm.mapKeys { it.key.name } shouldBe vp.defaultByForm
            def.mustBeElected shouldBe vp.mustBeElected
            def.multiSelect shouldBe vp.multiSelect
            def.exclusiveKeys.toList() shouldContainExactly vp.exclusiveKeys
            def.formDependence shouldBe vp.formDependence
            def.crossScope shouldBe vp.crossScope
            def.installationBound shouldBe (vp.installationParameter != null || vp.installationParameterRef != null)
            def.installationParameter?.value shouldBe vp.installationParameterRef?.row
        }
    }

    private fun assertSameTyped(
        id: String,
        mapped: TypedValueSpec?,
        source: TypedValue?,
    ) {
        if (source == null) {
            mapped.shouldBeNull()
            return
        }
        val spec = requireNotNull(mapped) { "$id lost a typed value" }
        spec.type.name shouldBe source.type.name
        spec.unit shouldBe source.unit
        assertSameBound(spec.min, source.min)
        assertSameBound(spec.max, source.max)
        assertSameBound(spec.minExclusive, source.minExclusive)
        assertSameBound(spec.length, source.length)
        assertSameBound(spec.default, source.default)
        spec.direction.name shouldBe (source.direction?.name ?: "NONE")
        spec.values shouldBe source.values.orEmpty()
        spec.minItems shouldBe source.minItems
        spec.maxItems shouldBe source.maxItems
        spec.ref shouldBe source.ref
        spec.rule shouldBe source.rule
        spec.requires shouldBe source.requires
        spec.fields.keys shouldBe source.fields.orEmpty().keys
        source.fields.orEmpty().forEach { (name, field) -> assertSameTyped(id, spec.fields[name], field) }
        assertSameTyped(id, spec.item, source.item)
        assertSameTyped(id, spec.key, source.key)
        assertSameTyped(id, spec.value, source.value)
    }

    private fun assertSameBound(
        mapped: Bound?,
        source: BoundValue?,
    ) {
        if (source == null) {
            mapped.shouldBeNull()
            return
        }
        val bound = requireNotNull(mapped)
        bound.number shouldBe source.number
        bound.named shouldBe source.symbolic
        bound.row?.value shouldBe source.row
    }

    @Test
    fun `every LOOSER option maps with its acknowledgers, one per role, and none is left empty`() {
        val looser =
            VariationPoint.entries
                .flatMap { vp -> vp.optionSpecs.orEmpty().map { vp to it } }
                .filter { (_, option) -> option.strictness.name == Strictness.LOOSER.name }
        looser.shouldNotBeEmpty()
        looser.forEach { (vp, option) ->
            val mapped = requireNotNull(catalogue.find(VariationPointKey(vp.id))?.option(option.key))
            val expected = option.acknowledgedBy.sumOf { if (it.rolesByForm.isEmpty()) it.roles.size else 1 }
            val refused = option.acknowledgedBy.any { ack -> ack.rolesByForm.values.any { it.size > 1 } }
            if (refused) {
                mapped.acknowledgers.shouldBeEmpty()
            } else {
                withClue("${vp.id} ${option.key}") { mapped.acknowledgers.shouldNotBeEmpty() }
                mapped.acknowledgers.size shouldBe expected
            }
        }
    }

    @Test
    fun `an acknowledger with several roles becomes one per role with the same condition (INV-Q-3854 ii)`() {
        val option =
            VpOption(
                "RELAXED",
                "relaxed",
                com.dynokore.gst.catalogue.Strictness.LOOSER,
                null,
                listOf(
                    GstAcknowledger(
                        listOf("tax.approver", "finance.controller"),
                        AckCondition.UNDER_DELEGATION,
                        true,
                        emptyMap(),
                    ),
                ),
            )
        GstVariationPointCatalogue.acknowledgers(option) shouldBe
            listOf(
                Acknowledger("tax.approver", AcknowledgerCondition.UNDER_DELEGATION, true),
                Acknowledger("finance.controller", AcknowledgerCondition.UNDER_DELEGATION, true),
            )
    }

    @Test
    fun `a form naming more than one role leaves the option without acknowledgers, so it is refused`() {
        val byForm = mapOf(GstForm.SAAS to listOf("a.role", "b.role"), GstForm.PRIVATE to listOf("c.role"))
        val option =
            VpOption(
                "RELAXED",
                "relaxed",
                com.dynokore.gst.catalogue.Strictness.LOOSER,
                null,
                listOf(GstAcknowledger(emptyList(), AckCondition.ALWAYS, false, byForm)),
            )
        GstVariationPointCatalogue.acknowledgers(option).shouldBeEmpty()
        val single = byForm + (GstForm.SAAS to listOf("a.role"))
        GstVariationPointCatalogue.acknowledgers(
            option.copy(acknowledgedBy = listOf(GstAcknowledger(emptyList(), AckCondition.ALWAYS, false, single))),
        ) shouldBe
            listOf(
                Acknowledger(roleByForm = mapOf(DeploymentForm.SAAS to "a.role", DeploymentForm.PRIVATE to "c.role")),
            )
    }

    @Test
    fun `crossOption carries every constraint record of the row, and is null without one (INV-Q-3854 iii)`() {
        VariationPoint.entries.forEach { vp ->
            val crossOption = catalogue.find(VariationPointKey(vp.id))?.crossOption
            if (vp.constraints.isEmpty()) {
                crossOption.shouldBeNull()
            } else {
                crossOption?.lines() shouldBe
                    vp.constraints.map { "${it.id} ${it.kind.name}: ${it.rule} (refused: ${it.refused})" }
            }
        }
    }

    @Test
    fun `an unregistered key is absent, so config-resolver refuses it`() {
        catalogue.find(VariationPointKey("VP-999")).shouldBeNull()
    }

    @Test
    fun `keys lists the rows the embedding service owns, and cannot list without one`() {
        catalogue.keys().map { it.value } shouldContainExactly
            VariationPoint.entries.filter { it.owner == "submission-svc" }.map { it.id }
        assertThrows<CatalogueListingUnavailable> { GstVariationPointCatalogue().keys() }
    }
}
