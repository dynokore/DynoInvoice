package com.dynokore.platform.configresolver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Actor.principal` uses tenancy-kernel's `BlankText.isActor` (coordinator ruling INV-Q-4684 (A); INV-Q-4534,
 * INV-Q-4614, INV-Q-4622): the refusal names the rule and never echoes the value.
 */
class ActorPrincipalTest {
    @Test
    fun `a blank or control-character principal is refused without echoing it`() {
        for (sample in listOf("\uFEFF", "\u00A0\u3000", "a\tb", "\nx", "x\u0085", "\u001B[31mx", "x\u009B")) {
            val refusal = assertThrows(IllegalArgumentException::class.java) { Actor(sample, emptySet()) }
            val message = refusal.message.orEmpty()
            assertTrue(message.contains("no control character"), message)
            assertFalse(message.contains(sample))
        }
    }

    @Test
    fun `a valid principal is kept unchanged`() {
        for (sample in listOf("x\u200By", "CASE 5521", ('!'..'~').joinToString("").repeat(3).take(255))) {
            assertEquals(sample, Actor(sample, setOf("role")).principal)
        }
    }
}
