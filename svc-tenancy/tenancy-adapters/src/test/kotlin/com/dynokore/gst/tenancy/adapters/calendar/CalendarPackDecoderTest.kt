package com.dynokore.gst.tenancy.adapters.calendar

import com.dynokore.platform.calendarandperiods.CalendarPack
import com.dynokore.platform.calendarandperiods.CalendarPackCoverageException
import com.dynokore.platform.calendarandperiods.FilingFrequency
import com.dynokore.platform.calendarandperiods.FrequencyChange
import com.dynokore.platform.calendarandperiods.FrequencyHistory
import com.dynokore.platform.calendarandperiods.NonBusinessDayRule
import com.dynokore.platform.calendarandperiods.PackPeriodGenerator
import com.dynokore.platform.calendarandperiods.PeriodRequest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/** INV-T-5055: the calendar pack decoder (fixtures are test data, not the Singapore rule). */
class CalendarPackDecoderTest {
    private val mapper = ObjectMapper()
    private val decoder = CalendarPackDecoder(mapper)
    private val quarterly = FilingFrequency("QUARTERLY")
    private val monthly = FilingFrequency("MONTHLY")

    private fun base(): ObjectNode =
        mapper.readTree(
            """
            {"windows":{"QUARTERLY":[
              {"start":"2026-01-01","end":"2026-03-31","returnForm":"F5"},
              {"start":"2026-04-01","end":"2026-06-30","returnForm":"F5"},
              {"start":"2026-07-01","end":"2026-09-30","returnForm":"F5"}],
             "MONTHLY":[
              {"start":"2026-01-01","end":"2026-01-31","returnForm":"F5"},
              {"start":"2026-02-01","end":"2026-02-28","returnForm":"F5"}]},
             "finalReturnForm":"F8",
             "dueRules":{"F5":{"monthsAfterEnd":1,"endOfMonth":true,"daysAfterEnd":0},
                         "F8":{"monthsAfterEnd":0,"endOfMonth":false,"daysAfterEnd":30}},
             "weekend":["SATURDAY","SUNDAY"],
             "holidays":["2026-01-01"]}
            """.trimIndent(),
        ) as ObjectNode

    private fun payload(
        contents: JsonNode,
        packType: String = "calendar",
        version: String = "2026.1",
    ): String {
        val p = mapper.createObjectNode()
        p.put("packIdentifier", "sg-calendar")
        p.put("packType", packType)
        p.put("version", version)
        p.put("effectiveFrom", "2026-01-01")
        p.put("minEngineVersion", "1.0.0")
        val artefact = p.putObject("artefact")
        artefact.putObject("manifest")
        artefact.putObject("checksums")
        artefact.put("signature", "sig")
        artefact.set("contents", contents)
        return p.toString()
    }

    private fun pack(contents: JsonNode): CalendarPack =
        decoder.decode(payload(contents)).shouldBeInstanceOf<CalendarPackDecoding.Decoded>().pack

    private fun refusal(
        contents: JsonNode,
        packType: String = "calendar",
    ): CalendarPackDecoding.Refused =
        decoder.decode(payload(contents, packType)).shouldBeInstanceOf<CalendarPackDecoding.Refused>()

    private fun withThresholdsAndMargin(): ObjectNode {
        val c = base()
        c.putObject("alertThresholds").also {
            it.putArray("MONTHLY").add(5).add(2)
            it.putArray("QUARTERLY").add(10).add(5)
        }
        c.putObject("safetyMargin").put("value", 10).put("unit", "DAYS")
        return c
    }

    private fun request(
        registration: String,
        deregistration: String? = null,
    ) = PeriodRequest(
        LocalDate.parse(registration),
        deregistration?.let(LocalDate::parse),
        FrequencyHistory.of(listOf(FrequencyChange(LocalDate.parse(registration), quarterly))),
        LocalDate.parse("2026-09-30"),
        NonBusinessDayRule.NO_MOVE,
    )

