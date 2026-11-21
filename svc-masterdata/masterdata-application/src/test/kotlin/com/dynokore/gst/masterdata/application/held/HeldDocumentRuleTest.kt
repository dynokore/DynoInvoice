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
        reasons: Set<String> = emptySet(),
    ) = HeldDocumentFact(HeldKind.HELD, d, seq, entity, party, reasons)

    private fun released(
        seq: Long,
        reasons: Set<String> = emptySet(),
    ) = HeldDocumentFact(HeldKind.RELEASED, d, seq, null, null, reasons)

    private fun row(
        seq: Long,
        held: Boolean,
        entity: String? = "E",
        party: String? = "P",
        rul011: Boolean = false,
    ) = HeldDocumentRow(d, seq, held, entity, party, rul011)

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

                override fun rul011Blocked(entity: String): Set<PartyRef> =
                    rows.values
                        .filter { it.held && it.rul011 && it.entity == entity && it.partyRef != null }
                        .map { PartyRef(it.partyRef!!) }
                        .toSet()

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

    private fun fold(vararg facts: HeldDocumentFact): HeldDocumentRow? {
        var current: HeldDocumentRow? = null
        for (f in facts) current = HeldDocumentRule.next(current, f) ?: current
        return current
    }

    private val rul = setOf("RUL-011")

    @Test
    fun `INV-AC-26219 a hold with RUL-011 and a party gives rul011 true`() {
        assertEquals(row(1, true, rul011 = true), fold(held(1, reasons = rul)))
    }

    @Test
    fun `INV-AC-26219 a hold with another reason gives rul011 false`() {
        assertEquals(row(1, true, rul011 = false), fold(held(1, reasons = setOf("MDM-002"))))
    }

    @Test
    fun `INV-AC-26219 a hold then a release naming RUL-011 gives rul011 false`() {
        assertEquals(row(2, false, rul011 = false), fold(held(1, reasons = rul), released(2, rul)))
    }

    @Test
    fun `INV-AC-26219 the release before the hold gives the same row as the other order`() {
        val forward = fold(held(1, reasons = rul), released(2, rul))!!
        val reverse = fold(released(2, rul), held(1, reasons = rul))!!
        // the tombstone of a release that arrived first has no entity and party; the observable state is the same
        assertEquals(Triple(2L, false, false), Triple(forward.docsequence, forward.held, forward.rul011))
        assertEquals(Triple(2L, false, false), Triple(reverse.docsequence, reverse.held, reverse.rul011))
    }

    @Test
    fun `INV-AC-26219 a repeated hold leaves the row equal`() {
        val once = fold(held(1, reasons = rul))
        assertEquals(once, fold(held(1, reasons = rul), held(1, reasons = rul)))
        assertNull(HeldDocumentRule.next(once, held(1, reasons = rul)))
    }

    @Test
    fun `INV-AC-26219 a release naming only other reasons leaves rul011 true`() {
        val facts = listOf(held(1, reasons = rul), released(2, setOf("OTHER-001")))
        assertEquals(row(2, false, rul011 = true), fold(facts[0], facts[1]))
    }

    @Test
    fun `INV-AC-26219 look-alike reasons never set rul011`() {
        for (reason in listOf("rul-011", "RUL-0110", "XRUL-011", "RUL-011 ", "RUL-01")) {
            assertEquals(false, fold(held(1, reasons = setOf(reason)))!!.rul011, reason)
        }
        assertEquals(true, fold(held(1, reasons = setOf("A-001", "RUL-011")))!!.rul011)
        val after = fold(held(1, reasons = rul), released(2, setOf("rul-011", "RUL-0110", "XRUL-011")))
        assertEquals(true, after!!.rul011)
    }

    @Test
    fun `INV-AC-26219 a hold without a party reference is never returned by rul011Blocked`() {
        val rows = mutableMapOf<UUID, HeldDocumentRow>()
        val port =
            object : HeldDocuments {
                override fun apply(fact: HeldDocumentFact) {
                    HeldDocumentRule.next(rows[fact.docUuid], fact)?.let { rows[fact.docUuid] = it }
                }

                override fun heldFor(
                    entity: String,
                    party: PartyRef,
                ): List<UUID> = emptyList()

                override fun rul011Blocked(entity: String): Set<PartyRef> =
                    rows.values
                        .filter { it.held && it.rul011 && it.entity == entity && it.partyRef != null }
                        .map { PartyRef(it.partyRef!!) }
                        .toSet()
            }
        port.apply(held(1, party = null, reasons = rul))
        assertEquals(emptySet<PartyRef>(), port.rul011Blocked("E"))
        port.apply(held(2, party = "P", reasons = rul))
        assertEquals(setOf(PartyRef("P")), port.rul011Blocked("E"))
        port.apply(held(3, party = null, reasons = rul))
        assertEquals(emptySet<PartyRef>(), port.rul011Blocked("E"))
    }
}
