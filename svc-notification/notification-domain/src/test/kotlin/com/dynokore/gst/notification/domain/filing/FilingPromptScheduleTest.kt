package com.dynokore.gst.notification.domain.filing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class FilingPromptScheduleTest {
    private fun apr(day: Int) = LocalDate.of(2026, 4, day)

    private fun ready(date: LocalDate) = PromptOn(date, PromptKind.PERIOD_READY, null)

    private fun before(
        date: LocalDate,
        days: Int,
    ) = PromptOn(date, PromptKind.DAYS_BEFORE_DUE, days)

    private fun default(
        readyOn: LocalDate,
        due: LocalDate = apr(30),
    ) = FilingPromptSchedule.plan(PromptOption.READY_AND_3_DAYS, emptyList(), readyOn, due)

    private fun custom(
        days: List<Int>,
        readyOn: LocalDate = apr(1),
        due: LocalDate = apr(30),
    ) = FilingPromptSchedule.plan(PromptOption.CUSTOM_DAYS, days, readyOn, due)

    private fun reason(days: List<Int>) = shouldThrow<InvalidCustomDays> { custom(days, apr(20)) }.reason

    private val twoPrompts = listOf(ready(apr(20)), before(apr(27), 3))

    @Test
    fun `INV-AC-2263 default option first ready on the 20th due on the 30th`() {
        default(apr(20)) shouldBe twoPrompts
    }

    @Test
    fun `INV-AC-10442 default gives the ready prompt and 3 days before`() {
        default(apr(20)) shouldBe twoPrompts
    }

    @Test
    fun `INV-AC-10442 custom 5 and 2 gives the 20th 25th and 28th`() {
        custom(listOf(5, 2), apr(20)) shouldBe listOf(ready(apr(20)), before(apr(25), 5), before(apr(28), 2))
    }

    @Test
    fun `INV-AC-10442 each invalid list is refused with the matching reason`() {
        listOf(
            reason(emptyList()),
            reason(listOf(3, 3)),
            reason(listOf(0)),
            reason(listOf(91)),
            reason(listOf(1, 2, 3, 4, 5, 6)),
        ) shouldBe
            listOf(
                InvalidReason.EMPTY,
                InvalidReason.DUPLICATE,
                InvalidReason.OUT_OF_RANGE,
                InvalidReason.OUT_OF_RANGE,
                InvalidReason.TOO_MANY,
            )
    }

    @Test
    fun `INV-AC-10442 first ready on the 28th keeps only the 28th`() {
        default(apr(28)) shouldBe listOf(ready(apr(28)))
    }

    @Test
    fun `INV-AC-934 filing fact recorded on the 21st means nothing is sent on the 27th`() {
        FilingPromptSchedule.promptsDue(twoPrompts, apr(27), true, emptySet()).let {
            it.send shouldBe null
            it.superseded.shouldBeEmpty()
        }
    }

    @Test
    fun `INV-AC-934 with no fact recorded the 27th prompt is sent`() {
        FilingPromptSchedule.promptsDue(twoPrompts, apr(27), false, setOf(ready(apr(20)))).send shouldBe
            before(apr(27), 3)
    }

    @Test
    fun `INV-AC-10443 latest due prompt is sent and the earlier superseded`() {
        val due = FilingPromptSchedule.promptsDue(twoPrompts, apr(28), false, emptySet())
        due.send shouldBe before(apr(27), 3)
        due.superseded shouldBe listOf(ready(apr(20)))
    }

    @Test
    fun `INV-AC-10443 sent 27th prompt sends nothing the next day`() {
        val due = FilingPromptSchedule.promptsDue(twoPrompts, apr(28), false, setOf(before(apr(27), 3)))
        due.send shouldBe null
        due.superseded.shouldBeEmpty()
    }

    @Test
    fun `sent 20th and today the 27th sends the 27th and supersedes nothing`() {
        val due = FilingPromptSchedule.promptsDue(twoPrompts, apr(27), false, setOf(ready(apr(20))))
        due.send shouldBe before(apr(27), 3)
        due.superseded.shouldBeEmpty()
    }

    @Test
    fun `sent 20th and 27th and today the 28th sends nothing`() {
        val sent = setOf(ready(apr(20)), before(apr(27), 3))
        FilingPromptSchedule.promptsDue(twoPrompts, apr(28), false, sent).send shouldBe null
    }

    @Test
    fun `sent 27th makes promptsDue on the 27th empty`() {
        val due = FilingPromptSchedule.promptsDue(twoPrompts, apr(27), false, setOf(before(apr(27), 3)))
        due.send shouldBe null
        due.superseded.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-10443 recorded fact sends nothing and supersedes nothing`() {
        val due = FilingPromptSchedule.promptsDue(twoPrompts, apr(28), true, emptySet())
        due.send shouldBe null
        due.superseded.shouldBeEmpty()
    }

    @Test
    fun `promptsDue with nothing due yet is empty`() {
        val due = FilingPromptSchedule.promptsDue(twoPrompts, apr(19), false, emptySet())
        due.send shouldBe null
        due.superseded.shouldBeEmpty()
    }

    @Test
    fun `first ready on the due-date side keeps one PERIOD_READY prompt`() {
        default(apr(27)) shouldBe listOf(ready(apr(27)))
        default(apr(30)) shouldBe listOf(ready(apr(30)))
        default(LocalDate.of(2026, 5, 1)) shouldBe listOf(ready(LocalDate.of(2026, 5, 1)))
    }

    @Test
    fun `custom 7 and 1 ready on the 1st gives the 1st 23rd and 29th`() {
        custom(listOf(7, 1)) shouldBe listOf(ready(apr(1)), before(apr(23), 7), before(apr(29), 1))
    }

    @Test
    fun `custom days are sorted by date whatever their order`() {
        custom(listOf(1, 7)) shouldBe listOf(ready(apr(1)), before(apr(23), 7), before(apr(29), 1))
    }

    @Test
    fun `custom 10 and 30 against first ready on the 5th`() {
        custom(listOf(10), apr(5)) shouldBe listOf(ready(apr(5)), before(apr(20), 10))
        custom(listOf(30), apr(5)) shouldBe listOf(ready(apr(5)))
    }

    @Test
    fun `a custom prompt on the ready date is dropped`() {
        custom(listOf(10), apr(20)) shouldBe listOf(ready(apr(20)))
    }

    @Test
    fun `month and leap year arithmetic`() {
        default(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 2)).last().date shouldBe LocalDate.of(2026, 3, 30)
        default(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 3, 2)).last().date shouldBe LocalDate.of(2028, 2, 28)
    }

    @Test
    fun `the default option ignores the custom days even when invalid`() {
        FilingPromptSchedule.plan(PromptOption.READY_AND_3_DAYS, listOf(0, 0), apr(20), apr(30)) shouldBe twoPrompts
    }

    @Test
    fun `the accepted extremes`() {
        custom(listOf(1)).map { it.days } shouldBe listOf(null, 1)
        custom(listOf(90), LocalDate.of(2026, 1, 1)).map { it.days } shouldBe listOf(null, 90)
        custom(listOf(1, 2, 3, 4, 5)).map { it.days } shouldBe listOf(null, 5, 4, 3, 2, 1)
    }

    @Test
    fun `the refusals of one list each`() {
        listOf(
            reason(emptyList()),
            reason(listOf(1, 2, 3, 4, 5, 6)),
            reason(listOf(3, 3)),
            reason(listOf(0)),
            reason(listOf(91)),
            reason(listOf(-1)),
        ) shouldBe
            listOf(
                InvalidReason.EMPTY,
                InvalidReason.TOO_MANY,
                InvalidReason.DUPLICATE,
                InvalidReason.OUT_OF_RANGE,
                InvalidReason.OUT_OF_RANGE,
                InvalidReason.OUT_OF_RANGE,
            )
    }

    @Test
    fun `constants follow the VP-16 typed value`() {
        listOf(MIN_DAY, MAX_DAY, MAX_ITEMS) shouldBe listOf(1, 90, 5)
    }

    @Test
    fun `PromptOption has exactly the VP-16 option keys`() {
        PromptOption.entries.map { it.name }.toSet() shouldBe setOf("READY_AND_3_DAYS", "CUSTOM_DAYS")
    }

    @Test
    fun `fromKey accepts the real keys and refuses look-alikes`() {
        PromptOption.fromKey("READY_AND_3_DAYS") shouldBe PromptOption.READY_AND_3_DAYS
        PromptOption.fromKey("CUSTOM_DAYS") shouldBe PromptOption.CUSTOM_DAYS
        listOf("ready_and_3_days", "READY_AND_4_DAYS", "", " CUSTOM_DAYS").forEach {
            shouldThrow<IllegalArgumentException> { PromptOption.fromKey(it) }
        }
    }
}
