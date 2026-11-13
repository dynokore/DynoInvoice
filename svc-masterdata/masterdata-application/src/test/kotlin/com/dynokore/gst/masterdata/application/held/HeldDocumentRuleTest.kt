package com.dynokore.gst.masterdata.application.held

import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class HeldDocumentRuleTest {
    private val d = UUID.fromString("00000000-0000-0000-0000-00000000000d")

    private fun held(
        seq: Long,
        entity: String = "E",
        party: String? = "P",
    ) = HeldDocumentFact(HeldKind.HELD, d, seq, entity, party)

    private fun released(seq: Long) = HeldDocumentFact(HeldKind.RELEASED, d, seq, null, null)

    private fun row(
        seq: Long,
        held: Boolean,
        entity: String? = "E",
        party: String? = "P",
    ) = HeldDocumentRow(d, seq, held, entity, party)

    @Test
    fun `INV-AC-21330 first hold writes the row and the same fact again changes nothing`() {
        val first = HeldDocumentRule.next(null, held(3))
        assertEquals(row(3, true), first)
        assertNull(HeldDocumentRule.next(first, held(3)))
    }

    @Test
    fun `INV-AC-21330 release keeps entity and party as a tombstone`() {
        assertEquals(row(4, false), HeldDocumentRule.next(row(3, true), released(4)))
    }

    @Test
    fun `INV-AC-21330 a stale hold on a released row returns null`() {
        assertNull(HeldDocumentRule.next(row(4, false), held(3)))
    }

    @Test
    fun `INV-AC-21330 release on no row writes a bare tombstone and a later lower hold returns null`() {
        val tomb = HeldDocumentRule.next(null, released(5))
        assertEquals(row(5, false, null, null), tomb)
        assertNull(HeldDocumentRule.next(tomb, held(4)))
    }

    @Test
    fun `INV-AC-21330 a newer hold without party replaces the old party`() {
        assertEquals(row(6, true, "E", null), HeldDocumentRule.next(row(4, false), held(6, party = null)))
    }

    @Test
    fun `INV-AC-21330 a hold without party on no row writes nothing`() {
        assertNull(HeldDocumentRule.next(null, held(3, party = null)))
    }

    @Test
    fun `INV-AC-21330 different content at the same sequence changes nothing`() {
        assertNull(HeldDocumentRule.next(row(3, true), held(3, "E2", "P2")))
    }

    @Test
    fun `INV-AC-21330 invalid facts are refused`() {
        assertThrows(IllegalArgumentException::class.java) { held(0) }
        assertThrows(IllegalArgumentException::class.java) { held(3, entity = " ") }
        assertThrows(IllegalArgumentException::class.java) { held(3, party = "") }
        assertThrows(IllegalArgumentException::class.java) { HeldDocumentFact(HeldKind.RELEASED, d, 3, null, " ") }
        assertThrows(IllegalArgumentException::class.java) { HeldDocumentFact(HeldKind.HELD, d, 3, null, "P") }
    }

    @Test
    fun `INV-AC-21330 every arrival order of three facts ends in the row of the highest sequence`() {
        val facts = listOf(held(3), released(4), held(5))
        val orders =
            listOf(
                listOf(0, 1, 2),
                listOf(0, 2, 1),
                listOf(1, 0, 2),
                listOf(1, 2, 0),
                listOf(2, 0, 1),
                listOf(2, 1, 0),
            )
        assertEquals(6, orders.size)
        for (order in orders) {
            var current: HeldDocumentRow? = null
            for (i in order) current = HeldDocumentRule.next(current, facts[i]) ?: current
            assertEquals(row(5, true), current, "order $order")
        }
    }

    @Test
    fun `INV-AC-21330 heldFor is a list of docUuids ordered by the UUID text`() {
        val rows = mutableMapOf<UUID, HeldDocumentRow>()
        val port =
            object : HeldDocuments {
                override fun apply(fact: HeldDocumentFact) {
                    HeldDocumentRule.next(rows[fact.docUuid], fact)?.let { rows[fact.docUuid] = it }
                }

                override fun heldFor(
                    entity: String,
                    party: PartyRef,
                ): List<UUID> =
                    rows.values
                        .filter { it.held && it.entity == entity && it.partyRef == party.value }
                        .map { it.docUuid }
                        .sortedBy { it.toString() }
            }
        val other = UUID.fromString("00000000-0000-0000-0000-000000000001")
        port.apply(held(3))
        port.apply(HeldDocumentFact(HeldKind.HELD, other, 1, "E", "P"))
        assertEquals(listOf(other, d), port.heldFor("E", PartyRef("P")))
        port.apply(released(4))
        assertEquals(listOf(other), port.heldFor("E", PartyRef("P")))
    }
}
