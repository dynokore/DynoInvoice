package com.dynokore.gst.transport.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class DomainTest {
    @Test
    fun `a rejection needs a reason and a reason needs an internal code`() {
        shouldThrow<IllegalArgumentException> { TransportOutcome.Rejected(emptyList()) }
        shouldThrow<IllegalArgumentException> { FailureReason("AP-PKG-INVALID-DOCUMENT", null, "x") }
        shouldThrow<IllegalArgumentException> { FailureReason("sub-003", null, "x") }
        FailureReason(FailureReason.PACKAGE_REFUSED, null, "x").code shouldBe "SUB-003"
    }

    @Test
    fun `an acceptance needs its acknowledgement and the outcome kinds map one to one`() {
        shouldThrow<IllegalArgumentException> { TransportOutcome.Accepted(" ") }
        TransportOutcome.Accepted("a").kind shouldBe OutcomeKind.ACCEPTED
        TransportOutcome.TransientFailure.kind shouldBe OutcomeKind.TRANSIENT_FAILURE
        OutcomeKind.ACCEPTED.isFailure shouldBe false
        OutcomeKind.REJECTED.isFailure shouldBe true
        OutcomeKind.TRANSIENT_FAILURE.isFailure shouldBe true
    }

    @Test
    fun `identifiers refuse a blank value, a version below one and an over-long id`() {
        shouldThrow<IllegalArgumentException> { DocRef(UUID.randomUUID(), 0) }
        shouldThrow<IllegalArgumentException> { AdapterId("") }
        shouldThrow<IllegalArgumentException> { AdapterId("x".repeat(41)) }
        shouldThrow<IllegalArgumentException> { EntityRef(" ") }
        AdapterId("x".repeat(40)).value.length shouldBe 40
    }

    @Test
    fun `the ceiling is the agreed rate until a lower tenant value is set`() {
        val agreed = ThroughputCeiling.agreed(BigDecimal("60"))
        agreed.effective shouldBe BigDecimal("60")
        val lower = agreed.withTenantValue(BigDecimal("30")).shouldBeInstanceOf<CeilingChange.Applied>().ceiling
        lower.effective shouldBe BigDecimal("30")
        lower.agreedRate shouldBe BigDecimal("60")
    }

    @Test
    fun `a value equal to the agreed rate is accepted and one unit above it is refused`() {
        val agreed = ThroughputCeiling.agreed(BigDecimal("60"))
        agreed.withTenantValue(BigDecimal("60")).shouldBeInstanceOf<CeilingChange.Applied>()
        val refused = agreed.withTenantValue(BigDecimal("60.001")).shouldBeInstanceOf<CeilingChange.Refused>()
        refused.reason shouldBe CeilingRefusal.ABOVE_AGREED_RATE
        refused.ceiling.effective shouldBe BigDecimal("60")
    }

    @Test
    fun `zero and negative values are not lower values`() {
        val agreed = ThroughputCeiling.agreed(BigDecimal("60"))
        listOf("0", "0.000", "-1").forEach {
            agreed.withTenantValue(BigDecimal(it)).shouldBeInstanceOf<CeilingChange.Refused>().reason shouldBe
                CeilingRefusal.NOT_POSITIVE
        }
        shouldThrow<IllegalArgumentException> { ThroughputCeiling.agreed(BigDecimal.ZERO) }
    }

    @Test
    fun `a stored tenant value above the agreed rate is never trusted`() {
        shouldThrow<IllegalStateException> { ThroughputCeiling.restore(BigDecimal("60"), BigDecimal("61")) }
        ThroughputCeiling.restore(BigDecimal("60"), null).effective shouldBe BigDecimal("60")
    }

    @Test
    fun `the failure rate is failed over total and zero for an empty window`() {
        AttemptCounts(4, 1).failureRate shouldBe 0.25
        AttemptCounts(0, 0).failureRate shouldBe 0.0
        shouldThrow<IllegalArgumentException> { AttemptCounts(1, 2) }
        shouldThrow<IllegalArgumentException> { AttemptCounts(-1, 0) }
    }
}
