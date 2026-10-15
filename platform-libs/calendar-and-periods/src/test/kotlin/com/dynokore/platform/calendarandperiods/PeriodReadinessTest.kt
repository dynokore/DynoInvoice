package com.dynokore.platform.calendarandperiods

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Readiness gating is a pure function of the completeness controls. The control inputs are Wave 3 (fixtures here). */
class PeriodReadinessTest {
    @Test
    fun `all controls complete gives ready`() {
        val verdict =
            gatePeriodReadiness(
                listOf(CompletenessControl("DOC-COMPLETE", true), CompletenessControl("REC-COMPLETE", true)),
            )

        assertEquals(ReadinessVerdict.Ready, verdict)
    }

    @Test
    fun `an incomplete control blocks and names its code`() {
        val verdict =
            gatePeriodReadiness(
                listOf(
                    CompletenessControl("DOC-COMPLETE", true),
                    CompletenessControl("REC-COMPLETE", false),
                    CompletenessControl("ADJ-COMPLETE", false),
                ),
            )

        assertEquals(ReadinessVerdict.Blocked(listOf("REC-COMPLETE", "ADJ-COMPLETE")), verdict)
    }

    @Test
    fun `the same input gives the same verdict`() {
        val controls = listOf(CompletenessControl("A", false))

        assertEquals(gatePeriodReadiness(controls), gatePeriodReadiness(controls))
    }

    @Test
    fun `an empty control set is refused, never ready`() {
        assertThrows(IllegalArgumentException::class.java) { gatePeriodReadiness(emptyList()) }
    }

    @Test
    fun `a blank control code is refused`() {
        assertThrows(IllegalArgumentException::class.java) { CompletenessControl(" ", true) }
    }
}
