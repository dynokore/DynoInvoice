package com.dynokore.platform.serviceruntime.staleness

import com.dynokore.platform.outboxandqueue.StalenessRule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

/** INV-Q-2991 provisional defaults, marked provisional (INV-Q-2987), and tighten-only (INV-REQ-1165 b4). */
class StalenessRulesTest {
    @Test
    fun `the defaults are 5 minutes on the submission path and 30 minutes otherwise, marked provisional`() {
        ProvisionalStalenessThresholds.PROVISIONAL shouldBe true
        val path = listOf("CanonicalVersionCreated", "PayloadReady", "PayloadRejected", "DocumentReleased")
        listOf("payload-svc", "submission-svc").forEach { consumer ->
            path.forEach { ProvisionalStalenessThresholds.of(consumer, type(it)) shouldBe Duration.ofMinutes(5) }
        }
        ProvisionalStalenessThresholds.of("evidence-svc", type("CanonicalVersionCreated")) shouldBe Duration.ofMinutes(30)
        ProvisionalStalenessThresholds.of("submission-svc", type("PolicyChanged")) shouldBe Duration.ofMinutes(30)
        ProvisionalStalenessThresholds.of("console-bff", "SubmissionAcknowledged") shouldBe Duration.ofMinutes(30)
    }

    @Test
    fun `a configured rule without a threshold takes its provisional value`() {
        val rules =
            StalenessRules.resolve(
                listOf(
                    StalenessProperties.Rule("submission-svc", CANONICAL),
                    StalenessProperties.Rule("submission-svc", type("PolicyChanged")),
                ),
            )
        rules.rules.map { it.threshold } shouldBe listOf(Duration.ofMinutes(5), Duration.ofMinutes(30))
    }

    @Test
    fun `a shorter installation value applies`() {
        StalenessRules.resolve(listOf(StalenessProperties.Rule("submission-svc", CANONICAL, Duration.ofMinutes(2))))
            .rules.single().threshold shouldBe Duration.ofMinutes(2)
    }

    @Test
    fun `a longer installation value is refused, naming the rule`() {
        val refused =
            shouldThrow<IllegalStateException> {
                StalenessRules.resolve(listOf(StalenessProperties.Rule("submission-svc", CANONICAL, Duration.ofMinutes(6))))
            }
        refused.message shouldContain "consumer submission-svc on $CANONICAL"
        refused.message shouldContain "only tighten"
    }

    @Test
    fun `a declared rule bean is held to the same ceiling`() {
        shouldThrow<IllegalStateException> {
            StalenessRules.resolve(emptyList(), listOf(StalenessRule("evidence-svc", type("PolicyChanged"), Duration.ofMinutes(31))))
        }.message shouldContain "consumer evidence-svc"
    }

    @Test
    fun `one rule per consumer and event type, and both are named`() {
        shouldThrow<IllegalStateException> {
            StalenessRules.resolve(listOf(StalenessProperties.Rule("submission-svc", CANONICAL)), listOf(StalenessRule("submission-svc", CANONICAL, Duration.ofMinutes(1))))
        }
        shouldThrow<IllegalStateException> { StalenessRules.resolve(listOf(StalenessProperties.Rule("", CANONICAL))) }
        shouldThrow<IllegalStateException> { StalenessRules.resolve(listOf(StalenessProperties.Rule("submission-svc", ""))) }
    }

    private companion object {
        const val CANONICAL = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"

        fun type(name: String) = "com.dynokore.gst.x.$name.v1"
    }
}
