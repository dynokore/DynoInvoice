package com.dynokore.gst.access.application.alert

import com.dynokore.gst.access.domain.grant.EntityRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The check of ACC-001 over fakes (layer 1 of INV-REQ-895). */
class OrphanedApproversTest {
    private val e1 = EntityRef("E1")
    private val e2 = EntityRef("E2")
    private val now = Instant.parse("2026-11-16T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val counts = mutableMapOf(e1 to 0, e2 to 0)
    private val everHeld = mutableSetOf(e1, e2)
    private val open = mutableSetOf<EntityRef>()
    private val raised = mutableListOf<Pair<String, String>>()
    private var activeFails = false

    private val state =
        object : OrphanAlertState {
            override fun tryOpen(entity: EntityRef) = open.add(entity)

            override fun close(entity: EntityRef) {
                open.remove(entity)
            }

            override fun openEntities(): Set<EntityRef> = open.toSet()
        }

    private val check =
        OrphanedApprovers(
            { entity, at ->
                check(at == now)
                if (activeFails) error("read failed")
                counts.getValue(entity)
            },
            { everHeld.contains(it) },
            state,
            { code, scope -> raised += code to scope },
            clock,
        )

    @Test
    fun `INV-AC-23854 count zero and held raises once with the entity scope`() {
        check.afterChange(setOf(e1))
        check.afterChange(setOf(e1))
        assertEquals(listOf("ACC-001" to "entity:E1"), raised)
        assertEquals(setOf(e1), open)
    }

    @Test
    fun `INV-AC-23854 an approver again closes the state and a new loss raises again`() {
        check.afterChange(setOf(e1))
        counts[e1] = 1
        check.afterChange(setOf(e1))
        assertEquals(emptySet<EntityRef>(), open)
        counts[e1] = 0
        check.afterChange(setOf(e1))
        assertEquals(2, raised.size)
    }

    @Test
    fun `INV-AC-23854 an entity that never held an approver raises nothing and opens nothing`() {
        everHeld.clear()
        check.afterChange(setOf(e1))
        assertEquals(emptyList<Pair<String, String>>(), raised)
        assertEquals(emptySet<EntityRef>(), open)
    }

    @Test
    fun `INV-AC-23854 an entity that still has an approver records nothing`() {
        counts[e2] = 1
        check.afterChange(setOf(e1, e2))
        assertEquals(listOf("ACC-001" to "entity:E1"), raised)
    }

    @Test
    fun `INV-AC-23854 settle closes open entities outside the set or with an approver`() {
        open += setOf(e1, e2)
        counts[e2] = 1
        check.settle(setOf(e2))
        assertEquals(emptySet<EntityRef>(), open)
        assertEquals(emptyList<Pair<String, String>>(), raised)
    }

    @Test
    fun `INV-AC-23854 a failing approver read propagates and records and opens nothing`() {
        activeFails = true
        assertThrows(IllegalStateException::class.java) { check.afterChange(setOf(e1)) }
        assertEquals(emptyList<Pair<String, String>>(), raised)
        assertEquals(emptySet<EntityRef>(), open)
    }
}
