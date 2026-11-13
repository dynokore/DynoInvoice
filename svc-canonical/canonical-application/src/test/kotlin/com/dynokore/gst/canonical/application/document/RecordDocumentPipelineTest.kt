package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.gst.canonical.application.source.SourceEventStore
import com.dynokore.gst.canonical.domain.source.SourceKey
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

class RecordDocumentPipelineTest {
    private val tenant = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private var open = false
    private var nextDoc = 0
    private val log = mutableListOf<String>()

    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                open = true
                try {
                    return block()
                } finally {
                    open = false
                }
            }
        }

    private val sourceStore =
        object : SourceEventStore {
            val rows = linkedMapOf<SourceKey, Pair<UUID, String>>()

            override fun lockSourceKey(key: SourceKey) = Unit

            override fun docUuidOf(key: SourceKey): UUID? = rows[key]?.first

            override fun boundDocUuid(key: SourceKey): UUID? =
                rows.entries
                    .firstOrNull { it.key.copy(sourceRevision = 0) == key.copy(sourceRevision = 0) }
                    ?.value
                    ?.first

            override fun resultOf(key: SourceKey): String? = rows[key]?.second

            override fun insert(
                key: SourceKey,
                docUuid: UUID,
                result: String,
            ): Boolean {
                rows[key] = docUuid to result
                return true
            }
        }

    private class Upsert(
        val docUuid: UUID,
        val partyRef: String,
        val inTransaction: Boolean,
    )

    private val upserts = mutableListOf<Upsert>()
    private val partyRows = linkedMapOf<UUID, String>()
    private val partyStore =
        object : DocumentPartyRefs {
            override fun upsert(
                docUuid: UUID,
                partyRef: String,
            ) {
                upserts += Upsert(docUuid, partyRef, open)
                partyRows[docUuid] = partyRef
            }
        }

    private val answers = ArrayDeque<PartyRefAnswer>()
    private val directoryCalls = mutableListOf<Pair<CounterpartyLookup, Boolean>>()
    private val directory =
        object : CounterpartyDirectory {
            override fun partyRefOf(lookup: CounterpartyLookup): PartyRefAnswer {
                directoryCalls += lookup to open
                return answers.removeFirst()
            }
        }

    private val pipeline =
        RecordDocumentPipeline(
            directory,
            RecordSourceEvent(sourceStore, tx) { UUID(0, (++nextDoc).toLong()) },
            RecordPartyRef(partyStore),
        )

    private var buildCalls = 0

    private fun key(
        name: String,
        revision: Long = 1,
    ) = SourceKey(tenant, "erp", name, revision)

    private fun lookup(name: String) = CounterpartyLookup("E1", "COMPANY", "0195", name, LocalDate.of(2026, 10, 1))

    private fun run(
        key: SourceKey,
        lookup: CounterpartyLookup?,
    ) = pipeline.record(key, lookup) { doc ->
        buildCalls++
        "result-${key.sourceKey}-${key.sourceRevision}-$doc"
    }

    @Test
    fun `INV-AC-21374 first record calls the directory once outside a transaction, upserts inside the record one`() {
        answers += PartyRefAnswer.Found("CTP-118")
        val recorded = run(key("K1"), lookup("K1"))
        directoryCalls.map { it.first } shouldBe listOf(lookup("K1"))
        directoryCalls.map { it.second } shouldBe listOf(false)
        upserts.size shouldBe 1
        upserts[0].docUuid shouldBe recorded.docUuid
        upserts[0].partyRef shouldBe "CTP-118"
        upserts[0].inTransaction shouldBe true
        recorded.result shouldBe "result-K1-1-${recorded.docUuid}"
        recorded.inserted shouldBe true
        buildCalls shouldBe 1
    }

    @Test
    fun `INV-AC-21374 NotFound and Unavailable complete the record and write no party reference`() {
        answers += PartyRefAnswer.NotFound
        answers += PartyRefAnswer.Unavailable
        val k2 = run(key("K2"), lookup("K2"))
        val k3 = run(key("K3"), lookup("K3"))
        k2.result shouldBe "result-K2-1-${k2.docUuid}"
        k3.result shouldBe "result-K3-1-${k3.docUuid}"
        upserts.size shouldBe 0
        partyRows shouldBe emptyMap()
        buildCalls shouldBe 2
        directoryCalls.map { it.second } shouldBe listOf(false, false)
    }

    @Test
    fun `INV-AC-21374 a repeat calls the directory again but builds and upserts nothing, an amendment replaces`() {
        answers += PartyRefAnswer.Found("CTP-118")
        val first = run(key("K1"), lookup("K1"))
        answers += PartyRefAnswer.Found("CTP-118")
        val repeat = run(key("K1"), lookup("K1"))
        repeat.result shouldBe first.result
        repeat.docUuid shouldBe first.docUuid
        repeat.inserted shouldBe false
        directoryCalls.size shouldBe 2
        directoryCalls.map { it.second } shouldBe listOf(false, false)
        buildCalls shouldBe 1
        upserts.size shouldBe 1
        partyRows shouldBe mapOf(first.docUuid to "CTP-118")

        answers += PartyRefAnswer.Found("CTP-119")
        val amended = run(key("K1", revision = 2), lookup("K1"))
        amended.docUuid shouldBe first.docUuid
        directoryCalls.size shouldBe 3
        buildCalls shouldBe 2
        upserts.size shouldBe 2
        upserts[1].inTransaction shouldBe true
        partyRows shouldBe mapOf(first.docUuid to "CTP-119")
    }

    @Test
    fun `INV-AC-21374 a null lookup calls no directory and persists nothing`() {
        val recorded = run(key("K6"), null)
        directoryCalls.size shouldBe 0
        upserts.size shouldBe 0
        recorded.result shouldBe "result-K6-1-${recorded.docUuid}"
    }

    @Test
    fun `INV-AC-21374 a directory exception propagates and no record is written`() {
        val failing =
            RecordDocumentPipeline(
                object : CounterpartyDirectory {
                    override fun partyRefOf(lookup: CounterpartyLookup): PartyRefAnswer = error("boom")
                },
                RecordSourceEvent(sourceStore, tx) { UUID(0, 99) },
                RecordPartyRef(partyStore),
            )
        assertThrows<IllegalStateException> { failing.record(key("K7"), lookup("K7")) { "x" } }
        sourceStore.rows shouldBe emptyMap()
        upserts.size shouldBe 0
    }
}
