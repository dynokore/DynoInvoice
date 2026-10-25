package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NonWaivableFloorCheckerTest {
    private fun control(
        id: String,
        modifiers: List<VpClassModifier> = emptyList(),
        options: List<String> = listOf("a", "b"),
        bounds: String? = null,
        title: String = "t",
    ) = ParsedVariationPoint(
        id = id,
        requirement = "INV-REQ-0",
        revision = "x",
        title = title,
        vpClass = VpClass.CONTROL,
        classModifiers = modifiers,
        scope = listOf("entity"),
        owner = "o",
        setPermission = "vp-01.set",
        mustBeElected = false,
        default = options.first(),
        options = options,
        bounds = bounds,
        status = VpStatus.AVAILABLE,
        statusNote = null,
        governs = emptyList(),
        formDependence = null,
    )

    @Test
    fun `a plain CONTROL with a looser option and an acknowledgement marker in bounds is classified`() {
        val result = NonWaivableFloorChecker.check(listOf(control("VP-X", bounds = "needs a recorded acknowledgement")))
        assertTrue(result.passed)
        assertEquals(1, result.classified.size)
    }

    @Test
    fun `a plain CONTROL with a looser option and no acknowledgement marker anywhere is a violation`() {
        val result = NonWaivableFloorChecker.check(listOf(control("VP-X", bounds = "no special rule here")))
        assertTrue(!result.passed)
        assertEquals(1, result.violations.size)
    }

    @Test
    fun `a CONTROL with only one option needs no acknowledgement marker`() {
        val result = NonWaivableFloorChecker.check(listOf(control("VP-X", options = listOf("only"))))
        assertTrue(result.passed)
        assertEquals(1, result.classified.size)
    }

    @Test
    fun `a tighten-only CONTROL passes structurally regardless of text`() {
        val result =
            NonWaivableFloorChecker.check(
                listOf(control("VP-X", modifiers = listOf(VpClassModifier.TIGHTEN_ONLY), bounds = "nothing relevant")),
            )
        assertTrue(result.passed)
        assertEquals(1, result.classified.size)
    }

    @Test
    fun `VP-09 and VP-08, the only known non-waivable VPs, are classified compliant`() {
        val rows =
            listOf(
                control("VP-09", modifiers = listOf(VpClassModifier.NON_WAIVABLE)),
                control("VP-08", modifiers = listOf(VpClassModifier.NON_WAIVABLE_IN_PART)),
            )
        val result = NonWaivableFloorChecker.check(rows)
        assertTrue(result.passed)
        assertEquals(2, result.classified.size)
    }

    @Test
    fun `a non-waivable CONTROL not in the cited map is listed for decision, not passed silently`() {
        val result = NonWaivableFloorChecker.check(listOf(control("VP-999", modifiers = listOf(VpClassModifier.NON_WAIVABLE))))
        assertTrue(result.passed) // pending decision is not a violation
        assertEquals(1, result.pendingDecision.size)
        assertEquals(0, result.classified.size)
    }

    @Test
    fun `only CONTROL-class rows are checked`() {
        val policy = control("VP-P", bounds = "no acknowledgement anywhere").copy(vpClass = VpClass.POLICY)
        val result = NonWaivableFloorChecker.check(listOf(policy))
        assertTrue(result.passed)
        assertTrue(result.classified.isEmpty())
        assertTrue(result.pendingDecision.isEmpty())
    }
}
