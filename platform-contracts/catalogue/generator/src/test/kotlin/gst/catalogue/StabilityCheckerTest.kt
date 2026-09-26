package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves the INV-AC-2170 comparator itself, against small authored fixtures —
 * independent of whether a real previous train exists yet (there is no earlier
 * published gst-catalogue train: this is 1.0.0). Same "prove the gate itself works"
 * approach as platform-contracts' own breaking-change-self-test.
 */
class StabilityCheckerTest {
    private fun row(
        code: String = "ING-001",
        condition: String = "Unknown sourceSystem",
        severity: String = "BLOCK",
        disposition: String? = "TERMINAL",
        revisionNote: String? = null,
    ) = CatalogueRow(code, condition, severity, disposition, revisionNote)

    @Test
    fun `an unchanged catalogue has no violations`() {
        val violations = StabilityChecker.check(listOf(row()), listOf(row()))
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `a pure addition is not a violation`() {
        val previous = listOf(row())
        val current = listOf(row(), row(code = "ING-002", condition = "New condition"))
        assertTrue(StabilityChecker.check(previous, current).isEmpty())
    }

    @Test
    fun `a code missing from the later train is a violation`() {
        val violations = StabilityChecker.check(listOf(row()), emptyList())
        assertEquals(1, violations.size)
        assertEquals("ING-001", violations.single().code)
    }

    @Test
    fun `the same code with a changed condition is a repurposing`() {
        val previous = listOf(row(condition = "Unknown sourceSystem"))
        val current = listOf(row(condition = "A completely different condition"))
        val violations = StabilityChecker.check(previous, current)
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("condition changed"))
    }

    @Test
    fun `a severity or disposition change with no revision note is a violation`() {
        val previous = listOf(row(severity = "BLOCK", disposition = "AUTO_RETRY", revisionNote = null))
        val current = listOf(row(severity = "BLOCK", disposition = "MANUAL", revisionNote = null))
        val violations = StabilityChecker.check(previous, current)
        assertEquals(1, violations.size)
        assertTrue(violations.single().reason.contains("no revision note"))
    }

    @Test
    fun `a severity or disposition change carrying a revision note passes, as for SUB-010`() {
        val previous = listOf(row(code = "RUL-005", severity = "BLOCK", disposition = "AUTO_RETRY"))
        val current =
            listOf(
                row(
                    code = "RUL-005",
                    severity = "BLOCK",
                    disposition = "MANUAL",
                    revisionNote = "Amended 25 Sep 2026 (Keel INV-Q-2132); previously AUTO_RETRY",
                ),
            )
        assertTrue(StabilityChecker.check(previous, current).isEmpty())
    }
}
