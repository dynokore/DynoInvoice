package com.dynokore.gst.submission.domain.schedule

import com.dynokore.gst.submission.domain.schedule.DespatchChoice.Accepted
import com.dynokore.gst.submission.domain.schedule.DespatchChoice.Refused
import com.dynokore.gst.submission.domain.schedule.DespatchOption.CONTINUOUS
import com.dynokore.gst.submission.domain.schedule.DespatchOption.DAILY_WINDOW
import com.dynokore.gst.submission.domain.schedule.DespatchOption.WEEKLY_WINDOW
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DespatchWindowSettingTest {
    @Test
    fun `INV-AC-16257 continuous and daily are accepted whether or not the weekly window fits`() {
        listOf(true, false).forEach {
            DespatchWindowSetting.choose(CONTINUOUS, it) shouldBe Accepted(CONTINUOUS)
            DespatchWindowSetting.choose(DAILY_WINDOW, it) shouldBe Accepted(DAILY_WINDOW)
        }
    }

    @Test
    fun `INV-AC-16257 weekly is accepted only when it fits`() {
        DespatchWindowSetting.choose(WEEKLY_WINDOW, true) shouldBe Accepted(WEEKLY_WINDOW)
        DespatchWindowSetting.choose(WEEKLY_WINDOW, false) shouldBe Refused(DespatchRefusal.WEEKLY_DOES_NOT_FIT)
    }

    @Test
    fun `INV-AC-16257 the default is continuous`() {
        DespatchWindowSetting.DEFAULT shouldBe CONTINUOUS
    }

    @Test
    fun `INV-AC-16257 fromKey takes the exact member name only`() {
        DespatchOption.entries.forEach { DespatchOption.fromKey(it.name) shouldBe it }
        listOf("continuous", "CONTINUOUS ", "").forEach {
            shouldThrow<IllegalArgumentException> { DespatchOption.fromKey(it) }
        }
    }

    @Test
    fun `INV-AC-16257 the options and the refusal are exactly the catalogue members`() {
        DespatchOption.entries.map { it.name } shouldBe listOf("CONTINUOUS", "DAILY_WINDOW", "WEEKLY_WINDOW")
        DespatchRefusal.entries.map { it.name } shouldBe listOf("WEEKLY_DOES_NOT_FIT")
    }
}
