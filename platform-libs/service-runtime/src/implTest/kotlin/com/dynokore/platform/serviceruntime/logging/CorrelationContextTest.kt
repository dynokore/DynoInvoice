package com.dynokore.platform.serviceruntime.logging

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CorrelationContextTest {
    @Test
    fun `binds correlation id for the block's duration and clears it after`() {
        CorrelationContext.withCorrelation(CorrelationId("corr-1")) {
            CorrelationContext.currentCorrelationId() shouldBe CorrelationId("corr-1")
        }
        CorrelationContext.currentCorrelationId().shouldBeNull()
    }

    @Test
    fun `restores a previously-bound correlation id after a nested block`() {
        CorrelationContext.withCorrelation(CorrelationId("outer")) {
            CorrelationContext.withCorrelation(CorrelationId("inner")) {
                CorrelationContext.currentCorrelationId() shouldBe CorrelationId("inner")
            }
            CorrelationContext.currentCorrelationId() shouldBe CorrelationId("outer")
        }
    }

    @Test
    fun `binds causation id independently of correlation id`() {
        CorrelationContext.withCorrelation(CorrelationId("corr-1")) {
            CorrelationContext.withCausation(CausationId("cause-1")) {
                CorrelationContext.currentCorrelationId() shouldBe CorrelationId("corr-1")
                CorrelationContext.currentCausationId() shouldBe CausationId("cause-1")
            }
        }
    }
}
