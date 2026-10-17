package com.dynokore.platform.documentlifecycle

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StateModelTest {
    private val a = StateSpec("A")
    private val b = StateSpec("B")

    @Test
    fun `an empty state set is refused`() {
        assertThrows(IllegalArgumentException::class.java) { StateModel(emptyList(), emptySet(), "A") }
    }

    @Test
    fun `a transition naming an unknown state is refused on either end`() {
        assertThrows(IllegalArgumentException::class.java) { StateModel(listOf(a), setOf(Transition("A", "Z")), "A") }
        assertThrows(IllegalArgumentException::class.java) { StateModel(listOf(a), setOf(Transition("Z", "A")), "A") }
    }

    @Test
    fun `a duplicate state code is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            StateModel(listOf(a, StateSpec("A", inFlight = true)), emptySet(), "A")
        }
    }

    @Test
    fun `a blank state code is refused`() {
        listOf("", " ", "\t").forEach {
            assertThrows(IllegalArgumentException::class.java) { StateModel(listOf(a, StateSpec(it)), emptySet(), "A") }
        }
    }

    @Test
    fun `an initial state outside the set is refused`() {
        assertThrows(IllegalArgumentException::class.java) { StateModel(listOf(a), emptySet(), "B") }
    }

    @Test
    fun `terminal is derived from the absence of an outgoing transition`() {
        val model = StateModel(listOf(a, b), setOf(Transition("A", "B")), "A")
        assertFalse(model.isTerminal("A"))
        assertTrue(model.isTerminal("B"))
    }

    @Test
    fun `a state with only a self transition is not terminal`() {
        assertFalse(StateModel(listOf(a), setOf(Transition("A", "A")), "A").isTerminal("A"))
    }

    @Test
    fun `flags are read back per state`() {
        val model =
            StateModel(listOf(StateSpec("A", inFlight = true), StateSpec("B", countsAsOpen = true)), emptySet(), "A")
        assertTrue(model.isInFlight("A") && !model.countsAsOpen("A"))
        assertTrue(!model.isInFlight("B") && model.countsAsOpen("B"))
    }
}
