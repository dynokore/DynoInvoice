package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `the snapshot carries PRF-1 to PRF-6 at version 1 and PRF-7 at version 3 with Keel's typed values`() {
        assertEquals((1..7).map { "PRF-$it" }, profiles.keys.toList())
        assertEquals((1..6).map { 1 } + 3, profiles.values.map { it.version })
        assertEquals("Small business, one or two people", profiles.getValue("PRF-1").name)
        val vp03 = profiles.getValue("PRF-1").values.single { it.vp == "VP-03" }
        assertEquals("EXTERNAL_ADVISOR", vp03.option)
        assertEquals("SOLE_OPERATOR_ACK", vp03.otherwiseOption)
        assertEquals(listOf("SUB-005", "PER-003"), profiles.getValue("PRF-4").values.single { it.vp == "VP-15" }.adds)
        val vp43 = profiles.getValue("PRF-7").values.single { it.vp == "VP-43" }
        assertEquals("LONGER", vp43.option)
        val record = vp43.typedValue!!
        assertEquals(ValueType.RECORD, record.type)
        assertEquals(setOf("structuredSubmissions", "acknowledgementsAndTraces", "sourceDocumentReferences"), record.fields.keys)
        record.fields.forEach { (name, field) ->
            assertEquals(ValueType.DURATION, field.type, name)
            assertEquals("years", field.unit, name)
            assertEquals(0, field.value!!.compareTo(BigDecimal(7)), name)
        }
    }

    @Test
    fun `INV-Q-3320 PRF-7 VP-08 is the default pair with a typed 2 hour window, and nothing is untyped`() {
        val vp08 = profiles.getValue("PRF-7").values.single { it.vp == "VP-08" }
        assertEquals("DEFAULT_PAIR_4H", vp08.option)
        assertNull(vp08.unresolved)
        val window = vp08.typedValue!!.fields.getValue("window")
        assertEquals(ValueType.DURATION, window.type)
        assertEquals("hours", window.unit)
        assertEquals(0, window.value!!.compareTo(BigDecimal(2)))
        assertTrue(profiles.values.none { p -> p.values.any { it.unresolved != null } })
    }

    @Test
    fun `INV-Q-3320 a PRF-7 VP-08 window outside the 1 to 8 hour bound fails`() {
        val rows = VariationPointYamlParser.parse(File("../variation-points/variation-points.yaml"), File("../variation-points/pending-fields.yaml"))
        val codes = ErrorCodeYamlParser.parse(File("../errors/error-codes.yaml"))
        fun withWindow(hours: Int): List<ParsedOperatingProfile> =
            profiles.values.map { p ->
                if (p.id != "PRF-7") {
                    p
                } else {
                    p.copy(
                        values =
                            p.values.map { v ->
                                if (v.vp != "VP-08") v
                                else v.copy(typedValue = v.typedValue!!.copy(fields = mapOf("window" to ProfileTypedValue(ValueType.DURATION, "hours", BigDecimal(hours)))))
                            },
                    )
                }
            }
        assertTrue(OperatingProfileChecker.check(withWindow(8), rows, codes).isEmpty())
        assertTrue(OperatingProfileChecker.check(withWindow(9), rows, codes).any { it.contains("VP-08") && it.contains("violates max 8") })
        assertTrue(OperatingProfileChecker.check(withWindow(0), rows, codes).any { it.contains("VP-08") && it.contains("violates min 1") })
    }

    @Test
    fun `INV-T-3636 PRF-2 VP-01's condition is typed as VP-01's directory fact, with no version change`() {
        val vp01 = profiles.getValue("PRF-2").values.single { it.vp == "VP-01" }
        assertEquals("CUSTOMER_OIDC", vp01.option)
        assertEquals("PLATFORM_IDENTITY", vp01.otherwiseOption)
        assertEquals(
            ConditionFact("VP-01.DIRECTORY_CONNECTED", ConstraintFact(ScopeLevel.TENANT, "customer_oidc_directory_connected")),
            vp01.conditionFact,
        )
        assertEquals(1, profiles.getValue("PRF-2").version)
    }

    @Test
    fun `INV-AC-2231 PRF-7 changed with a higher version, and its difference shows VP-43 only`() {
        val previous = OperatingProfileYamlParser.parseSnapshot(File("../operating-profiles/previous-snapshot.yaml")).associateBy { it.id }
        assertTrue(ProfileVersionChecker.check(previous.values.toList(), profiles.values.toList()).isEmpty())
        assertEquals(2, previous.getValue("PRF-7").version)
        val changes = ProfileVersionChecker.differences(previous.getValue("PRF-7").values, profiles.getValue("PRF-7").values)
        assertEquals(listOf("VP-43" to ProfileChangeKind.CHANGED), changes.map { it.vp to it.kind })
        assertEquals(ValueType.DURATION, changes.single().before!!.typedValue!!.type)
        assertEquals(ValueType.RECORD, changes.single().after!!.typedValue!!.type)
        (1..6).forEach { n ->
            val id = "PRF-$n"
            assertTrue(ProfileVersionChecker.differences(previous.getValue(id).values, profiles.getValue(id).values).isEmpty(), id)
        }
    }

    private val vpRows = VariationPointYamlParser.parse(File("../variation-points/variation-points.yaml"), File("../variation-points/pending-fields.yaml"))
    private val errorCodes = ErrorCodeYamlParser.parse(File("../errors/error-codes.yaml"))

    private fun withPrf7Vp43(fields: Map<String, ProfileTypedValue>): List<ParsedOperatingProfile> =
        profiles.values.map { p ->
            if (p.id != "PRF-7") {
                p
            } else {
                p.copy(values = p.values.map { v -> if (v.vp != "VP-43") v else v.copy(typedValue = v.typedValue!!.copy(fields = fields)) })
            }
        }

    private fun years(n: Int) = ProfileTypedValue(ValueType.DURATION, "years", BigDecimal(n))

    private val sevenYears =
        mapOf("structuredSubmissions" to years(7), "acknowledgementsAndTraces" to years(7), "sourceDocumentReferences" to years(7))

    @Test
    fun `INV-Q-6527 the unchanged PRF-7 record passes, and structuredSubmissions of 4 fails below the min 5`() {
        assertTrue(OperatingProfileChecker.check(withPrf7Vp43(sevenYears), vpRows, errorCodes).isEmpty())
        val below = OperatingProfileChecker.check(withPrf7Vp43(sevenYears + ("structuredSubmissions" to years(4))), vpRows, errorCodes)
        assertEquals(1, below.size, below.joinToString("\n"))
        assertTrue(below.single().contains("VP-43") && below.single().contains("structuredSubmissions") && below.single().contains("violates min 5"), below.single())
    }

    @Test
    fun `INV-Q-6527 the look-alike field structuredSubmission fails as an unknown field`() {
        val fields = sevenYears - "structuredSubmissions" + ("structuredSubmission" to years(7))
        val violations = OperatingProfileChecker.check(withPrf7Vp43(fields), vpRows, errorCodes)
        assertEquals(1, violations.size, violations.joinToString("\n"))
        assertTrue(violations.single().contains("VP-43") && violations.single().contains("sets field 'structuredSubmission', which its option's record does not have"), violations.single())
    }

    @Test
    fun `INV-Q-6527 a PRF-7 value without sourceDocumentReferences passes the profile check, the omitted field keeps the default`() {
        // OperatingProfileChecker lets a record omit a field (INV-Q-3320); a write that omits one is refused by config-resolver's setPolicy check, not here.
        assertTrue(OperatingProfileChecker.check(withPrf7Vp43(sevenYears - "sourceDocumentReferences"), vpRows, errorCodes).isEmpty())
    }

    @Test
    fun `an untyped value without a pending marker fails`() {
        val untyped = File.createTempFile("profiles", ".yaml")
        // PRF-7's VP-08 is the one value the snapshots once carried untyped; rebuild that form from the typed row.
        val typedRow = Regex("""        option: "DEFAULT_PAIR_4H"\n        typed_value:\n(?:          .*\n|            .*\n)+""")
        val untypedRow = "        typed: false\n        untyped_reason: \"no option fits; left untyped pending its own ruling\"\n"
        val text = File("../operating-profiles/previous-snapshot.yaml").readText()
        assertTrue(typedRow.containsMatchIn(text))
        untyped.writeText(typedRow.replaceFirst(text, untypedRow))
        val empty = File.createTempFile("pending", ".yaml").apply { writeText("pending: {}\n") }
        val error = assertThrows(IllegalStateException::class.java) { OperatingProfileYamlParser.parse(untyped, empty) }
        assertTrue(error.message!!.contains("never invent an option"))
    }
}
