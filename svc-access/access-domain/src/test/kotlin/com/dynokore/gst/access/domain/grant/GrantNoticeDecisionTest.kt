package com.dynokore.gst.access.domain.grant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class GrantNoticeDecisionTest {
    private fun n(
        days: Int,
        date: String,
    ) = ExpiryNotice(days, LocalDate.parse(date))

    private val ending20 =
        NoticeSchedule(LocalDate.parse("2026-11-20"), listOf(n(14, "2026-11-06"), n(3, "2026-11-17")))

    private fun decide(
        schedule: NoticeSchedule,
        today: String,
        recorded: Set<Int> = emptySet(),
    ) = GrantNoticeDecision.decide(schedule, LocalDate.parse(today), recorded)

    @Test
    fun `INV-AC-13315 created on the 19th sends the 17th notice and supersedes the 6th`() {
        val d = decide(ending20, "2026-11-19")
        assertEquals(listOf(n(3, "2026-11-17")), d.send)
        assertEquals(listOf(n(14, "2026-11-06")), d.superseded)
        assertEquals(LocalDate.parse("2026-11-20"), d.expiryDate)
    }

    @Test
    fun `INV-AC-13315 created on the 10th sends the 6th notice and leaves the 17th`() {
        val d = decide(ending20, "2026-11-10")
        assertEquals(listOf(n(14, "2026-11-06")), d.send)
        assertEquals(emptyList<ExpiryNotice>(), d.superseded)
    }

    @Test
    fun `INV-AC-13315 before every notice date nothing is decided`() {
        val d = decide(ending20, "2026-11-05")
        assertEquals(emptyList<ExpiryNotice>(), d.send)
        assertEquals(emptyList<ExpiryNotice>(), d.superseded)
    }

    @Test
    fun `INV-AC-13315 on a notice date itself one message is sent`() {
        val d = decide(ending20, "2026-11-17")
        assertEquals(listOf(n(3, "2026-11-17")), d.send)
        assertEquals(listOf(n(14, "2026-11-06")), d.superseded)
    }

    @Test
    fun `INV-AC-13315 a recorded day is not decided again`() {
        val d = decide(ending20, "2026-11-19", setOf(14))
        assertEquals(listOf(n(3, "2026-11-17")), d.send)
        assertEquals(emptyList<ExpiryNotice>(), d.superseded)
    }

    @Test
    fun `INV-AC-13315 a second decision with both days recorded returns nothing`() {
        val d = decide(ending20, "2026-11-19", setOf(14, 3))
        assertEquals(emptyList<ExpiryNotice>(), d.send)
        assertEquals(emptyList<ExpiryNotice>(), d.superseded)
    }

    @Test
    fun `INV-AC-13315 a recorded 14 on the 10th leaves nothing and the 3 notice untouched`() {
        val d = decide(ending20, "2026-11-10", setOf(14))
        assertEquals(emptyList<ExpiryNotice>(), d.send)
        assertEquals(emptyList<ExpiryNotice>(), d.superseded)
    }

    @Test
    fun `INV-AC-13315 a recorded day outside the schedule is ignored`() {
        val d = decide(ending20, "2026-11-19", setOf(99))
        assertEquals(listOf(n(3, "2026-11-17")), d.send)
        assertEquals(listOf(n(14, "2026-11-06")), d.superseded)
    }

    @Test
    fun `INV-AC-13315 three days leave the notice still ahead in neither list`() {
        val s =
            NoticeSchedule(
                LocalDate.parse("2026-11-28"),
                listOf(n(21, "2026-11-07"), n(7, "2026-11-21"), n(3, "2026-11-25")),
            )
        val d = decide(s, "2026-11-23")
        assertEquals(listOf(n(7, "2026-11-21")), d.send)
        assertEquals(listOf(n(21, "2026-11-07")), d.superseded)
    }

    @Test
    fun `INV-AC-13315 four days supersede in ascending notice date order`() {
        val s =
            NoticeSchedule(
                LocalDate.parse("2026-11-28"),
                listOf(n(21, "2026-11-07"), n(14, "2026-11-14"), n(7, "2026-11-21"), n(3, "2026-11-25")),
            )
        val d = decide(s, "2026-11-23")
        assertEquals(listOf(n(7, "2026-11-21")), d.send)
        assertEquals(listOf(n(21, "2026-11-07"), n(14, "2026-11-14")), d.superseded)
    }
}
