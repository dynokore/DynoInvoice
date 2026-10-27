package com.dynokore.gst.submission.domain.gate

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FilingGateTest {
    private val c1 = LatestCheck("c1", CheckVerdict.NOT_READY)
    private val c2 = LatestCheck("c2", CheckVerdict.NOT_READY)
    private val c2Ready = LatestCheck("c2", CheckVerdict.READY)

    @Test
    fun `INV-AC-98 BLOCKING with NOT_READY c2 and no override is refused with PER-001`() {
        FilingGate.decide(GateMode.BLOCKING, c2, null) shouldBe Refuse("PER-001")
    }

    @Test
    fun `INV-AC-98 the same call with an override for c2 proceeds as OVERRIDDEN`() {
        FilingGate.decide(GateMode.BLOCKING, c2, "c2") shouldBe Proceed(ProceedBasis.OVERRIDDEN)
    }

    @Test
    fun `INV-AC-98 an override for the earlier check c1 while the latest is c2 is refused`() {
        FilingGate.decide(GateMode.BLOCKING, c2, "c1") shouldBe Refuse()
    }

    @Test
    fun `INV-AC-2261 BLOCKING with NOT_READY and no override is refused`() {
        FilingGate.decide(GateMode.BLOCKING, c1, null) shouldBe Refuse()
    }

    @Test
    fun `INV-AC-2261 BLOCKING with no check at all is refused even with an override id`() {
        FilingGate.decide(GateMode.BLOCKING, null, null) shouldBe Refuse()
        FilingGate.decide(GateMode.BLOCKING, null, "c2") shouldBe Refuse()
    }

    @Test
    fun `INV-AC-194 ADVISORY with NOT_READY proceeds as ADVISORY`() {
        FilingGate.decide(GateMode.ADVISORY, c2, null) shouldBe Proceed(ProceedBasis.ADVISORY)
    }

    @Test
    fun `INV-AC-2260 ADVISORY with NOT_READY proceeds as ADVISORY`() {
        FilingGate.decide(GateMode.ADVISORY, c2, "c1") shouldBe Proceed(ProceedBasis.ADVISORY)
    }

    @Test
    fun `INV-AC-2260 ADVISORY with no check proceeds as ADVISORY`() {
        FilingGate.decide(GateMode.ADVISORY, null, null) shouldBe Proceed(ProceedBasis.ADVISORY)
        FilingGate.decide(GateMode.ADVISORY, c2Ready, null) shouldBe Proceed(ProceedBasis.ADVISORY)
    }

    @Test
    fun `INV-AC-194 ADVISORY with no check proceeds as ADVISORY`() {
        FilingGate.decide(GateMode.ADVISORY, null, null) shouldBe Proceed(ProceedBasis.ADVISORY)
    }

    @Test
    fun `BLOCKING with READY proceeds as READY also when an override id is present`() {
        FilingGate.decide(GateMode.BLOCKING, c2Ready, null) shouldBe Proceed(ProceedBasis.READY)
        FilingGate.decide(GateMode.BLOCKING, c2Ready, "c2") shouldBe Proceed(ProceedBasis.READY)
        FilingGate.decide(GateMode.BLOCKING, c2Ready, "c1") shouldBe Proceed(ProceedBasis.READY)
    }

    @Test
    fun `the override id is compared exactly, a different case or a trailing space is refused`() {
        FilingGate.decide(GateMode.BLOCKING, c2, "C2") shouldBe Refuse()
        FilingGate.decide(GateMode.BLOCKING, c2, "c2 ") shouldBe Refuse()
        FilingGate.decide(GateMode.BLOCKING, c2, " c2") shouldBe Refuse()
        FilingGate.decide(GateMode.BLOCKING, c2, "c") shouldBe Refuse()
        FilingGate.decide(GateMode.BLOCKING, c2, "") shouldBe Refuse()
    }

    @Test
    fun `equal input gives equal output`() {
        FilingGate.decide(GateMode.BLOCKING, c2, "c2") shouldBe FilingGate.decide(GateMode.BLOCKING, c2, "c2")
    }

    private val known = setOf("c1", "c2")

    @Test
    fun `canOverride ADVISORY gives NoBlock`() {
        FilingGate.canOverride(GateMode.ADVISORY, c2, known, "c2") shouldBe NoBlock("SRT-005")
    }

    @Test
    fun `canOverride an unknown id gives NoBlock`() {
        FilingGate.canOverride(GateMode.BLOCKING, c2, known, "c9") shouldBe NoBlock("SRT-005")
        FilingGate.canOverride(GateMode.BLOCKING, c2, known, "C2") shouldBe NoBlock("SRT-005")
    }

    @Test
    fun `canOverride a known older id gives Stale`() {
        FilingGate.canOverride(GateMode.BLOCKING, c2, known, "c1") shouldBe Stale("SRT-008")
        FilingGate.canOverride(GateMode.BLOCKING, null, known, "c1") shouldBe Stale("SRT-008")
    }

    @Test
    fun `canOverride the latest with READY gives NoBlock`() {
        FilingGate.canOverride(GateMode.BLOCKING, c2Ready, known, "c2") shouldBe NoBlock("SRT-005")
    }

    @Test
    fun `canOverride the latest with NOT_READY gives Allowed`() {
        FilingGate.canOverride(GateMode.BLOCKING, c2, known, "c2") shouldBe Allowed
    }

    @Test
    fun `canOverride an empty requested id is refused with an error`() {
        assertThrows<IllegalArgumentException> { FilingGate.canOverride(GateMode.BLOCKING, c2, known, "") }
        assertThrows<IllegalArgumentException> { FilingGate.canOverride(GateMode.ADVISORY, c2, known, "") }
    }

    @Test
    fun `an empty checkId is refused`() {
        assertThrows<IllegalArgumentException> { LatestCheck("", CheckVerdict.READY) }
    }

    @Test
    fun `the codes are the catalogue codes`() {
        REFUSAL_CODE shouldBe "PER-001"
        NO_BLOCK_CODE shouldBe "SRT-005"
        STALE_CODE shouldBe "SRT-008"
    }

    @Test
    fun `GateMode members are exactly the VP-14 keys`() {
        GateMode.entries.map { it.name } shouldContainExactlyInAnyOrder listOf("ADVISORY", "BLOCKING")
    }

    @Test
    fun `CheckVerdict members are exactly the contract verdicts`() {
        CheckVerdict.entries.map { it.name } shouldContainExactlyInAnyOrder listOf("READY", "NOT_READY")
    }

    @Test
    fun `fromKey accepts both real keys`() {
        GateMode.fromKey("ADVISORY") shouldBe GateMode.ADVISORY
        GateMode.fromKey("BLOCKING") shouldBe GateMode.BLOCKING
    }

    @Test
    fun `fromKey refuses advisory, BLOCK and the empty string`() {
        for (key in listOf("advisory", "BLOCK", "", " BLOCKING")) {
            assertThrows<IllegalArgumentException> { GateMode.fromKey(key) }
        }
    }
}
