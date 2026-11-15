package com.dynokore.gst.access.domain.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PeriodLabelTest {
    @Test
    fun `INV-AC-23350 a quarter label parses and a month label parses as monthly`() {
        assertEquals(PeriodLabel.Quarter("2026Q3"), PeriodLabel.parse("2026Q3"))
        assertEquals(PeriodLabel.Month("2026-09"), PeriodLabel.parse("2026-09"))
    }

    @Test
    fun `INV-AC-23350 look-alike labels are not periods`() {
        listOf(
            "2026H1",
            "2026",
            "2026q3",
            "2026Q5",
            "2026Q31",
            "2026Q3 ",
            " 2026Q3",
            "2026Q3\n",
            "2026-13",
            "2026-9",
            "\uFF12\uFF10\uFF12\uFF16Q3",
            "",
        ).forEach { assertNull(PeriodLabel.parse(it), "[$it]") }
    }
}
