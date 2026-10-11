package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files

class VariationPointFieldsCheckerTest {
    private fun row(
        vpClass: VpClass = VpClass.CONTROL,
        modifiers: List<VpClassModifier> = emptyList(),
        options: List<String> = listOf("a", "b"),
        default: String? = "a",
        mustBeElected: Boolean = false,
        status: VpStatus = VpStatus.AVAILABLE,
        fields: VpCatalogueFields? = null,
        pending: List<String> = emptyList(),
    ) = ParsedVariationPoint(
        id = "VP-99",
        requirement = "INV-REQ-1",
        revision = "r",
        title = "t",
        vpClass = vpClass,
        classModifiers = modifiers,
        scope = listOf("entity"),
        owner = "svc",
        setBy = listOf("tax.approver"),
        mustBeElected = mustBeElected,
        default = default,
        options = options,
        bounds = null,
        status = status,
        statusNote = null,
        governs = emptyList(),
        formDependence = null,
        fields = fields,
        pendingQuestions = pending,
    )

    private fun fields(
        vararg options: Pair<String, Strictness>,
        typed: TypedValue? = null,
        acknowledgedBy: List<Acknowledger>? = null,
    ) = VpCatalogueFields(
        scopeLevels = listOf(ScopeLevel.ENTITY),
        options =
            options.mapIndexed { i, (k, s) ->
                VpOptionSpec(
                    k,
                    s,
                    if (i == options.lastIndex) typed else null,
                    if (s == Strictness.LOOSER) acknowledgedBy ?: listOf(ack()) else emptyList(),
                )
            },
    )

    private fun ack(
        roles: List<String> = listOf("tax.approver"),
        byForm: Map<DeploymentForm, List<String>> = emptyMap(),
    ) = Acknowledger(roles, AckCondition.ALWAYS, false, byForm)

