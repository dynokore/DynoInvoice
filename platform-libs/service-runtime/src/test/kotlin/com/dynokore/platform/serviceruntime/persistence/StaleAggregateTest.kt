package com.dynokore.platform.serviceruntime.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import org.junit.jupiter.api.Test

/** INV-AC-6973: the published StaleAggregate exception of ARC-19. */
class StaleAggregateTest {
    @Test
    fun `INV-AC-6973 T1 properties and message`() {
        val e = StaleAggregate("PeriodObligation", 3)
        e.aggregate shouldBe "PeriodObligation"
        e.version shouldBe 3L
        e.message shouldBe "stale aggregate PeriodObligation at version 3"
    }

    @Test
    fun `INV-AC-6973 T2 blank name and negative version are refused`() {
        shouldThrow<IllegalArgumentException> { StaleAggregate("", 1) }
        shouldThrow<IllegalArgumentException> { StaleAggregate("  ", 1) }
        shouldThrow<IllegalArgumentException> { StaleAggregate("Account", -1) }
        StaleAggregate("Account", 0).version shouldBe 0L
        StaleAggregate(" Account", 1).aggregate shouldBe " Account"
    }

    @Test
    fun `INV-AC-6973 T3 runtime exception with the published class name`() {
        val e = StaleAggregate("Account", 1)
        e.shouldBeInstanceOf<RuntimeException>()
        e.shouldNotBeInstanceOf<Error>()
        StaleAggregate::class.java.name shouldBe "com.dynokore.platform.serviceruntime.persistence.StaleAggregate"
    }
}
