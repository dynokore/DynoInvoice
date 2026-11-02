package com.dynokore.gst.ingestion.domain.schedule

import com.dynokore.gst.ingestion.domain.schedule.ExtractionChoice.Accepted
import com.dynokore.gst.ingestion.domain.schedule.ExtractionChoice.Refused
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExtractionFrequencyTest {
    private fun choose(
        option: ExtractionOption,
        runs: Int? = null,
        fits: Boolean = true,
        available: Boolean = true,
        bounds: Pair<Int, Int> = 2 to 24,
    ) = ExtractionFrequency.choose(option, runs, fits, available, bounds.first, bounds.second)

    @Test
    fun `INV-AC-16256 daily without a run count is the default for every combination`() {
        for (fits in listOf(true, false)) {
            for (available in listOf(true, false)) {
                choose(ExtractionOption.DAILY, null, fits, available) shouldBe Accepted(ExtractionFrequency.DEFAULT)
            }
        }
    }

    @Test
    fun `INV-AC-16256 a planned point refuses weekly and several daily`() {
        choose(ExtractionOption.WEEKLY, available = false, fits = true) shouldBe
            Refused(ExtractionRefusal.POINT_PLANNED)
        choose(ExtractionOption.SEVERAL_DAILY, 4, available = false) shouldBe Refused(ExtractionRefusal.POINT_PLANNED)
    }

    @Test
    fun `INV-AC-16256 weekly needs the window to fit`() {
        choose(ExtractionOption.WEEKLY, fits = false) shouldBe Refused(ExtractionRefusal.WEEKLY_DOES_NOT_FIT)
        choose(ExtractionOption.WEEKLY, fits = true) shouldBe
            Accepted(ExtractionSetting(ExtractionOption.WEEKLY, null))
    }

    @Test
    fun `INV-AC-16256 several daily is bound by the catalogue run count`() {
        choose(ExtractionOption.SEVERAL_DAILY, null) shouldBe Refused(ExtractionRefusal.RUNS_REQUIRED)
        choose(ExtractionOption.SEVERAL_DAILY, 1) shouldBe Refused(ExtractionRefusal.RUNS_OUT_OF_RANGE)
        choose(ExtractionOption.SEVERAL_DAILY, 25) shouldBe Refused(ExtractionRefusal.RUNS_OUT_OF_RANGE)
        choose(ExtractionOption.SEVERAL_DAILY, 2) shouldBe
            Accepted(ExtractionSetting(ExtractionOption.SEVERAL_DAILY, 2))
        choose(ExtractionOption.SEVERAL_DAILY, 24) shouldBe
            Accepted(ExtractionSetting(ExtractionOption.SEVERAL_DAILY, 24))
    }

    @Test
    fun `INV-AC-16256 daily and weekly refuse a run count`() {
        choose(ExtractionOption.DAILY, 3) shouldBe Refused(ExtractionRefusal.RUNS_NOT_ALLOWED)
        choose(ExtractionOption.WEEKLY, 3) shouldBe Refused(ExtractionRefusal.RUNS_NOT_ALLOWED)
    }

    @Test
    fun `INV-AC-16256 invalid bounds throw`() {
        assertThrows<IllegalArgumentException> { choose(ExtractionOption.DAILY, bounds = 0 to 24) }
        assertThrows<IllegalArgumentException> { choose(ExtractionOption.DAILY, bounds = 5 to 4) }
    }

    @Test
    fun `INV-AC-16256 fromKey accepts the exact name only`() {
        for (o in ExtractionOption.entries) ExtractionOption.fromKey(o.name) shouldBe o
        for (bad in listOf("daily", "DAILY ", "")) {
            assertThrows<IllegalArgumentException> { ExtractionOption.fromKey(bad) }
        }
        ExtractionOption.entries.map { it.name } shouldBe listOf("DAILY", "SEVERAL_DAILY", "WEEKLY")
    }

    @Test
    fun `INV-AC-16256 the setting constructor rejects an inconsistent run count`() {
        assertThrows<IllegalArgumentException> { ExtractionSetting(ExtractionOption.DAILY, 3) }
        assertThrows<IllegalArgumentException> { ExtractionSetting(ExtractionOption.SEVERAL_DAILY, null) }
        assertThrows<IllegalArgumentException> { ExtractionSetting(ExtractionOption.SEVERAL_DAILY, 0) }
    }
}