    @Test
    fun `every LOOSER option needs a non-empty acknowledged_by naming roles`() {
        fun check(a: List<Acknowledger>) =
            VariationPointFieldsChecker.check(row(fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.LOOSER, acknowledgedBy = a)))
        assertEquals(1, check(emptyList()).size)
        assertEquals(1, check(listOf(ack(roles = emptyList()))).size)
        assertEquals(1, check(listOf(ack(byForm = mapOf(DeploymentForm.SAAS to listOf("security.officer"))))).size)
        assertEquals(1, check(listOf(ack(roles = listOf(" ")))).size)
        assertTrue(check(listOf(ack(roles = emptyList(), byForm = mapOf(DeploymentForm.SAAS to listOf("a"), DeploymentForm.PRIVATE to listOf("b"))))).isEmpty())
    }

    @Test
    fun `a non-LOOSER option may not carry acknowledged_by`() {
        val f = fields("A" to Strictness.DEFAULT, "B" to Strictness.LOOSER)
        val withAckOnDefault = f.copy(options = listOf(f.options[0].copy(acknowledgedBy = listOf(ack())), f.options[1]))
        assertEquals(1, VariationPointFieldsChecker.check(row(fields = withAckOnDefault)).size)
    }

    @Test
    fun `acknowledged_by parses strictly into typed entries`() {
        val entry =
            mapOf("roles" to listOf("tax.approver"), "condition" to "UNDER_DELEGATION", "distinct_from_setter" to true, "roles_by_form" to null)
        val f = VariationPointFieldsParser.parse("VP-99", catWith(optionB = mapOf("strictness" to "LOOSER", "acknowledged_by" to listOf(entry))), java.io.File("x"))
        assertEquals(listOf(Acknowledger(listOf("tax.approver"), AckCondition.UNDER_DELEGATION, true)), f.options[1].acknowledgedBy)
        val byForm = entry + mapOf("roles" to emptyList<String>(), "roles_by_form" to mapOf("SAAS" to listOf("s"), "PRIVATE" to listOf("p")))
        val g = VariationPointFieldsParser.parse("VP-99", catWith(optionB = mapOf("acknowledged_by" to listOf(byForm))), java.io.File("x"))
        assertEquals(mapOf(DeploymentForm.SAAS to listOf("s"), DeploymentForm.PRIVATE to listOf("p")), g.options[1].acknowledgedBy.single().rolesByForm)
        fun bad(vararg e: Pair<String, Any?>) = catWith(optionB = mapOf("acknowledged_by" to listOf(entry + mapOf(*e))))
        parseFails(bad("condition" to "SOMETIMES"), "is not one of ALWAYS, UNDER_DELEGATION")
        parseFails(bad("distinct_from_setter" to "yes"), "'distinct_from_setter' must be true or false")
        parseFails(bad("roles" to "tax.approver"), "'roles' must be a list")
        parseFails(bad("roles" to listOf(3)), "non-text entry")
        parseFails(bad("extra" to 1), "unknown field(s) extra")
        parseFails(bad("roles_by_form" to mapOf("CLOUD" to listOf("x"))), "is not one of SAAS, PRIVATE")
        parseFails(bad("roles_by_form" to mapOf("SAAS" to "x")), "must be a list of roles")
        parseFails(catWith(optionB = mapOf("acknowledged_by" to listOf(entry - "condition"))), "no 'condition'")
        parseFails(catWith(optionB = mapOf("acknowledged_by" to listOf(entry - "distinct_from_setter"))), "no 'distinct_from_setter'")
        parseFails(catWith(optionB = mapOf("acknowledged_by" to "x")), "'acknowledged_by' must be a list")
    }

    @Test
    fun `a row without fields needs a pending marker unless retired`() {
        assertEquals(1, VariationPointFieldsChecker.check(row()).size)
        assertTrue(VariationPointFieldsChecker.check(row(pending = listOf("INV-Q-3049"))).isEmpty())
        assertTrue(VariationPointFieldsChecker.check(row(status = VpStatus.RETIRED)).isEmpty())
    }

    @Test
    fun `a pending marker on a row that has fields is stale`() {
        val r = row(fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.STRICTER), pending = listOf("INV-Q-1"))
        assertTrue(VariationPointFieldsChecker.check(r).single().reason.contains("stale"))
    }

    @Test
    fun `a clean CONTROL row with a looser option passes`() {
        val r = row(fields = fields("EARLIER_DATE" to Strictness.DEFAULT, "FILER_OF_RECORD" to Strictness.LOOSER))
        assertTrue(VariationPointFieldsChecker.check(r).isEmpty())
    }

    @Test
    fun `tighten-only and non-waivable controls offer no looser option`() {
        listOf(VpClassModifier.TIGHTEN_ONLY, VpClassModifier.NON_WAIVABLE, VpClassModifier.NON_WAIVABLE_IN_PART).forEach {
            val r = row(modifiers = listOf(it), fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.LOOSER))
            assertEquals(1, VariationPointFieldsChecker.check(r).size, "$it")
        }
    }

    @Test
    fun `POLICY options are only DEFAULT or NEUTRAL and PARAMETER options never LOOSER`() {
        val policy = row(vpClass = VpClass.POLICY, fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.STRICTER))
        assertEquals(1, VariationPointFieldsChecker.check(policy).size)
        val parameter = row(vpClass = VpClass.PARAMETER, fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.LOOSER))
        assertEquals(1, VariationPointFieldsChecker.check(parameter).size)
    }

    @Test
    fun `keys must match the options one to one, be UPPER_SNAKE and unique`() {
        assertEquals(1, VariationPointFieldsChecker.check(row(fields = fields("A" to Strictness.DEFAULT))).size)
        val badKey = row(fields = fields("A" to Strictness.DEFAULT, "lower_case" to Strictness.NEUTRAL))
        assertEquals(1, VariationPointFieldsChecker.check(badKey).size)
        val duplicate = row(fields = fields("A" to Strictness.DEFAULT, "A" to Strictness.NEUTRAL))
        assertEquals(1, VariationPointFieldsChecker.check(duplicate).size)
    }

    @Test
    fun `exactly one DEFAULT at the default's position, none on a must-be-elected row`() {
        val two = row(fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.DEFAULT))
        assertEquals(1, VariationPointFieldsChecker.check(two).size)
        val wrongPosition = row(fields = fields("A" to Strictness.NEUTRAL, "B" to Strictness.DEFAULT))
        assertEquals(1, VariationPointFieldsChecker.check(wrongPosition).size)
        val elected = row(mustBeElected = true, default = null, fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.NEUTRAL))
        assertEquals(1, VariationPointFieldsChecker.check(elected).size)
    }

    @Test
    fun `typed values state a direction and keep min, default and max in order`() {
        fun n(v: Int) = BoundValue(number = BigDecimal(v))
        val noDirection = TypedValue(ValueType.INT, min = n(1), max = n(10))
        assertEquals(1, VariationPointFieldsChecker.check(row(fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.NEUTRAL, typed = noDirection))).size)
        val outside = TypedValue(ValueType.DURATION, min = n(1), max = n(8), default = n(9), direction = Direction.DOWN_STRICTER)
        assertEquals(1, VariationPointFieldsChecker.check(row(fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.NEUTRAL, typed = outside))).size)
        val record = TypedValue(ValueType.RECORD, fields = mapOf("w" to TypedValue(ValueType.INT, min = n(5), max = n(2), direction = Direction.NONE)))
        assertEquals(1, VariationPointFieldsChecker.check(row(fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.NEUTRAL, typed = record))).size)
        val undirected = TypedValue(ValueType.RECORD, fields = mapOf("w" to TypedValue(ValueType.INT, min = n(0), max = n(4))))
        assertEquals(1, VariationPointFieldsChecker.check(row(fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.NEUTRAL, typed = undirected))).size)
        val directedRecord = undirected.copy(direction = Direction.NONE)
        assertTrue(VariationPointFieldsChecker.check(row(fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.NEUTRAL, typed = directedRecord))).isEmpty())
    }

    @Test
    fun `exclusive keys need multi-select and must name option keys`() {
        val f = fields("A" to Strictness.DEFAULT, "B" to Strictness.NEUTRAL).copy(exclusiveKeys = listOf("Z"))
        assertEquals(2, VariationPointFieldsChecker.check(row(fields = f)).size)
    }

    @Test
    fun `a pending marker must name INV-Q question keys`() {
        val file = Files.createTempFile("pending", ".yaml").toFile().apply { writeText("pending:\n  VP-04: [TBD]\n") }
        assertThrows(IllegalStateException::class.java) { PendingFieldsParser.parse(file) }
        file.writeText("pending:\n  VP-04: [INV-Q-3049]\n")
        assertEquals(mapOf("VP-04" to listOf("INV-Q-3049")), PendingFieldsParser.parse(file))
    }

    @Test
    fun `a per-form default replaces the single DEFAULT and names option keys`() {
        val f = fields("ON" to Strictness.NEUTRAL, "OFF" to Strictness.NEUTRAL)
        assertTrue(VariationPointFieldsChecker.check(row(default = "x", fields = f.copy(defaultByForm = mapOf("SAAS" to "ON")))).isEmpty())
        assertEquals(1, VariationPointFieldsChecker.check(row(default = "x", fields = f.copy(defaultByForm = mapOf("SAAS" to "MAYBE")))).size)
    }

    @Test
    fun `list, map and enum typed values parse with their inner types`() {
        val cat =
            mapOf(
                "scope_levels" to listOf("TENANT"),
                "options" to
                    listOf(
                        mapOf("key" to "A", "strictness" to "DEFAULT"),
                        mapOf(
                            "key" to "B",
                            "strictness" to "NEUTRAL",
                            "typed_value" to
                                mapOf(
                                    "type" to "map",
                                    "direction" to "NONE",
                                    "key" to mapOf("type" to "list_ref", "ref" to "tiers"),
                                    "value" to
                                        mapOf(
                                            "type" to "list",
                                            "item" to mapOf("type" to "enum", "values" to listOf("UP", "DOWN")),
                                            "min_items" to 1,
                                            "max_items" to 5,
                                        ),
                                ),
                        ),
                    ),
            )
        val f = VariationPointFieldsParser.parse("VP-99", cat, java.io.File("x"))
        val map = f.options[1].typedValue!!
        assertEquals(ValueType.MAP, map.type)
        assertEquals(listOf("UP", "DOWN"), map.value!!.item!!.values)
        assertEquals(5, map.value.maxItems)
        val bad = cat + ("options" to listOf(mapOf("key" to "A", "strictness" to "DEFAULT", "typed_value" to mapOf("type" to "list"))))
        assertThrows(IllegalStateException::class.java) { VariationPointFieldsParser.parse("VP-99", bad, java.io.File("x")) }
    }

    private fun catWith(
        optionB: Map<String, Any?> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        mapOf(
            "scope_levels" to listOf("TENANT"),
            "options" to
                listOf(
                    mapOf("key" to "A", "strictness" to "DEFAULT"),
                    mapOf("key" to "B", "strictness" to "NEUTRAL") + optionB,
                ),
        ) + extra

    private fun parseFails(
        cat: Map<String, Any?>,
        contains: String,
    ) {
        val e = assertThrows(IllegalStateException::class.java) { VariationPointFieldsParser.parse("VP-99", cat, java.io.File("x")) }
        assertTrue(e.message!!.contains(contains), e.message)
    }

    @Test
    fun `an unknown option key fails instead of being dropped`() {
        parseFails(catWith(optionB = mapOf("strictnes" to "LOOSER")), "unknown field(s) strictnes")
    }

    @Test
    fun `wrongly typed values fail instead of becoming null or a default`() {
        parseFails(catWith(optionB = mapOf("typed_value" to "int")), "is not a map")
        parseFails(catWith(extra = mapOf("multi_select" to "yes")), "'multi_select' must be true or false")
        parseFails(catWith(extra = mapOf("must_be_elected" to 1)), "'must_be_elected' must be true or false")
        parseFails(catWith(extra = mapOf("exclusive_keys" to "A")), "'exclusive_keys' must be a list")
        parseFails(catWith(extra = mapOf("exclusive_keys" to listOf(mapOf("a" to 1)))), "non-text entry")
        parseFails(catWith(extra = mapOf("note" to 5)), "'note' must be text")
        parseFails(catWith(extra = mapOf("default_by_form" to listOf("A"))), "is not a map")
        parseFails(catWith(extra = mapOf("installation_parameter" to 3)), "is not a map")
        val list = { extra: Map<String, Any?> -> catWith(optionB = mapOf("typed_value" to mapOf("type" to "list", "item" to mapOf("type" to "int", "direction" to "NONE")) + extra)) }
        parseFails(list(mapOf("distinct" to "true")), "'distinct' must be true or false")
        parseFails(list(mapOf("ordered" to 1)), "'ordered' must be true or false")
        parseFails(list(mapOf("min_items" to "2")), "'min_items' must be a whole number")
        parseFails(list(mapOf("max_items" to 2.5)), "'max_items' must be a whole number")
        parseFails(list(mapOf("unit" to listOf("d"))), "'unit' must be text")
        parseFails(list(mapOf("min" to listOf(1))), "a bound must be a number")
        parseFails(list(mapOf("item" to "int")), "is not a map")
        parseFails(list(mapOf("fields" to "x")), "is not a map")
        parseFails(catWith(optionB = mapOf("typed_value" to mapOf("type" to "enum", "values" to "UP"))), "'values' must be a list")
    }

    @Test
    fun `a list or map of numeric items states its direction`() {
        fun n(v: Int) = BoundValue(number = BigDecimal(v))
        val item = TypedValue(ValueType.INT, min = n(1), max = n(9))
        fun check(typed: TypedValue) = VariationPointFieldsChecker.check(row(fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.NEUTRAL, typed = typed)))
        assertEquals(1, check(TypedValue(ValueType.LIST, item = item)).size)
        assertEquals(1, check(TypedValue(ValueType.MAP, key = TypedValue(ValueType.LIST_REF, ref = "t"), value = TypedValue(ValueType.LIST, item = item))).size)
        assertTrue(check(TypedValue(ValueType.LIST, item = item, direction = Direction.UP_STRICTER)).isEmpty())
        assertTrue(check(TypedValue(ValueType.LIST, item = TypedValue(ValueType.ENUM, values = listOf("X")))).isEmpty())
    }

    @Test
    fun `a default must lie above the exclusive minimum`() {
        fun n(v: Int) = BoundValue(number = BigDecimal(v))
        fun check(typed: TypedValue) = VariationPointFieldsChecker.check(row(fields = fields("A" to Strictness.DEFAULT, "B" to Strictness.NEUTRAL, typed = typed)))
        val base = TypedValue(ValueType.INT, min = null, max = n(10), minExclusive = n(0), direction = Direction.NONE)
        assertEquals(1, check(base.copy(default = n(0))).size)
        assertEquals(1, check(base.copy(default = n(-1))).size)
        assertTrue(check(base.copy(default = n(1))).isEmpty())
    }

    // INV-Q-3202: a bound or installation parameter that names another variation point.

    private fun parseCatalogue(cat: Map<String, Any?>) = VariationPointFieldsParser.parse("VP-99", cat, java.io.File("fixture.yaml"))

    private val rowBoundCatalogue: Map<String, Any?> =
        mapOf(
            "scope_levels" to listOf("TENANT"),
            "options" to
                listOf(
                    mapOf("key" to "PLATFORM_MAXIMUM", "strictness" to "DEFAULT"),
                    mapOf(
                        "key" to "SHORTER",
                        "strictness" to "STRICTER",
                        "typed_value" to
                            mapOf("type" to "duration", "unit" to "days", "min" to 1, "max" to mapOf("row" to "VP-79"), "direction" to "DOWN_STRICTER"),
                    ),
                ),
            "installation_parameter" to mapOf("row" to "VP-79", "requirement" to "INV-REQ-3210", "name" to "platform maximum", "rule" to "r"),
        )

    @Test
    fun `parses a max bound and an installation parameter that name another row`() {
        val parsed = parseCatalogue(rowBoundCatalogue)
        assertEquals(BoundValue(row = "VP-79"), parsed.options[1].typedValue!!.max)
        assertEquals(InstallationParameterRef("VP-79", "INV-REQ-3210", "platform maximum", null, "r"), parsed.installationParameterRef)
        assertEquals(null, parsed.installationParameter)
    }

    @Test
    fun `a bound map other than exactly row VP-nn fails the parse`() {
        listOf(mapOf("row" to "79"), mapOf("row" to "VP-79", "extra" to 1), mapOf("value" to 3)).forEach { bad ->
            val cat = rowBoundCatalogue + ("options" to listOf(mapOf("key" to "S", "strictness" to "DEFAULT", "typed_value" to mapOf("type" to "duration", "max" to bad))))
            assertThrows(IllegalStateException::class.java) { parseCatalogue(cat) }
        }
    }

    @Test
    fun `an installation parameter row without its requirement or with unknown fields fails the parse`() {
        assertThrows(IllegalStateException::class.java) {
            parseCatalogue(rowBoundCatalogue + ("installation_parameter" to mapOf("row" to "VP-79")))
        }
        assertThrows(IllegalStateException::class.java) {
            parseCatalogue(rowBoundCatalogue + ("installation_parameter" to mapOf("row" to "VP-79", "requirement" to "R", "max" to 3)))
        }
    }

    @Test
    fun `row references pass when the named row exists with the named requirement`() {
        val source = row(options = listOf("a", "b"), fields = parseCatalogue(rowBoundCatalogue))
        val target = row().copy(id = "VP-79", requirement = "INV-REQ-3210")
        assertEquals(emptyList<VariationPointFieldsChecker.Violation>(), VariationPointFieldsChecker.checkRowReferences(listOf(source, target)))
    }

    @Test
    fun `a row reference to a missing row or the wrong requirement fails`() {
        val source = row(options = listOf("a", "b"), fields = parseCatalogue(rowBoundCatalogue))
        val missing = VariationPointFieldsChecker.check(listOf(source)).map { it.reason }
        assertTrue(missing.any { it == "option SHORTER: bound row VP-79 is not a variation point of this catalogue" }, missing.toString())
        assertTrue(missing.any { it == "installation_parameter row VP-79 is not a variation point of this catalogue" }, missing.toString())
        val wrong = row().copy(id = "VP-79", requirement = "INV-REQ-1")
        assertEquals(
            listOf("installation_parameter row VP-79 is INV-REQ-1, not INV-REQ-3210"),
            VariationPointFieldsChecker.checkRowReferences(listOf(source, wrong)).map { it.reason },
        )
    }
}
