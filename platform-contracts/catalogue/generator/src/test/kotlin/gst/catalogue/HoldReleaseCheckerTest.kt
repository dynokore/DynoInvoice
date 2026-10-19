package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HoldReleaseCheckerTest {
    private fun row(
        code: String,
        severity: Severity,
        disposition: Disposition?,
        raw: String = disposition?.name ?: "",
    ) = ParsedErrorCode(
        code = code,
        area = code.substringBefore('-'),
        number = code.substringAfter('-').toInt(),
        conditionRaw = "condition",
        conditionCore = "condition",
        revisionNote = null,
        severity = severity,
        severityRaw = severity.name,
        disposition = disposition,
        dispositionRaw = raw,
        owner = "Platform",
    )

    private val ing017 = row("ING-017", Severity.HOLD, Disposition.MANUAL)

    private fun auto(raw: String) = row("PAY-013", Severity.HOLD, Disposition.AUTO_RELEASE, raw)

    private fun violationsOf(vararg rows: ParsedErrorCode) = HoldReleaseChecker.check(rows.toList()).violations

    @Test
    fun `AUTO_RELEASE on an event passes`() {
        assertTrue(HoldReleaseChecker.check(listOf(auto("AUTO_RELEASE on acceptance"), ing017)).passed)
    }

    @Test
    fun `AUTO_RELEASE when an event passes`() {
        val rows = listOf(auto("AUTO_RELEASE when the election is recorded"), ing017)
        assertTrue(HoldReleaseChecker.check(rows).passed)
    }

    @Test
    fun `ING-017 with MANUAL passes`() {
        val result = HoldReleaseChecker.check(listOf(ing017))
        assertTrue(result.passed)
        assertEquals(1, result.checked.size)
    }

    @Test
    fun `the bare AUTO_RELEASE is a violation naming the code and the raw string`() {
        val v = violationsOf(auto("AUTO_RELEASE"), ing017)
        assertEquals(1, v.size)
        assertTrue("PAY-013" in v[0] && "'AUTO_RELEASE'" in v[0])
    }

    @Test
    fun `AUTO_RELEASE on with nothing after it is a violation`() {
        assertEquals(1, violationsOf(auto("AUTO_RELEASE on"), ing017).size)
    }

    @Test
    fun `look-alikes of on and when are violations`() {
        assertEquals(1, violationsOf(auto("AUTO_RELEASE onwards"), ing017).size)
        assertEquals(1, violationsOf(auto("AUTO_RELEASE when "), ing017).size)
    }

    @Test
    fun `a HOLD row ING-018 with MANUAL is a violation`() {
        val v = violationsOf(row("ING-018", Severity.HOLD, Disposition.MANUAL), ing017)
        assertEquals(1, v.size)
        assertTrue("ING-018" in v[0])
    }

    @Test
    fun `ING-017 with TERMINAL is a violation`() {
        assertFalse(HoldReleaseChecker.check(listOf(row("ING-017", Severity.HOLD, Disposition.TERMINAL))).passed)
    }

    @Test
    fun `ING-017 with severity BLOCK is a violation`() {
        assertFalse(HoldReleaseChecker.check(listOf(row("ING-017", Severity.BLOCK, Disposition.MANUAL), auto("AUTO_RELEASE on x"))).passed)
    }

    @Test
    fun `ING-017 absent is a violation`() {
        val v = violationsOf(auto("AUTO_RELEASE on x"))
        assertEquals(1, v.size)
        assertTrue("ING-017" in v[0])
    }

    @Test
    fun `an empty catalogue is a violation`() {
        assertFalse(HoldReleaseChecker.check(emptyList()).passed)
    }

    @Test
    fun `WARN and BLOCK rows with the bare AUTO_RELEASE are not this check's rows`() {
        val rows =
            listOf(
                row("PAY-050", Severity.WARN, Disposition.AUTO_RELEASE),
                row("PAY-051", Severity.BLOCK, Disposition.AUTO_RELEASE),
                ing017,
            )
        assertTrue(HoldReleaseChecker.check(rows).passed)
    }
}
