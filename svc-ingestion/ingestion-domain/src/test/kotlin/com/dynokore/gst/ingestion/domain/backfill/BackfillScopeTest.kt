package com.dynokore.gst.ingestion.domain.backfill

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate

class BackfillScopeTest {
    private val activation = LocalDate.of(2026, 3, 1)
    private val affected = DatePeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
    private val current = DatePeriod(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))

    private fun decide(
        s: BackfillSituation,
        d: LocalDate,
        a: DatePeriod? = affected,
    ) = BackfillScope.decide(s, d, activation, a, current)

    private fun refused(r: BackfillRefusal) = BackfillDecision.Refused(r)

    @Test
    fun `INV-AC-17991 early adoption accepts the activation date and refuses the day before`() {
        decide(BackfillSituation.EARLY_ADOPTION, LocalDate.of(2026, 3, 1)) shouldBe BackfillDecision.Accepted
        decide(BackfillSituation.EARLY_ADOPTION, LocalDate.of(2026, 2, 28)) shouldBe
            refused(BackfillRefusal.BEFORE_ACTIVATION)
    }

    @Test
    fun `INV-AC-17991 early adoption does not read the affected period`() {
        decide(BackfillSituation.EARLY_ADOPTION, LocalDate.of(2026, 3, 1), null) shouldBe BackfillDecision.Accepted
        decide(BackfillSituation.EARLY_ADOPTION, LocalDate.of(2026, 3, 1), affected) shouldBe BackfillDecision.Accepted
    }

    @Test
    fun `INV-AC-17991 silent failure correction accepts both ends of the affected period`() {
        decide(BackfillSituation.SILENT_FAILURE_CORRECTION, LocalDate.of(2026, 1, 1)) shouldBe BackfillDecision.Accepted
        decide(BackfillSituation.SILENT_FAILURE_CORRECTION, LocalDate.of(2026, 1, 31)) shouldBe
            BackfillDecision.Accepted
    }

    @Test
    fun `INV-AC-17991 silent failure correction refuses dates outside the affected period`() {
        decide(BackfillSituation.SILENT_FAILURE_CORRECTION, LocalDate.of(2025, 12, 31)) shouldBe
            refused(BackfillRefusal.OUTSIDE_AFFECTED_PERIOD)
        decide(BackfillSituation.SILENT_FAILURE_CORRECTION, LocalDate.of(2026, 2, 1)) shouldBe
            refused(BackfillRefusal.OUTSIDE_AFFECTED_PERIOD)
    }

    @Test
    fun `INV-AC-17991 silent failure correction refuses a null affected period`() {
        decide(BackfillSituation.SILENT_FAILURE_CORRECTION, LocalDate.of(2026, 1, 15), null) shouldBe
            refused(BackfillRefusal.AFFECTED_PERIOD_REQUIRED)
    }

    @Test
    fun `INV-AC-17991 mid period migration accepts both ends of the current period`() {
        decide(BackfillSituation.MID_PERIOD_MIGRATION, LocalDate.of(2026, 7, 1)) shouldBe BackfillDecision.Accepted
        decide(BackfillSituation.MID_PERIOD_MIGRATION, LocalDate.of(2026, 7, 31)) shouldBe BackfillDecision.Accepted
    }

    @Test
    fun `INV-AC-17991 mid period migration refuses dates outside the current period`() {
        decide(BackfillSituation.MID_PERIOD_MIGRATION, LocalDate.of(2026, 6, 30)) shouldBe
            refused(BackfillRefusal.OUTSIDE_CURRENT_PERIOD)
        decide(BackfillSituation.MID_PERIOD_MIGRATION, LocalDate.of(2026, 8, 1)) shouldBe
            refused(BackfillRefusal.OUTSIDE_CURRENT_PERIOD)
    }

    @Test
    fun `INV-AC-17991 mid period migration does not read the affected period`() {
        decide(BackfillSituation.MID_PERIOD_MIGRATION, LocalDate.of(2026, 7, 1), null) shouldBe
            BackfillDecision.Accepted

        decide(BackfillSituation.MID_PERIOD_MIGRATION, LocalDate.of(2026, 7, 1), affected) shouldBe
            BackfillDecision.Accepted
    }

    @Test
    fun `INV-AC-17991 date period requires start not after end`() {
        shouldThrow<IllegalArgumentException> { DatePeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 31)) }
        DatePeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)).start shouldBe LocalDate.of(2026, 2, 1)
    }

    @Test
    fun `INV-AC-17991 fromKey accepts only the exact member name`() {
        BackfillSituation.entries.forEach { BackfillSituation.fromKey(it.name) shouldBe it }
        listOf("early_adoption", "EARLY_ADOPTION ", "").forEach {
            shouldThrow<IllegalArgumentException> { BackfillSituation.fromKey(it) }
        }
    }

    @Test
    fun `INV-AC-17991 situations are the three in order`() {
        BackfillSituation.entries.map { it.name } shouldBe
            listOf("EARLY_ADOPTION", "SILENT_FAILURE_CORRECTION", "MID_PERIOD_MIGRATION")
    }

    @Test
    fun `INV-AC-17991 main source reads no literal date, parse or time source`() {
        val file = File("src/main/kotlin/com/dynokore/gst/ingestion/domain/backfill/BackfillScope.kt")
        check(file.isFile && file.canRead()) { "cannot read ${file.path}" }
        val text = file.readText()
        listOf("LocalDate.of(", "LocalDate.parse(", "Clock", "now()").forEach {
            check(!text.contains(it)) { "BackfillScope.kt must not contain $it" }
        }
    }
}
