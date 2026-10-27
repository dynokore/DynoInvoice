package com.dynokore.gst.notification.domain.notice

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class FilingNoticeTest {
    private val day = LocalDate.of(2026, 3, 30)
    private val at = Instant.parse("2026-03-31T15:59:59Z")

    private fun decide(
        option: NoticeOption,
        byAgent: Boolean = true,
        on: Instant = at,
    ) = FilingNoticeRoute.decide(option, byAgent, on)

    @Test
    fun `INV-AC-2257 an agent-recorded fact under IMMEDIATE_NOTICE is SendNow false`() {
        decide(NoticeOption.IMMEDIATE_NOTICE) shouldBe NoticeRoute.SendNow(false)
    }

    @Test
    fun `INV-AC-10460 ACKNOWLEDGED_NOTICE is SendNow true`() {
        decide(NoticeOption.ACKNOWLEDGED_NOTICE) shouldBe NoticeRoute.SendNow(true)
    }

    @Test
    fun `INV-AC-10460 a fact not recorded by the agent is NoNotice under all options`() {
        NoticeOption.entries.forEach { decide(it, byAgent = false) shouldBe NoticeRoute.NoNotice }
    }

    @Test
    fun `INV-AC-10460 DAILY_DIGEST covers the Singapore day`() {
        decide(NoticeOption.DAILY_DIGEST, on = Instant.parse("2026-03-31T15:59:59Z")) shouldBe
            NoticeRoute.AddToDigest(LocalDate.of(2026, 3, 31))
        decide(NoticeOption.DAILY_DIGEST, on = Instant.parse("2026-03-31T16:00:00Z")) shouldBe
            NoticeRoute.AddToDigest(LocalDate.of(2026, 4, 1))
    }

    @Test
    fun `INV-AC-1947 INV-AC-10461 the notice components are exactly date reference and count`() {
        componentNames(FilingNotice::class.java) shouldBe setOf("filingDate", "filingReference", "lateDocumentCount")
    }

    @Test
    fun `INV-AC-1947 a look-alike with a documentNumbers component fails the same assertion`() {
        data class LookAlike(
            val filingDate: LocalDate,
            val filingReference: String,
            val lateDocumentCount: Int,
            val documentNumbers: List<String>,
        )
        (componentNames(LookAlike::class.java) == setOf("filingDate", "filingReference", "lateDocumentCount")) shouldBe
            false
    }

    @Test
    fun `INV-AC-929 INV-AC-10461 a notice for 2 late holds the count 2`() {
        FilingNotice(day, "IRAS-123", 2).lateDocumentCount shouldBe 2
    }

    @Test
    fun `INV-AC-10461 a blank reference and a count of -1 are refused`() {
        shouldThrow<IllegalArgumentException> { FilingNotice(day, " ", 2) }
        shouldThrow<IllegalArgumentException> { FilingNotice(day, "IRAS-123", -1) }
    }

    @Test
    fun `the option set is exactly the VP-12 keys`() {
        NoticeOption.entries.map { it.name }.toSet() shouldBe
            setOf("IMMEDIATE_NOTICE", "ACKNOWLEDGED_NOTICE", "DAILY_DIGEST")
    }

    @Test
    fun `fromKey accepts the three keys and refuses look-alikes`() {
        NoticeOption.entries.forEach { NoticeOption.fromKey(it.name) shouldBe it }
        listOf("immediate_notice", "IMMEDIATE_NOTICES", "").forEach {
            shouldThrow<IllegalArgumentException> { NoticeOption.fromKey(it) }
        }
    }

    private fun componentNames(type: Class<*>): Set<String> =
        type.declaredFields
            .filter {
                !java.lang.reflect.Modifier
                    .isStatic(it.modifiers)
            }.map { it.name }
            .toSet()
}
