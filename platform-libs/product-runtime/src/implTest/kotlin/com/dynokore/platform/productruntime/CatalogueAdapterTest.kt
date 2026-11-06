package com.dynokore.platform.productruntime

import com.dynokore.gst.catalogue.AckCondition
import com.dynokore.gst.catalogue.BoundValue
import com.dynokore.gst.catalogue.OperationRoles
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
import com.dynokore.platform.configresolver.VariationPointDefinition
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.acknowledgePermissionKey
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
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
            def.setPermission shouldBe vp.setPermission
            withClue("${vp.id} setPermission is its id lower-cased with .set") {
                def.setPermission shouldBe vp.id.lowercase() + ".set"
            }
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

    @Test
    fun `setPermission of VP-52 is held by deployment operator and setPermission of VP-04 by client signatory`() {
        fun holders(definition: VariationPointDefinition) =
            GstForm.entries.flatMapTo(linkedSetOf()) { OperationRoles.rolesFor(it, definition.setPermission).keys }
        holders(requireNotNull(catalogue.find(VariationPointKey("VP-52")))) shouldContain "deployment.operator"
        holders(requireNotNull(catalogue.find(VariationPointKey("VP-04")))) shouldContain "client.signatory"
    }

    @Test
    fun `look-alikes of the VP-01 setting permission are not accepted`() {
        val permission = requireNotNull(catalogue.find(VariationPointKey("VP-01"))).setPermission
        permission shouldBe "vp-01.set"
        val known = catalogue.permissionKeys()
        known shouldContain permission
        listOf("VP-01.set", "vp-01.acknowledge", "vp-01.set ", "vp-01.set\n", "VP-01.SET", "vp-1.set")
            .forEach { lookAlike ->
                withClue(lookAlike) {
                    (lookAlike == permission) shouldBe false
                    known.contains(lookAlike) shouldBe false
                }
            }
    }

    @Test
    fun `every setting key and every derived acknowledge key is in the catalogue key set`() {
        val known = catalogue.permissionKeys()
        catalogue.all().forEach { definition ->
            withClue(definition.key.value) {
                known shouldContain definition.setPermission
                definition.options.forEach { option ->
                    option.acknowledgers.forEach {
                        known shouldContain acknowledgePermissionKey(definition.key.value, option, it)
                    }
                }
            }
        }
    }

    @Test
    fun `the acknowledge keys of the thirteen rows are the expected ones, compared by equality`() {
        val derived =
            catalogue
                .all()
                .flatMap { definition ->
                    definition.options.flatMap { option ->
                        option.acknowledgers.map { acknowledgePermissionKey(definition.key.value, option, it) }
                    }
                }.toSet()
        val expected =
            listOf("03", "07", "13", "40", "44", "50", "61", "66", "81").map { "vp-$it.acknowledge" } +
                listOf("vp-59", "vp-62").flatMap {
                    listOf("$it.acknowledge.tax-approver", "$it.acknowledge.client-signatory")
                }
        derived shouldBe expected.toSet()
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
        spec.values shouldBe source.values
        spec.minItems shouldBe source.minItems
        spec.maxItems shouldBe source.maxItems
        spec.ref shouldBe source.ref
        spec.rule shouldBe source.rule
        spec.requires shouldBe source.requires
        spec.fields.keys shouldBe source.fields.keys
        source.fields.forEach { (name, field) -> assertSameTyped(id, spec.fields[name], field) }
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
    fun `an entry of roles a-x and b-y gives two acknowledgers whose keys are in the key set only if listed`() {
        val option =
            VpOption(
                "RELAXED",
                "relaxed",
                com.dynokore.gst.catalogue.Strictness.LOOSER,
                null,
                listOf(GstAcknowledger(listOf("a.x", "b.y"), AckCondition.UNDER_DELEGATION, false, emptyMap())),
            )
        val acknowledgers = GstVariationPointCatalogue.acknowledgers(option)
        acknowledgers.map { it.condition } shouldBe List(2) { AcknowledgerCondition.UNDER_DELEGATION }
        val spec =
            com.dynokore.platform.configresolver.OptionSpec(
                "RELAXED",
                Strictness.LOOSER,
                acknowledgers = acknowledgers,
            )
        val derived = acknowledgers.map { acknowledgePermissionKey("VP-99", spec, it) }
        derived shouldBe listOf("vp-99.acknowledge.a-x", "vp-99.acknowledge.b-y")
        val listed = setOf("vp-99.set") + derived
        derived.all { it in listed } shouldBe true
        derived.all { it in listed - "vp-99.acknowledge.b-y" } shouldBe false
        derived.all { it in listed - "vp-99.acknowledge.a-x" } shouldBe false
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
