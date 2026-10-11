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
    ) = VpCatalogueFields(
        scopeLevels = listOf(ScopeLevel.ENTITY),
        options = options.mapIndexed { i, (k, s) -> VpOptionSpec(k, s, if (i == options.lastIndex) typed else null) },
    )

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
}
