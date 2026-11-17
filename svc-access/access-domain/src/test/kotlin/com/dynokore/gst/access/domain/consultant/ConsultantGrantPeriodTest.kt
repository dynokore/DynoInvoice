package com.dynokore.gst.access.domain.consultant

import com.dynokore.gst.access.domain.consultant.ConsultantGrantPeriod.Choice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** INV-AC-24222 (INV-REQ-2465, VP-69): the period rule of an `implementation.consultant` grant. */
class ConsultantGrantPeriodTest {
    private val goLive = LocalDate.parse("2027-01-10")

    @Test
    fun `INV-AC-24222 the nine calls of the criterion give their outcomes`() {
        val cases =
            listOf(
                Triple(null, true, Choice.Days(30)),
                Triple(null, false, Choice.Days(30)),
                Triple(121, true, Choice.OutOfRange),
                Triple(120, true, Choice.Days(120)),
                Triple(120, false, Choice.NotAvailable),
                Triple(31, false, Choice.NotAvailable),
                Triple(30, false, Choice.Days(30)),
                Triple(0, true, Choice.OutOfRange),
                Triple(-1, true, Choice.OutOfRange),
            )
        cases.forEach { (days, available, expected) ->
            assertEquals(expected, ConsultantGrantPeriod.choose(days, available), "days $days, available $available")
        }
    }

    @Test
    fun `INV-AC-24222 the grant ends on go-live plus the days, not on the approval date plus the days`() {
        assertEquals(LocalDate.parse("2027-02-09"), ConsultantGrantPeriod.validTo(goLive, 30))
    }

    @Test
    fun `INV-AC-24222 the bounds are 1 and 120 under an available VP-69`() {
        assertEquals(Choice.Days(1), ConsultantGrantPeriod.choose(1, true))
        assertEquals(Choice.OutOfRange, ConsultantGrantPeriod.choose(0, true))
        assertEquals(Choice.Days(120), ConsultantGrantPeriod.choose(120, true))
        assertEquals(Choice.OutOfRange, ConsultantGrantPeriod.choose(121, true))
    }
}
