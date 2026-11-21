package com.dynokore.gst.masterdata.application.held

import com.dynokore.gst.masterdata.application.held.HeldDocumentRule.NO_REASON
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

    /** The row of a fact without reasons (the stand-in reason): one mark at [seq]; [identity] is the hold sequence. */
    private fun row(
        seq: Long,
        held: Boolean,
        entity: String? = "E",
        party: String? = "P",
        identity: Long? = seq,
    ) = HeldDocumentRow(
        d,
        seq,
        held,
        entity,
        party,
        identity,
        mapOf(HeldDocumentRule.NO_REASON to ReasonMark(seq, held)),
    )

    @Test
    fun `INV-AC-21330 first hold writes the row and the same fact again changes nothing`() {
        val first = HeldDocumentRule.next(null, held(3))
        assertEquals(row(3, true), first)
        assertNull(HeldDocumentRule.next(first, held(3)))
    }

    @Test
    fun `INV-AC-21330 release keeps entity and party as a tombstone`() {
        assertEquals(row(4, false, identity = 3), HeldDocumentRule.next(row(3, true), released(4)))
    }

    @Test
    fun `INV-AC-21330 a stale hold on a released row returns null`() {
        assertNull(HeldDocumentRule.next(row(4, false), held(3)))
    }

    @Test
    fun `INV-AC-21330 release on no row writes a bare tombstone and a later lower hold returns null`() {
        val tomb = HeldDocumentRule.next(null, released(5))
        assertEquals(row(5, false, null, null, null), tomb)
        // the lower hold still records its entity and party on the tombstone and leaves the row released
        assertEquals(row(5, false, "E", "P", 4), HeldDocumentRule.next(tomb, held(4)))
    }

    @Test
    fun `INV-AC-21330 a newer hold without party replaces the old party`() {
        assertEquals(row(6, true, "E", null), HeldDocumentRule.next(row(4, false), held(6, party = null)))
    }

    @Test
    fun `INV-AC-21330 a hold without party on no row is stored with no party`() {
        assertEquals(row(3, true, "E", null), HeldDocumentRule.next(null, held(3, party = null)))
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
                        .filter { it.marks["RUL-011"]?.held == true && it.entity == entity && it.partyRef != null }
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

    private fun marks(vararg m: Triple<String, Long, Boolean>) =
        m.associate {
            it.first to
                ReasonMark(it.second, it.third)
        }

    private fun blocked(row: HeldDocumentRow?): Set<PartyRef> =
        listOfNotNull(row)
            .filter { it.marks["RUL-011"]?.held == true && it.partyRef != null }
            .map { PartyRef(it.partyRef!!) }
            .toSet()

    @Test
    fun `INV-AC-26219 a hold with RUL-011 and a party marks RUL-011 held and is listed`() {
        val r = fold(held(1, reasons = rul))!!
        assertEquals(marks(Triple("RUL-011", 1L, true)), r.marks)
        assertEquals(setOf(PartyRef("P")), blocked(r))
    }

    @Test
    fun `INV-AC-26219 a hold with another reason has no RUL-011 mark`() {
        val r = fold(held(1, reasons = setOf("MDM-002")))!!
        assertEquals(marks(Triple("MDM-002", 1L, true)), r.marks)
        assertEquals(emptySet<PartyRef>(), blocked(r))
    }

    @Test
    fun `INV-AC-26219 a hold then a release naming RUL-011 gives RUL-011 not held`() {
        val r = fold(held(1, reasons = rul), released(2, rul))!!
        assertEquals(marks(Triple("RUL-011", 2L, false)), r.marks)
        assertEquals(false, r.held)
        assertEquals(emptySet<PartyRef>(), blocked(r))
    }

    @Test
    fun `INV-AC-26219 a release naming only other reasons leaves RUL-011 held and the row held`() {
        val r = fold(held(1, reasons = rul), released(2, setOf("OTHER-001")))!!
        assertEquals(marks(Triple("RUL-011", 1L, true), Triple("OTHER-001", 2L, false)), r.marks)
        assertEquals(true, r.held)
        assertEquals(setOf(PartyRef("P")), blocked(r))
    }

    @Test
    fun `INV-AC-26219 a release of RUL-011 keeps the row held while another reason remains`() {
        val r = fold(held(1, reasons = setOf("RUL-011", "MDM-002")), released(2, rul))!!
        assertEquals(true, r.held)
        assertEquals(emptySet<PartyRef>(), blocked(r))
    }

    @Test
    fun `INV-AC-26219 a repeated hold leaves the row equal`() {
        val once = fold(held(1, reasons = rul))
        assertEquals(once, fold(held(1, reasons = rul), held(1, reasons = rul)))
        assertNull(HeldDocumentRule.next(once, held(1, reasons = rul)))
    }

    @Test
    fun `INV-AC-26219 a hold without a party reference is stored and never listed`() {
        val r = fold(held(1, party = null, reasons = rul))!!
        assertEquals(true, r.held)
        assertEquals(null, r.partyRef)
        assertEquals(emptySet<PartyRef>(), blocked(r))
    }

    @Test
    fun `INV-AC-26219 look-alike reasons never mark RUL-011`() {
        for (reason in listOf("rul-011", "RUL-0110", "XRUL-011", "RUL-011 ", "RUL-01")) {
            assertEquals(emptySet<PartyRef>(), blocked(fold(held(1, reasons = setOf(reason)))), reason)
        }
        val after = fold(held(1, reasons = rul), released(2, setOf("rul-011", "RUL-0110", "XRUL-011")))
        assertEquals(setOf(PartyRef("P")), blocked(after))
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items) else items.flatMap { x -> permutations(items - x).map { listOf(x) + it } }

    @Test
    fun `INV-AC-26219 convergence all 6 arrival orders of three facts give the row of the order 1 2 3`() {
        val facts =
            listOf(
                held(1, reasons = setOf("RUL-011", "MDM-002")),
                released(2, setOf("OTHER-001")),
                released(3, rul),
            )
        val expected = fold(facts[0], facts[1], facts[2])!!
        assertEquals(
            marks(Triple("RUL-011", 3L, false), Triple("MDM-002", 1L, true), Triple("OTHER-001", 2L, false)),
            expected.marks,
        )
        assertEquals(true, expected.held)
        val orders = permutations(listOf(0, 1, 2))
        assertEquals(6, orders.size)
        for (order in orders) assertEquals(expected, fold(*order.map { facts[it] }.toTypedArray()), "order $order")
    }

    @Test
    fun `INV-AC-26219 convergence a release arriving before the hold records entity and party on the tombstone`() {
        val first = fold(held(1, reasons = rul), released(2, setOf("OTHER-001")))!!
        val reverse = fold(released(2, setOf("OTHER-001")), held(1, reasons = rul))!!
        assertEquals(first, reverse)
        assertEquals("E", reverse.entity)
        assertEquals("P", reverse.partyRef)
        assertEquals(true, reverse.held)
    }

    @Test
    fun `INV-AC-26219 a rule that cleared every mark on any release would fail the other-reason case`() {
        val r = fold(held(1, reasons = rul), released(2, setOf("OTHER-001")))!!
        assertEquals(true, r.marks.getValue("RUL-011").held)
    }
}
