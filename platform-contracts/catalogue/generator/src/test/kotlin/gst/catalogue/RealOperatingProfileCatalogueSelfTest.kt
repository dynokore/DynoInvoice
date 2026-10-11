package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal

/** Runs the parser and checks against the real vendored snapshot (not a fixture), as RealVariationPointCatalogueSelfTest does. */
class RealOperatingProfileCatalogueSelfTest {
    private val profilesFile = File("../operating-profiles/operating-profiles.yaml")
    private val pendingFile = File("../operating-profiles/pending-values.yaml")
    private val profiles = OperatingProfileYamlParser.parse(profilesFile, pendingFile).associateBy { it.id }

    @Test
    fun `every profile value references a real row and option`() {
        val violations =
            OperatingProfileChecker.check(
                profiles.values.toList(),
                VariationPointYamlParser.parse(File("../variation-points/variation-points.yaml"), File("../variation-points/pending-fields.yaml")),
                ErrorCodeYamlParser.parse(File("../errors/error-codes.yaml")),
            )
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `the snapshot carries PRF-1 to PRF-7 at version 1 with Keel's typed values`() {
        assertEquals((1..7).map { "PRF-$it" }, profiles.keys.toList())
        assertTrue(profiles.values.all { it.version == 1 })
        assertEquals("Small business, one or two people", profiles.getValue("PRF-1").name)
        val vp03 = profiles.getValue("PRF-1").values.single { it.vp == "VP-03" }
        assertEquals("EXTERNAL_ADVISOR", vp03.option)
        assertEquals("SOLE_OPERATOR_ACK", vp03.otherwiseOption)
        assertEquals(listOf("SUB-005", "PER-003"), profiles.getValue("PRF-4").values.single { it.vp == "VP-15" }.adds)
        val vp43 = profiles.getValue("PRF-7").values.single { it.vp == "VP-43" }
        assertEquals(0, vp43.typedValue!!.value.compareTo(BigDecimal(7)))
    }

    @Test
    fun `PRF-7 VP-08 is a marked gap pending INV-Q-3320, never an invented option`() {
        val vp08 = profiles.getValue("PRF-7").values.single { it.vp == "VP-08" }
        assertEquals(null, vp08.option)
        assertEquals("INV-Q-3320", vp08.unresolved!!.pendingQuestion)
        assertEquals(listOf("PRF-7" to "VP-08"), profiles.values.flatMap { p -> p.values.filter { it.unresolved != null }.map { p.id to it.vp } })
    }

    @Test
    fun `an untyped value without a pending marker fails`() {
        val empty = File.createTempFile("pending", ".yaml").apply { writeText("pending: {}\n") }
        val error = assertThrows(IllegalStateException::class.java) { OperatingProfileYamlParser.parse(profilesFile, empty) }
        assertTrue(error.message!!.contains("never invent an option"))
    }
}
