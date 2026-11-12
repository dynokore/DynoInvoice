package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

class ReleaseMasterDataHoldTest {
    private fun id(n: Int) = UUID.fromString("00000000-0000-0000-0000-0000000000d$n")

    private val d = (0..9).map { id(it) }
    private val log = mutableListOf<String>()

    private fun doc(
        n: Int,
        state: String,
        reasons: List<String>,
        entity: String = "E1",
    ) = HoldableDocument(id(n), entity, Direction.SALE, LocalDate.parse("2026-03-20"), "2026-Q1", state, reasons)

    private inner class FakeStore : DocumentReleaseStore {
        val docs =
            listOf(
                doc(1, "HELD", listOf("MDM-001")),
                doc(2, "HELD", listOf("MDM-001", "ING-016")),
                doc(3, "INGESTED", emptyList()),
                doc(4, "HELD", listOf("ING-016")),
                doc(5, "HELD", emptyList()),
                doc(6, "HELD", listOf("mdm-001")),
                doc(7, "HELD", listOf("MDM-0010")),
                doc(8, "HELD", listOf("MDM-001"), "E8"),
            ).associateBy { it.docUuid }.toMutableMap()

        override fun lockForRelease(docUuid: UUID): HoldableDocument? {
            log += "lock ${docUuid.toString().takeLast(2)}"
            return docs[docUuid]
        }

        override fun markReleased(docUuid: UUID) {
            log += "mark ${docUuid.toString().takeLast(2)}"
            docs[docUuid] = docs.getValue(docUuid).copy(state = DocumentStates.INGESTED, holdReasons = emptyList())
        }
    }

    private inner class FakeTx : TransactionRunner {
        var opened = 0
        var commits = 0
        var rollbacks = 0
        private var snapshot: Map<UUID, HoldableDocument> = emptyMap()

        override fun <T> inTransaction(block: () -> T): T {
            opened++
            snapshot = store.docs.toMap()
            return try {
                block().also { commits++ }
            } catch (e: Throwable) {
                rollbacks++
                store.docs.clear()
                store.docs.putAll(snapshot)
                throw e
            }
        }
    }

    private val store = FakeStore()
    private val tx = FakeTx()
    private val events = mutableListOf<ReleasedEvent>()
    private var failure: RuntimeException? = null
    private val publisher =
        ReleasedEventPublisher {
            log += "stage ${it.docUuid.toString().takeLast(2)}"
            failure?.let { f -> throw f }
            events += it
        }
    private val useCase = ReleaseMasterDataHold(store, publisher, tx)

    @Test
    fun `INV-AC-21246 first call returns the eight entries in input order and releases only d1`() {
        val result = useCase.release(listOf(d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[9], d[1]))
        result.entries.map { it.key to it.value } shouldBe
            listOf(
                d[1] to ReleaseResult.RELEASED,
                d[2] to ReleaseResult.NOT_MASTER_DATA,
                d[3] to ReleaseResult.NOT_HELD,
                d[4] to ReleaseResult.NOT_MASTER_DATA,
                d[5] to ReleaseResult.NOT_MASTER_DATA,
                d[6] to ReleaseResult.NOT_MASTER_DATA,
                d[7] to ReleaseResult.NOT_MASTER_DATA,
                d[9] to ReleaseResult.NOT_FOUND,
            )
        log shouldBe
            listOf(
                "lock d1",
                "mark d1",
                "stage d1",
                "lock d2",
                "lock d3",
                "lock d4",
                "lock d5",
                "lock d6",
                "lock d7",
                "lock d9",
            )
        events shouldBe listOf(ReleasedEvent(d[1], listOf("MDM-001"), "E1"))
        tx.opened shouldBe 1
        tx.commits shouldBe 1
        tx.rollbacks shouldBe 0
    }

    @Test
    fun `INV-AC-21246 second call returns d1 NOT_HELD and calls neither markReleased nor stage`() {
        useCase.release(listOf(d[1]))
        log.clear()
        events.clear()
        useCase.release(listOf(d[1])) shouldBe mapOf(d[1] to ReleaseResult.NOT_HELD)
        log shouldBe listOf("lock d1")
        events shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21246 publisher failure for d8 propagates, rolls back and leaves d8 HELD with MDM-001`() {
        failure = IllegalStateException("outbox down")
        assertThrows<IllegalStateException> { useCase.release(listOf(d[8])) }
        tx.rollbacks shouldBe 1
        tx.commits shouldBe 0
        store.docs.getValue(d[8]).state shouldBe DocumentStates.HELD
        store.docs.getValue(d[8]).holdReasons shouldBe listOf("MDM-001")
    }

    @Test
    fun `INV-AC-21246 empty list returns an empty map and calls no port`() {
        useCase.release(emptyList()) shouldBe emptyMap()
        log shouldBe emptyList()
        tx.opened shouldBe 0
    }

    @Suppress("ObjectLiteralToLambda") // the AC requires an anonymous object with exactly the declared members
    @Test
    fun `INV-AC-21246 ports declare exactly the designed members`() {
        val s =
            object : DocumentReleaseStore {
                override fun lockForRelease(docUuid: UUID): HoldableDocument? = null

                override fun markReleased(docUuid: UUID) = Unit
            }
        val p =
            object : ReleasedEventPublisher {
                override fun stage(event: ReleasedEvent) = Unit
            }
        s.lockForRelease(d[0]) shouldBe null
        p.stage(ReleasedEvent(d[0], listOf("MDM-001"), "E"))
        MasterDataHoldCodes.CODES shouldBe setOf("MDM-001")
    }
}
