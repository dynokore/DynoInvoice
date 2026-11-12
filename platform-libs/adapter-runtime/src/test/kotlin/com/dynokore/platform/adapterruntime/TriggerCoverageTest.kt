package com.dynokore.platform.adapterruntime

import com.dynokore.platform.adapterruntime.TriggerCoverageEntry.CoveredElsewhere
import com.dynokore.platform.adapterruntime.TriggerCoverageEntry.Mapped
import com.dynokore.platform.adapterruntime.TriggerCoverageEntry.Waived
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TriggerCoverageTest {
    private val all = TriggerClass.entries
    private val review = LocalDate.of(2027, 1, 31)
    private val mapped = Mapped("erp-ar")

    private fun full(vararg over: Pair<TriggerClass, TriggerCoverageEntry>) =
        all.associateWith<TriggerClass, TriggerCoverageEntry> { mapped } + over

    @Test
    fun `exactly the five classes exist in order`() {
        all.map { it.name } shouldBe
            listOf(
                "OUTBOUND_ISSUED",
                "INBOUND_RECORDED",
                "AGGREGABLE_BATCH_CLOSED",
                "JOURNAL_ONLY_SUPPLY",
                "THIRD_PARTY_ISSUANCE",
            )
    }

    @Test
    fun `one absent class is named, for each class`() {
        all.forEach { absent ->
            TriggerCoverage.evaluate(full() - absent) shouldBe CoverageVerdict.Incomplete(listOf(absent))
        }
    }

    @Test
    fun `empty map names all five in declaration order`() {
        TriggerCoverage.evaluate(emptyMap()) shouldBe CoverageVerdict.Incomplete(all)
    }

    @Test
    fun `blank adapter id is refused`() {
        shouldThrow<IllegalArgumentException> { Mapped("") }
        shouldThrow<IllegalArgumentException> { Mapped("  ") }
    }

    @Test
    fun `blank and whitespace-only waiver reason is refused`() {
        shouldThrow<IllegalArgumentException> { Waived("", review) }
        shouldThrow<IllegalArgumentException> { Waived(" \t\n", review) }
        Waived("no GL feed", review).reason shouldBe "no GL feed"
    }

    @Test
    fun `INV-AC-20901 covered elsewhere keeps evidence and acting user id`() {
        val entry = CoveredElsewhere("ev-1", "alice")
        entry.acknowledgedBy shouldBe "alice"
        entry.evidenceRef shouldBe "ev-1"
    }

    @Test
    fun `INV-AC-20901 covered elsewhere refuses blank evidence or blank acting user id`() {
        shouldThrow<IllegalArgumentException> { CoveredElsewhere("", "alice") }
        shouldThrow<IllegalArgumentException> { CoveredElsewhere(" ", "alice") }
        shouldThrow<IllegalArgumentException> { CoveredElsewhere("ev-1", " ") }
        shouldThrow<IllegalArgumentException> { CoveredElsewhere("ev-1", "") }
    }

    @Test
    fun `INV-AC-20901 covered elsewhere is complete but not a system class, waived is`() {
        val elsewhere = CoveredElsewhere("ev-1", "alice")
        TriggerCoverage.evaluate(full(TriggerClass.INBOUND_RECORDED to elsewhere)) shouldBe
            CoverageVerdict.Complete(all.toSet() - TriggerClass.INBOUND_RECORDED)
        TriggerCoverage.evaluate(full(TriggerClass.JOURNAL_ONLY_SUPPLY to Waived("none", review))) shouldBe
            CoverageVerdict.Complete(all.toSet())
    }

    @Test
    fun `waivers are re-presented at checks 1 to 4 only`() {
        val entries = full(TriggerClass.JOURNAL_ONLY_SUPPLY to Waived("none", review))
        listOf(1, 2, 3, 4).forEach {
            TriggerCoverage.waiversToRepresent(entries, it) shouldBe setOf(TriggerClass.JOURNAL_ONLY_SUPPLY)
        }
        TriggerCoverage.waiversToRepresent(entries, 5) shouldBe emptySet()
        TriggerCoverage.waiversToRepresent(entries, 40) shouldBe emptySet()
        shouldThrow<IllegalArgumentException> { TriggerCoverage.waiversToRepresent(entries, 0) }
        shouldThrow<IllegalArgumentException> { TriggerCoverage.waiversToRepresent(entries, -1) }
    }
}
