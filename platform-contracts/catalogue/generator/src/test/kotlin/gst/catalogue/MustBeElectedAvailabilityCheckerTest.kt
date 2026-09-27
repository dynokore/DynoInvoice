package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MustBeElectedAvailabilityCheckerTest {
    private fun vp(
        id: String,
        requirement: String,
        mustBeElected: Boolean,
        status: VpStatus,
    ) = ParsedVariationPoint(
        id = id,
        requirement = requirement,
        revision = "x",
        title = "t",
        vpClass = VpClass.POLICY,
        classModifiers = emptyList(),
        scope = listOf("tenant"),
        owner = "o",
        setBy = listOf("tenant.admin"),
        mustBeElected = mustBeElected,
        default = if (mustBeElected) null else "d",
        options = listOf("a", "b"),
        bounds = null,
        status = status,
        statusNote = null,
        governs = emptyList(),
        formDependence = null,
    )

    @Test
    fun `a must-be-elected VP that is available passes`() {
        val rows = listOf(vp("VP-22", "INV-REQ-1283", mustBeElected = true, status = VpStatus.AVAILABLE))
        assertTrue(MustBeElectedAvailabilityChecker.check(rows).isEmpty())
    }

    @Test
    fun `a must-be-elected VP that is only planned fails`() {
        val rows = listOf(vp("VP-22", "INV-REQ-1283", mustBeElected = true, status = VpStatus.PLANNED))
        val violations = MustBeElectedAvailabilityChecker.check(rows)
        assertEquals(1, violations.size)
        assertEquals("VP-22", violations.single().id)
    }

    @Test
    fun `a VP named by INV-AC-2236 as closing an open question must be available even if not must-be-elected`() {
        val rows = listOf(vp("VP-48", "INV-REQ-1309", mustBeElected = false, status = VpStatus.PLANNED))
        val violations = MustBeElectedAvailabilityChecker.check(rows)
        assertEquals(1, violations.size)
    }

    @Test
    fun `an ordinary planned VP not named by the AC and not must-be-elected is not checked`() {
        val rows = listOf(vp("VP-02", "INV-REQ-1263", mustBeElected = false, status = VpStatus.PLANNED))
        assertTrue(MustBeElectedAvailabilityChecker.check(rows).isEmpty())
    }

    @Test
    fun `every requirement INV-AC-2236 names is in the checker's own named set`() {
        val expected =
            setOf(
                "INV-REQ-1309", "INV-REQ-1310", "INV-REQ-1311", "INV-REQ-1312",
                "INV-REQ-1313", "INV-REQ-1314", "INV-REQ-1315", "INV-REQ-1323",
            )
        assertEquals(expected, MustBeElectedAvailabilityChecker.namedQuestionClosingRequirements)
    }
}
