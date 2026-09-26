package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlockDispositionCheckerTest {
    private fun blockRow(
        code: String,
        disposition: Disposition?,
    ) = ParsedErrorCode(
        code = code,
        area = code.substringBefore('-'),
        number = code.substringAfter('-').toInt(),
        conditionRaw = "condition",
        conditionCore = "condition",
        revisionNote = null,
        severity = Severity.BLOCK,
        severityRaw = "BLOCK",
        disposition = disposition,
        dispositionRaw = disposition?.name ?: "",
        owner = "Platform",
    )

    @Test
    fun `a Keel-named own-control code with OVERRIDABLE passes classified`() {
        val result = BlockDispositionChecker.check(listOf(blockRow("MDM-003", Disposition.OVERRIDABLE)))
        assertTrue(result.passed)
        assertEquals(1, result.classified.size)
        assertTrue(result.pendingDecision.isEmpty())
    }

    @Test
    fun `a Keel-named fix-at-source code with MANUAL passes classified`() {
        val result = BlockDispositionChecker.check(listOf(blockRow("PAY-001", Disposition.MANUAL)))
        assertTrue(result.passed)
    }

    @Test
    fun `a Keel-named structural-invalidity code with TERMINAL passes classified`() {
        val result = BlockDispositionChecker.check(listOf(blockRow("SUB-006", Disposition.TERMINAL)))
        assertTrue(result.passed)
    }

    @Test
    fun `RUL-005's FR-O9 reclassification to MANUAL passes classified`() {
        val result = BlockDispositionChecker.check(listOf(blockRow("RUL-005", Disposition.MANUAL)))
        assertTrue(result.passed)
    }

    @Test
    fun `a Keel-named code with the wrong disposition is a violation`() {
        // MDM-003 is classified own-control (INV-REQ-1186): OVERRIDABLE is called for, not MANUAL.
        val result = BlockDispositionChecker.check(listOf(blockRow("MDM-003", Disposition.MANUAL)))
        assertTrue(!result.passed)
        assertEquals(1, result.violations.size)
    }

    @Test
    fun `a BLOCK code with AUTO_RETRY or AUTO_RELEASE is always a violation, classified or not`() {
        val result = BlockDispositionChecker.check(listOf(blockRow("MDM-003", Disposition.AUTO_RETRY)))
        assertTrue(!result.passed)
    }

    @Test
    fun `a BLOCK code outside the Keel-named set is listed for decision, not failed`() {
        val result = BlockDispositionChecker.check(listOf(blockRow("ING-001", Disposition.TERMINAL)))
        assertTrue(result.passed)
        assertEquals(1, result.pendingDecision.size)
        assertTrue(result.classified.isEmpty())
    }

    @Test
    fun `non-BLOCK rows are ignored entirely`() {
        val warnRow = blockRow("ING-010", Disposition.MANUAL).copy(severity = Severity.WARN)
        val result = BlockDispositionChecker.check(listOf(warnRow))
        assertTrue(result.passed)
        assertTrue(result.pendingDecision.isEmpty())
        assertTrue(result.classified.isEmpty())
    }
}
