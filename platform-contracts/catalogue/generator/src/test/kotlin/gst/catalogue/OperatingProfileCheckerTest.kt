package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** INV-T-3277: each profile value is a reference to a catalogue row and option key, checked here on small fixtures. */
class OperatingProfileCheckerTest {
    private fun vp(
        id: String,
        options: List<VpOptionSpec>,
        status: VpStatus = VpStatus.AVAILABLE,
        multi: Boolean = false,
    ) = ParsedVariationPoint(
        id = id, requirement = "REQ-$id", revision = "r", title = "t", vpClass = VpClass.CONTROL, classModifiers = emptyList(),
        scope = emptyList(), owner = "o", setBy = emptyList(), mustBeElected = false, default = "d",
        options = options.map { it.key }, bounds = null, status = status, statusNote = null, governs = emptyList(),
        formDependence = null, fields = VpCatalogueFields(listOf(ScopeLevel.ENTITY), options, multiSelect = multi),
    )

    private val years = TypedValue(ValueType.DURATION, unit = "years", min = BoundValue(number = BigDecimal(5)))
    private val warnCodes = TypedValue(ValueType.LIST_REF, ref = "error-codes catalogue", filter = "severity WARN only")
    private val catalogue =
        listOf(
            vp("VP-1", listOf(VpOptionSpec("A", Strictness.DEFAULT), VpOptionSpec("LOOSE", Strictness.LOOSER))),
            vp("VP-2", listOf(VpOptionSpec("Y5", Strictness.DEFAULT), VpOptionSpec("LONGER", Strictness.STRICTER, years))),
            vp("VP-3", listOf(VpOptionSpec("BLOCK_ONLY", Strictness.DEFAULT), VpOptionSpec("ADD", Strictness.STRICTER, warnCodes))),
            vp("VP-4", listOf(VpOptionSpec("NONE", Strictness.DEFAULT), VpOptionSpec("X", Strictness.NEUTRAL)), multi = true),
        )
    private val codes =
        listOf(
            ParsedErrorCode("SUB-005", "SUB", 5, "c", "c", null, Severity.WARN, "WARN", null, "", "o"),
            ParsedErrorCode("SUB-001", "SUB", 1, "c", "c", null, Severity.BLOCK, "BLOCK", null, "", "o"),
        )

    private fun check(vararg values: ParsedProfileValue) =
        OperatingProfileChecker.check(
            listOf(ParsedOperatingProfile("PRF-1", "R", "r", "n", "t", 1, ProfileStatus.AVAILABLE, null, values.toList())),
            catalogue,
            codes,
        )

    private fun value(
        vp: String,
        option: String? = null,
    ) = ParsedProfileValue(vp = vp, requirement = "REQ-$vp", text = "t", option = option)

    @Test
    fun `valid values pass`() {
        val ok =
            check(
                value("VP-1", "A").copy(otherwiseOption = "LOOSE", needsAcknowledgement = listOf("LOOSE")),
                value("VP-2", "LONGER").copy(typedValue = ProfileTypedValue(ValueType.DURATION, "years", BigDecimal(7))),
                value("VP-3", "ADD").copy(adds = listOf("SUB-005")),
                value("VP-4").copy(options = listOf("X")),
            )
        assertEquals(emptyList<String>(), ok)
    }

    @Test
    fun `a missing row or option fails`() {
        assertTrue(check(value("VP-9", "A")).single().contains("no such variation point"))
        assertTrue(check(value("VP-1", "B")).single().contains("'B' is not an option of VP-1"))
        assertTrue(check(value("VP-1", "A").copy(requirement = "REQ-X")).single().contains("names REQ-X"))
    }

    @Test
    fun `a LOOSER option must be flagged and a flag must be LOOSER`() {
        assertTrue(check(value("VP-1", "LOOSE")).single().contains("not flagged"))
        assertTrue(check(value("VP-1", "A").copy(needsAcknowledgement = listOf("A"))).single().contains("is not LOOSER"))
    }

    @Test
    fun `typed values stay in bounds and adds codes have the filtered severity`() {
        val short = value("VP-2", "LONGER").copy(typedValue = ProfileTypedValue(ValueType.DURATION, "years", BigDecimal(4)))
        assertTrue(check(short).single().contains("violates min 5"))
        val months = value("VP-2", "LONGER").copy(typedValue = ProfileTypedValue(ValueType.DURATION, "months", BigDecimal(70)))
        assertTrue(check(months).single().contains("unit 'months'"))
        assertTrue(check(value("VP-3", "ADD").copy(adds = listOf("SUB-001"))).single().contains("WARN only"))
        assertTrue(check(value("VP-3", "ADD").copy(adds = listOf("SUB-999"))).single().contains("not in the error-code catalogue"))
    }

    @Test
    fun `select kind and explicit default are checked`() {
        assertTrue(check(value("VP-4", "X")).single().contains("multi-select"))
        assertTrue(check(value("VP-1").copy(options = listOf("A"))).single().contains("single-select"))
        assertTrue(check(value("VP-4").copy(options = listOf("NONE", "X"))).isEmpty()) // no exclusive keys in this fixture
        assertTrue(check(value("VP-2", "LONGER").copy(explicitDefault = true)).single().contains("not the DEFAULT"))
    }

    @Test
    fun `an unresolved value is checked only for its row`() {
        val gap = value("VP-1").copy(unresolved = UnresolvedValue("no option fits", "INV-Q-1"))
        assertTrue(check(gap).isEmpty())
        assertTrue(check(gap.copy(vp = "VP-9", requirement = "REQ-VP-9")).single().contains("no such variation point"))
    }
}
