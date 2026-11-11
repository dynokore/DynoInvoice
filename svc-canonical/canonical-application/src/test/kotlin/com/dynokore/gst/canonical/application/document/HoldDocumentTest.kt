package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.derivation.GateDecision
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class HoldDocumentTest {
    private val u = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val u2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    private val u3 = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
    private val posting = OffsetDateTime.parse("2026-03-21T09:30:00+08:00")
    private val log = mutableListOf<String>()

    private fun doc(
        id: UUID,
        state: String,
        optional: Boolean = true,
        reasons: List<String> = emptyList(),
    ) = HoldableDocument(
        id,
        "E-001",
        Direction.SALE,
        LocalDate.parse("2026-03-20"),
        "2026-Q1",
        state,
        reasons,
        if (optional) posting else null,
        if (optional) true else null,
    )

    private inner class FakeStore(
        vararg docs: HoldableDocument,
    ) : DocumentHoldStore {
        val docs = docs.associateBy { it.docUuid }.toMutableMap()
        val marks = mutableListOf<Pair<String, List<String>>>()

        override fun lockForHold(docUuid: UUID) = docs[docUuid]

        override fun markHeld(
            docUuid: UUID,
            reasons: List<String>,
        ) {
            log += "markHeld"
            marks += DocumentStates.HELD to reasons
            docs[docUuid] = docs.getValue(docUuid).copy(state = DocumentStates.HELD, holdReasons = reasons)
        }
    }

    private inner class FakePublisher(
        private val failure: RuntimeException? = null,
    ) : HeldEventPublisher {
        val events = mutableListOf<HeldEvent>()

        override fun stage(event: HeldEvent) {
            log += "stage"
            failure?.let { throw it }
            events += event
        }
    }

    private inner class FakeTx : TransactionRunner {
        var commits = 0
        var rollbacks = 0
        var calls = 0

        override fun <T> inTransaction(block: () -> T): T {
            calls++
            return try {
                block().also { commits++ }
            } catch (e: Throwable) {
                rollbacks++
                throw e
            }
        }
    }

    private val held = GateDecision.Held(listOf("ING-016", "PER-009"))
    private val store = FakeStore(doc(u, "INGESTED"), doc(u2, "INGESTED", optional = false), doc(u3, "READY"))
    private val pub = FakePublisher()
    private val tx = FakeTx()
    private val useCase = HoldDocument(store, pub, tx)

    @Test
    fun `INV-AC-20647 first hold of D marks HELD and stages one event in one committed transaction`() {
        useCase.hold(u, held)
        store.marks shouldBe listOf(DocumentStates.HELD to listOf("ING-016", "PER-009"))
        pub.events shouldBe
            listOf(
                HeldEvent(
                    u,
                    listOf("ING-016", "PER-009"),
                    "E-001",
                    Direction.SALE,
                    LocalDate.parse("2026-03-20"),
                    "2026-Q1",
                    posting,
                    true,
                ),
            )
        tx.calls shouldBe 1
        tx.commits shouldBe 1
        tx.rollbacks shouldBe 0
    }

    @Test
    fun `INV-AC-20647 replay with the same reasons hands nothing to either port`() {
        useCase.hold(u, held)
        useCase.hold(u, held)
        store.marks.size shouldBe 1
        pub.events.size shouldBe 1
        tx.commits shouldBe 2
    }

    @Test
    fun `INV-AC-20647 different reasons replace the stored list and stage one new event`() {
        useCase.hold(u, held)
        useCase.hold(u, GateDecision.Held(listOf("PER-009")))
        store.marks.last() shouldBe (DocumentStates.HELD to listOf("PER-009"))
        pub.events.size shouldBe 2
        pub.events.last().holdReasons shouldBe listOf("PER-009")
    }

    @Test
    fun `INV-AC-20647 D2 without optional facts stages null posting timestamp and invoice flag`() {
        useCase.hold(u2, held)
        pub.events.single().postingTimestamp shouldBe null
        pub.events.single().invoiceIssuedOrReceived shouldBe null
    }

    @Test
    fun `INV-AC-20647 D3 in state READY is refused and nothing is written`() {
        assertThrows<IllegalStateException> { useCase.hold(u3, held) }
        store.marks.size shouldBe 0
        pub.events.size shouldBe 0
        tx.rollbacks shouldBe 1
    }

    @Test
    fun `INV-AC-20647 Proceed decision is refused before any port call`() {
        assertThrows<IllegalArgumentException> { useCase.hold(u, GateDecision.Proceed) }
        tx.calls shouldBe 0
        log shouldBe emptyList()
        store.marks.size shouldBe 0
    }

    @Test
    fun `INV-AC-20647 publisher failure rolls the transaction back and reaches the caller`() {
        val boom = IllegalStateException("outbox down")
        val failing = HoldDocument(store, FakePublisher(boom), tx)
        val thrown = assertThrows<IllegalStateException> { failing.hold(u, held) }
        thrown shouldBe boom
        tx.rollbacks shouldBe 1
        tx.commits shouldBe 0
    }

    @Test
    fun `call order is markHeld before stage`() {
        useCase.hold(u, held)
        log shouldBe listOf("markHeld", "stage")
    }

    @Test
    fun `stored reasons are compared in step order`() {
        useCase.hold(u, GateDecision.Held(listOf("PER-009", "ING-016")))
        useCase.hold(u, held)
        store.marks.size shouldBe 2
        pub.events.size shouldBe 2
        store.docs.getValue(u).holdReasons shouldBe listOf("ING-016", "PER-009")
    }
}
