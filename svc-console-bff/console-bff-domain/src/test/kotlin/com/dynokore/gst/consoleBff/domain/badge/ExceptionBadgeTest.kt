package com.dynokore.gst.consoleBff.domain.badge

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ExceptionBadgeTest {
    private val today = LocalDate.of(2026, 11, 26)
    private val facts =
        mapOf(
            "MDM-002" to CodeFacts(CodeSeverity.BLOCK, CodeDisposition.MANUAL, null),
            "MDM-001" to CodeFacts(CodeSeverity.HOLD, CodeDisposition.AUTO_RELEASE, HoldRelease.DATA),
            "SUB-011" to CodeFacts(CodeSeverity.HOLD, CodeDisposition.AUTO_RELEASE, HoldRelease.DATA),
            "PER-011" to CodeFacts(CodeSeverity.HOLD, CodeDisposition.AUTO_RELEASE, HoldRelease.ELECTION),
            "PER-012" to CodeFacts(CodeSeverity.HOLD, CodeDisposition.AUTO_RELEASE, HoldRelease.ELECTION),
            "PAY-016" to CodeFacts(CodeSeverity.HOLD, CodeDisposition.AUTO_RELEASE, HoldRelease.ELECTION),
            "ING-017" to CodeFacts(CodeSeverity.HOLD, CodeDisposition.MANUAL, null),
            "HLD-000" to CodeFacts(CodeSeverity.HOLD, CodeDisposition.AUTO_RELEASE, null),
            "SUB-005" to CodeFacts(CodeSeverity.WARN, CodeDisposition.MANUAL, null),
            "SUB-001" to CodeFacts(CodeSeverity.WARN, CodeDisposition.AUTO_RETRY, null),
            "INF-001" to CodeFacts(CodeSeverity.INFO, null, null),
        )

    private fun item(
        code: String,
        owner: String = "Finance",
        entity: String = "e1",
        state: ItemState = ItemState.OPEN,
        due: LocalDate? = null,
    ) = BadgeItem(entity, code, owner, state, due)

    private fun count(vararg items: BadgeItem) = exceptionBadge(items.toList(), facts, today, 3).count

    private fun urgent(due: LocalDate?) = exceptionBadge(listOf(item("MDM-002", due = due)), facts, today, 3).urgent

    @Test
    fun `INV-AC-15817 400 open items of one group count once`() {
        val items = (1..400).map { item("MDM-002", entity = "doc-$it") }
        exceptionBadge(items, facts, today, 3).count shouldBe 1
    }

    @Test
    fun `INV-AC-15817 and INV-AC-15818 DATA holds are not counted`() {
        count(item("MDM-001"), item("SUB-011")) shouldBe 0
    }

    @Test
    fun `INV-AC-15818 ELECTION holds are counted`() {
        count(item("PER-011", "Onboarding"), item("PER-012", "Onboarding"), item("PAY-016", "Onboarding")) shouldBe 3
    }

    @Test
    fun `INV-AC-15817 manual HOLD counts`() {
        count(item("ING-017")) shouldBe 1
    }

    @Test
    fun `INV-AC-15818 a hold with no kind is counted`() {
        count(item("HLD-000")) shouldBe 1
    }

    @Test
    fun `INV-AC-15817 WARN manual counts and WARN auto retry does not`() {
        count(item("SUB-005")) shouldBe 1
        count(item("SUB-001")) shouldBe 0
    }

    @Test
    fun `INV-AC-15817 INFO is not counted`() {
        count(item("INF-001")) shouldBe 0
    }

    @Test
    fun `INV-AC-15817 groups are code and owner whatever the entity`() {
        count(item("MDM-002", "Finance"), item("MDM-002", "Ops")) shouldBe 2
        count(item("MDM-002", entity = "a"), item("MDM-002", entity = "b")) shouldBe 1
    }

    @Test
    fun `INV-AC-15817 a code missing from facts is counted and codes are case sensitive`() {
        count(item("ZZZ-999")) shouldBe 1
        count(item("mdm-001")) shouldBe 1
    }

    @Test
    fun `INV-AC-15817 resolved and overridden items are not counted`() {
        count(item("MDM-002", state = ItemState.RESOLVED), item("MDM-002", state = ItemState.OVERRIDDEN)) shouldBe 0
    }

    @Test
    fun `INV-AC-15817 urgency is within the threshold from today`() {
        urgent(LocalDate.of(2026, 11, 29)) shouldBe true
        urgent(LocalDate.of(2026, 11, 30)) shouldBe false
        urgent(LocalDate.of(2026, 11, 20)) shouldBe true
        urgent(null) shouldBe false
    }

    @Test
    fun `INV-AC-15817 resolved items do not make the badge urgent`() {
        val r =
            exceptionBadge(
                listOf(item("MDM-002", state = ItemState.RESOLVED, due = today)),
                facts,
                today,
                3,
            )
        r shouldBe BadgeResult(0, false)
    }

    @Test
    fun `INV-AC-15817 no items give count 0 and not urgent`() {
        exceptionBadge(emptyList(), facts, today, 3) shouldBe BadgeResult(0, false)
    }

    @Test
    fun `INV-AC-15817 a recessive group makes the badge urgent while uncounted`() {
        val r = exceptionBadge(listOf(item("MDM-001", due = LocalDate.of(2026, 11, 27))), facts, today, 3)
        r shouldBe BadgeResult(0, true)
    }

    @Test
    fun `INV-AC-15817 input order does not matter`() {
        val items =
            listOf(
                item("MDM-002"),
                item("PER-011", "Onboarding", due = LocalDate.of(2026, 11, 27)),
                item("MDM-001"),
                item("SUB-005", "Ops"),
            )
        exceptionBadge(items.reversed(), facts, today, 3) shouldBe exceptionBadge(items, facts, today, 3)
    }

    @Test
    fun `INV-AC-15817 threshold 0 and -1 are refused`() {
        shouldThrow<IllegalArgumentException> { exceptionBadge(emptyList(), facts, today, 0) }
        shouldThrow<IllegalArgumentException> { exceptionBadge(emptyList(), facts, today, -1) }
    }
}