    @Test
    fun `INV-AC-13079 INV-AC-113 INV-AC-153 the decoded pack carries the payload version and generates the periods and forms of its fixture`() {
        val decoded = pack(base())
        decoded.version shouldBe "2026.1"
        val generator = PackPeriodGenerator(decoded)
        val periods = generator.generate(request("2026-01-01"))
        periods.map { listOf(it.start, it.end, it.returnForm, it.dueDate) }.take(2) shouldContainExactly
            listOf(
                listOf(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), "F5", LocalDate.parse("2026-04-30")),
                listOf(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30"), "F5", LocalDate.parse("2026-07-31")),
            )
        val last = generator.generate(request("2026-01-01", "2026-08-15")).last()
        last.start shouldBe LocalDate.parse("2026-07-01")
        last.end shouldBe LocalDate.parse("2026-08-15")
        last.returnForm shouldBe "F8"
    }

    @Test
    fun `INV-AC-13079 a payload of any other pack type is refused, including the look-alikes`() {
        listOf("Calendar", "calendar-pack", "CALENDAR", " calendar", "calendar ", "", "rate").forEach {
            refusal(base(), it).item shouldBe "packType"
        }
        decoder.decode("""{"version":"1","artefact":{"contents":${base()}}}""").shouldBeInstanceOf<CalendarPackDecoding.Refused>().item shouldBe "packType"
        decoder.decode("""{"packType":1,"version":"1"}""").shouldBeInstanceOf<CalendarPackDecoding.Refused>().item shouldBe "packType"
    }

    @Test
    fun `a payload without a version, without contents or not JSON is refused`() {
        decoder.decode("""{"packType":"calendar","artefact":{"contents":${base()}}}""").shouldBeInstanceOf<CalendarPackDecoding.Refused>().item shouldBe "version"
        decoder.decode("""{"packType":"calendar","version":"1","artefact":{}}""").shouldBeInstanceOf<CalendarPackDecoding.Refused>().item shouldBe "artefact.contents"
        decoder.decode("not json").shouldBeInstanceOf<CalendarPackDecoding.Refused>().item shouldBe "payload"
    }

    @Test
    fun `contents that fail the schema are refused naming the item`() {
        val noForm = base().also { it.remove("finalReturnForm") }
        refusal(noForm).reason shouldContain "finalReturnForm"
        val noEnd = base()
        (noEnd.get("windows").get("QUARTERLY").get(0) as ObjectNode).remove("end")
        refusal(noEnd).let {
            it.item shouldContain "windows/QUARTERLY/0"
            it.reason shouldContain "end"
        }
        refusal(mapper.readTree("[]")).item shouldBe "contents"
    }

    @Test
    fun `two overlapping windows of one frequency are refused`() {
        val c = base()
        (c.get("windows").get("QUARTERLY").get(1) as ObjectNode).put("start", "2026-03-31")
        refusal(c).let {
            it.item shouldBe "contents/windows/QUARTERLY"
            it.reason shouldContain "overlap"
        }
    }

    @Test
    fun `a window that ends before it starts is refused`() {
        val c = base()
        (c.get("windows").get("MONTHLY").get(0) as ObjectNode).put("end", "2025-12-31")
        refusal(c).item shouldBe "contents/windows/MONTHLY/0/end"
    }

    @Test
    fun `a window or the final return form with no due rule is refused`() {
        val window = base()
        (window.get("windows").get("MONTHLY").get(1) as ObjectNode).put("returnForm", "F7")
        refusal(window).item shouldBe "contents/windows/MONTHLY/1"
        val final = base()
        final.put("finalReturnForm", "F9")
        refusal(final).item shouldBe "contents/finalReturnForm"
    }

    @Test
    fun `a weekend of all seven days is refused`() {
        val c = base()
        c.putArray("weekend").also { a -> DayOfWeek.entries.forEach { a.add(it.name) } }
        refusal(c).item shouldContain "weekend"
    }

    @Test
    fun `a date that is not a date is refused`() {
        val c = base()
        c.putArray("holidays").add("2026-02-30")
        refusal(c).reason shouldContain "2026-02-30"
    }

    @Test
    fun `INV-AC-13078 INV-AC-109 INV-AC-203 the thresholds of each frequency are returned for its own frequency and the first monthly item is 5`() {
        val decoded = pack(withThresholdsAndMargin())
        decoded.defaultAlertThresholds(monthly) shouldContainExactly listOf(5, 2)
        decoded.defaultAlertThresholds(quarterly) shouldContainExactly listOf(10, 5)
        decoded.defaultAlertThresholds(monthly).first() shouldBe 5
    }

