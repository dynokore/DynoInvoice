package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.DocumentHeldFacts
import com.dynokore.gst.consoleBff.domain.exceptions.DocumentReleasedFacts
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RecordHoldEventsTest {
    private class MemoryStore : ExceptionStore {
        val rows = linkedMapOf<Pair<String, UUID>, ExceptionRow>()

        override fun upsert(rows: List<ExceptionRow>) {
            rows.forEach { this.rows[it.tenantId to it.exceptionId] = it }
        }

        override fun rowsOfDocument(
            tenantId: String,
            docUuid: String,
        ) = rows.values.filter { it.tenantId == tenantId && it.docUuid == docUuid }

        override fun openRowsOfDocument(
            tenantId: String,
            docUuid: String,
        ) = rowsOfDocument(tenantId, docUuid).filter { it.state == ItemState.OPEN }
    }

    private val tenant = "11111111-1111-1111-1111-111111111111"
    private val doc = "22222222-2222-2222-2222-222222222222"
    private val raised = Instant.parse("2026-11-12T08:00:00Z")
    private val store = MemoryStore()
    private val recordHeld = RecordDocumentHeld(store)
    private val recordReleased = RecordDocumentReleased(store)
    private val held =
        DocumentHeldFacts(
            "ENT-1",
            doc,
            1L,
            listOf("MDM-002", "PAY-001"),
            "2026-Q3",
            "PAY-001",
            "Tax operations",
            "MANUAL",
        )

    private fun released(vararg codes: String) = DocumentReleasedFacts(doc, 2L, codes.toList())

    private fun snapshot() = store.rows.values.toList()

    private fun state(code: String) =
        store.rows.values
            .single { it.code == code }
            .state

    @Test
    fun `INV-AC-20958 hold stores two OPEN rows with pinned ids and recording again keeps them equal`() {
        recordHeld.record(tenant, held, raised)
        val first = snapshot()
        first.size shouldBe 2
        first.map { it.state }.toSet() shouldBe setOf(ItemState.OPEN)
        first.single { it.code == "PAY-001" }.exceptionId shouldBe
            UUID.fromString("8115e69b-adb5-52d0-98b2-6422b8e3abe1")
        first.single { it.code == "MDM-002" }.exceptionId shouldBe
            UUID.fromString("7c07080a-dc42-5cb6-8eec-07f213c4dd0c")
        first.all { it.exceptionId.version() == 5 } shouldBe true
        val pay = first.single { it.code == "PAY-001" }
        pay.remediationOwner shouldBe "Tax operations"
        pay.disposition shouldBe "MANUAL"
        pay.raisedAt shouldBe raised
        first.single { it.code == "MDM-002" }.remediationOwner shouldBe ""
        first.single { it.code == "MDM-002" }.disposition shouldBe ""
        recordHeld.record(tenant, held, raised)
        snapshot() shouldBe first
    }

    @Test
    fun `INV-AC-20958 release of PAY-001 resolves only PAY-001 and repeats or XXX-999 change nothing`() {
        recordHeld.record(tenant, held, raised)
        recordReleased.record(tenant, released("PAY-001"))
        state("PAY-001") shouldBe ItemState.RESOLVED
        state("MDM-002") shouldBe ItemState.OPEN
        val after = snapshot()
        recordReleased.record(tenant, released("PAY-001"))
        snapshot() shouldBe after
        recordReleased.record(tenant, released("XXX-999"))
        snapshot() shouldBe after
    }

    @Test
    fun `INV-AC-20958 second DocumentHeld for another document stores one OPEN row with empty members`() {
        val other = DocumentHeldFacts("ENT-1", "33333333-3333-3333-3333-333333333333", 1L, listOf("MDM-002"), "2026-Q3")
        recordHeld.record(tenant, other, raised)
        val rows = snapshot()
        rows.size shouldBe 1
        rows.single().state shouldBe ItemState.OPEN
        rows.single().remediationOwner shouldBe ""
        rows.single().disposition shouldBe ""
    }

    @Test
    fun `INV-AC-20958 a stored OVERRIDDEN row is kept by a held event and not resolved by a release`() {
        recordHeld.record(tenant, held, raised)
        val pay = store.rows.values.single { it.code == "PAY-001" }
        store.upsert(listOf(pay.copy(state = ItemState.OVERRIDDEN)))
        val before = snapshot()
        recordHeld.record(tenant, held, Instant.parse("2026-11-13T08:00:00Z"))
        state("PAY-001") shouldBe ItemState.OVERRIDDEN
        store.rows.values.single { it.code == "PAY-001" } shouldBe before.single { it.code == "PAY-001" }
        recordReleased.record(tenant, released("PAY-001"))
        state("PAY-001") shouldBe ItemState.OVERRIDDEN
    }

    @Test
    fun `INV-AC-21027 hold returns true on first call and differing row and false on identical repeat`() {
        recordHeld.record(tenant, held, raised) shouldBe true
        recordHeld.record(tenant, held, raised) shouldBe false
        recordHeld.record(tenant, held, Instant.parse("2026-11-13T08:00:00Z")) shouldBe true
    }

    @Test
    fun `INV-AC-21027 hold returns false when the only candidate row is kept because it is OVERRIDDEN`() {
        val single = held.copy(holdReasons = listOf("PAY-001"))
        recordHeld.record(tenant, single, raised) shouldBe true
        store.upsert(
            listOf(
                store.rows.values
                    .single()
                    .copy(state = ItemState.OVERRIDDEN),
            ),
        )
        recordHeld.record(tenant, single, Instant.parse("2026-11-13T08:00:00Z")) shouldBe false
    }

    @Test
    fun `INV-AC-21027 release returns true when an OPEN row resolved and false on repeat or no matching code`() {
        recordHeld.record(tenant, held, raised)
        recordReleased.record(tenant, released("PAY-001")) shouldBe true
        recordReleased.record(tenant, released("PAY-001")) shouldBe false
        recordReleased.record(tenant, released("XXX-999")) shouldBe false
    }

    @Test
    fun `INV-AC-21027 release returns false when the only matching row is OVERRIDDEN`() {
        recordHeld.record(tenant, held.copy(holdReasons = listOf("PAY-001")), raised)
        store.upsert(
            listOf(
                store.rows.values
                    .single()
                    .copy(state = ItemState.OVERRIDDEN),
            ),
        )
        recordReleased.record(tenant, released("PAY-001")) shouldBe false
    }
}
