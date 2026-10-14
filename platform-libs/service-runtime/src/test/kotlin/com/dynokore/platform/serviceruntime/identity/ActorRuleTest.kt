package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.ReplayMode
import com.dynokore.platform.serviceruntime.jobs.JobCommandRequest
import com.dynokore.platform.serviceruntime.rebuild.RebuildConsumerRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The api half's actor checks use tenancy-kernel's `BlankText.isActor` (coordinator ruling INV-Q-4684 (A); INV-Q-4534,
 * INV-Q-4614, INV-Q-4622; INV-REQ-854 b1): the refusal names the rule and never echoes the value.
 */
class ActorRuleTest {
    private val checks: Map<String, (String) -> String> =
        mapOf(
            "EndUserPrincipal.actor" to { actor -> EndUserPrincipal(actor, null, emptySet()).actor },
            "JobCommandRequest.actor" to { actor ->
                JobCommandRequest(actor, "j", null, UUID.randomUUID(), null, CorrelationId("c"), CausationId("c")).actor
            },
            "RebuildConsumerRequest.actor" to { actor ->
                RebuildConsumerRequest(
                    actor,
                    "topic",
                    ReplayMode.FULL_SYNC,
                    null,
                    UUID.randomUUID(),
                    null,
                    CorrelationId("c"),
                    CausationId("c"),
                ).actor
            },
        )

    @Test
    fun `each actor check refuses blank and control-character samples without echoing them`() {
        for ((name, check) in checks) {
            for (sample in REFUSED) {
                val refusal = assertThrows(IllegalArgumentException::class.java, { check(sample) }, name)
                val message = refusal.message.orEmpty()
                assertTrue(message.contains("no control character"), "$name: $message")
                assertFalse(message.contains(sample), name)
            }
        }
    }

    @Test
    fun `each actor check accepts a valid actor unchanged`() {
        for ((name, check) in checks) {
            for (sample in ACCEPTED) assertEquals(sample, check(sample), name)
        }
    }

    companion object {
        val REFUSED =
            listOf("\uFEFF", "\u00A0\u3000", "a\tb", "\nx", "x\u0085", "\u001B[31mx", "x\u009B")
        val ACCEPTED =
            listOf("x\u200By", "CASE 5521", ('!'..'~').joinToString("").repeat(3).take(255))
    }
}