    @Test
    fun `INV-AC-13078 a list that is not strictly descending or a key that is not a window key is refused naming the constructor message`() {
        val ascending = withThresholdsAndMargin()
        (ascending.get("alertThresholds") as ObjectNode).putArray("QUARTERLY").add(5).add(10)
        refusal(ascending).let {
            it.item shouldBe "CalendarPack"
            it.reason shouldContain "not strictly descending"
        }
        val stray = withThresholdsAndMargin()
        (stray.get("alertThresholds") as ObjectNode).putArray("ANNUAL").add(10)
        refusal(stray).let {
            it.item shouldBe "CalendarPack"
            it.reason shouldContain "ANNUAL"
        }
    }

    @Test
    fun `INV-AC-13077 INV-AC-2265 the margin is Duration ofDays 10 for DAYS and Duration ofHours 48 for HOURS and none without it`() {
        pack(withThresholdsAndMargin()).defaultSafetyMargin() shouldBe Duration.ofDays(10)
        val hours = base()
        hours.putObject("safetyMargin").put("value", 48).put("unit", "HOURS")
        pack(hours).defaultSafetyMargin() shouldBe Duration.ofHours(48)
        assertThrows(CalendarPackCoverageException::class.java) { pack(base()).defaultSafetyMargin() }
        assertThrows(CalendarPackCoverageException::class.java) { pack(base()).defaultAlertThresholds(monthly) }
    }

    @Test
    fun `INV-AC-13077 a unit that is neither DAYS nor HOURS is refused`() {
        listOf("WEEKS", "days", "Hours", "").forEach { unit ->
            val c = base()
            c.putObject("safetyMargin").put("value", 1).put("unit", unit)
            refusal(c).item shouldContain "safetyMargin"
        }
    }

    @Test
    fun `an entrySources array is checked by the schema and decodes to a pack equal to the one without it`() {
        val without = withThresholdsAndMargin()
        val with = without.deepCopy()
        with.putArray("entrySources").addObject().put("section", "finalReturnForm").put("sourceClass", "PRIMARY").put("sourceReference", "IRAS")
        sameFields(pack(with), pack(without))
        val broken = without.deepCopy()
        broken.putArray("entrySources").addObject().put("section", "finalReturnForm").put("sourceClass", "primary").put("sourceReference", "IRAS")
        refusal(broken).item shouldContain "entrySources"
    }

    @Test
    fun `an unknown property at the top level, in a window and in a due rule is ignored`() {
        val without = withThresholdsAndMargin()
        listOf("transition", "defaultFrequency").forEach { name ->
            val with = without.deepCopy()
            with.put(name, "SHOULD-NOT-APPEAR")
            (with.get("windows").get("QUARTERLY").get(0) as ObjectNode).put(name, "SHOULD-NOT-APPEAR")
            (with.get("dueRules").get("F5") as ObjectNode).put(name, "SHOULD-NOT-APPEAR")
            val decoded = pack(with)
            sameFields(decoded, pack(without))
            fieldsOf(decoded).toString() shouldContainNot "SHOULD-NOT-APPEAR"
        }
    }

    @Test
    fun `a pinned rule is mapped and the weekend and holidays form the business calendar`() {
        val c = base()
        c.put("pinnedRule", "NEXT_WORKING_DAY")
        val decoded = pack(c)
        decoded.pinnedRule shouldBe NonBusinessDayRule.NEXT_WORKING_DAY
        decoded.calendar.weekend shouldBe setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        decoded.calendar.holidays shouldBe setOf(LocalDate.parse("2026-01-01"))
        pack(base()).pinnedRule shouldBe null
    }

    private fun fieldsOf(p: CalendarPack): List<Any?> =
        listOf(
            p.version,
            listOf(quarterly, monthly).map { p.windowsOf(it) },
            p.finalReturnForm,
            p.dueRules,
            p.calendar.weekend,
            p.calendar.holidays,
            p.pinnedRule,
            listOf(monthly, quarterly).map { runCatching { p.defaultAlertThresholds(it) }.getOrNull() },
            runCatching { p.defaultSafetyMargin() }.getOrNull(),
        )

    private fun sameFields(
        a: CalendarPack,
        b: CalendarPack,
    ) {
        fieldsOf(a) shouldBe fieldsOf(b)
    }

    private infix fun String.shouldContainNot(s: String) {
        (s in this) shouldBe false
    }
}
