package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.application.document.CreateDocumentVersion
import com.dynokore.gst.canonical.application.document.DocumentStore
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** INV-REQ-637 and INV-REQ-2574: a correction is a new version, created under optimistic concurrency. */
class DocumentVersionsTest {
    private val kit = DocumentKit()
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val day = LocalDate.of(2026, 3, 15)

    private fun versionRows(doc: UUID) =
        DocumentSql
            .rows("select version::text from canonical.document_version where doc_uuid = ? order by version", doc)
            .map { it[0]!!.toInt() }

    private fun pointer(doc: UUID) =
        DocumentSql.one("select current_version::text from canonical.document where doc_uuid = ?", doc)!!.toInt()

    private fun amend(
        doc: UUID,
        from: Int,
        marker: String,
        on: LocalDate = day,
        use: CreateDocumentVersion = kit.versions,
    ) = kit.asTenant(tenant) { use.createVersion(doc, from, DocumentKit.version(on, """{"marker":"$marker"}""")) }

    @Test
    fun `INV-AC-2575 two concurrent amendments from version 1 give one version 2 and one conflict`() {
        repeat(ROUNDS) { round ->
            val document = DocumentKit.document(tenant, entity, day)
            kit.insert(document)
            val barrier = CyclicBarrier(2)
            val racing =
                CreateDocumentVersion(
                    object : DocumentStore by kit.store {
                        override fun createVersion(
                            docUuid: UUID,
                            expectedVersion: Int,
                            version: com.dynokore.gst.canonical.domain.document.NewVersion,
                        ): UpdateOutcome {
                            // both are inside their transaction and both hold version 1
                            barrier.await(10, TimeUnit.SECONDS)
                            return kit.store.createVersion(docUuid, expectedVersion, version)
                        }
                    },
                    kit.transaction,
                )
            val pool = Executors.newFixedThreadPool(2)
            val outcomes =
                try {
                    listOf("A$round", "B$round")
                        .map { marker ->
                            marker to pool.submit<UpdateOutcome> { amend(document.docUuid, 1, marker, use = racing) }
                        }.map { (marker, future) -> marker to future.get(30, TimeUnit.SECONDS) }
                } finally {
                    pool.shutdownNow()
                }
            val winners = outcomes.filter { it.second == UpdateOutcome.Updated(2) }
            val losers = outcomes.filter { it.second == UpdateOutcome.Conflict }
            winners.size shouldBe 1
            losers.size shouldBe 1
            versionRows(document.docUuid) shouldBe listOf(1, 2)
            pointer(document.docUuid) shouldBe 2
            val loserMarker = losers.single().first
            DocumentSql.one(
                "select count(*) from canonical.document_version where doc_uuid = ? and canonical::text like ?",
                document.docUuid,
                "%$loserMarker%",
            ) shouldBe "0"
            // the retry of the loser from version 2 is the next consecutive version
            amend(document.docUuid, 2, loserMarker) shouldBe UpdateOutcome.Updated(3)
            versionRows(document.docUuid) shouldBe listOf(1, 2, 3)
            pointer(document.docUuid) shouldBe 3
        }
    }

    @Test
    fun `INV-AC-2575 a stale expected version or an unknown document writes nothing`() {
        val document = DocumentKit.document(tenant, entity, day)
        kit.insert(document)
        amend(document.docUuid, 1, "first") shouldBe UpdateOutcome.Updated(2)
        amend(document.docUuid, 1, "stale") shouldBe UpdateOutcome.Conflict
        amend(document.docUuid, 3, "ahead") shouldBe UpdateOutcome.Conflict
        amend(UUID.randomUUID(), 1, "unknown") shouldBe UpdateOutcome.Conflict
        versionRows(document.docUuid) shouldBe listOf(1, 2)
    }

    @Test
    fun `INV-AC-2575 a document of another tenant is a conflict and is not written`() {
        val document = DocumentKit.document(UUID.randomUUID(), entity, day)
        kit.insert(document)
        amend(document.docUuid, 1, "foreign") shouldBe UpdateOutcome.Conflict
        versionRows(document.docUuid) shouldBe listOf(1)
    }

    @Test
    fun `an amendment sets the transaction date and leaves the partition date`() {
        val document = DocumentKit.document(tenant, entity, day)
        kit.insert(document)
        val moved = LocalDate.of(2026, 4, 2)
        amend(document.docUuid, 1, "move", on = moved) shouldBe UpdateOutcome.Updated(2)
        DocumentSql.rows(
            "select transaction_date::text, partition_date::text from canonical.document where doc_uuid = ?",
            document.docUuid,
        ) shouldBe listOf(listOf("2026-04-02", "2026-03-15"))
    }

    @Test
    fun `INV-AC-1712 a correction creates version 2 and version 1 is equal in every column`() {
        val document =
            DocumentKit.document(
                tenant,
                entity,
                day,
                canonical =
                    """{"a": 1, "b": [1, 2, 3], "lines": {"value": [], "classification": "content"}, """ +
                        """"taxSubtotals": {"value": [], "classification": "content"}, """ +
                        """"parties": {"value": {"supplier": {}, "buyer": {}}, "classification": "content"}}""",
            )
        kit.insert(document)
        val before = versionOne(document.docUuid)
        amend(document.docUuid, 1, "correction", on = LocalDate.of(2026, 5, 1)) shouldBe UpdateOutcome.Updated(2)
        versionRows(document.docUuid) shouldBe listOf(1, 2)
        versionOne(document.docUuid) shouldBe before
        before.isNotEmpty() shouldBe true
    }

    /** The whole row of version 1 as one text value: every column, canonical JSONB and created_at included. */
    private fun versionOne(doc: UUID) =
        DocumentSql.rows("select v::text from canonical.document_version v where doc_uuid = ? and version = 1", doc)

    private companion object {
        const val ROUNDS = 10
    }
}
