package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqSampleDocumentReader
import com.dynokore.gst.canonical.application.sample.AnswerSampleRequest
import com.dynokore.gst.canonical.application.sample.SampleDocumentFunctions
import com.dynokore.gst.canonical.application.sample.SamplePage
import com.dynokore.gst.canonical.application.sample.SamplePeriodCalendar
import com.dynokore.gst.canonical.application.sample.SamplePeriods
import com.dynokore.gst.canonical.application.sample.SampleRequest
import com.dynokore.gst.canonical.domain.derivation.UpstreamFunction
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Page size, page numbers, order and schema conformance of the sample pages over PostgreSQL (INV-REQ-772 b1). */
class SamplePagingTest {
    private val kit = DocumentKit()
    private val reader = JooqSampleDocumentReader(kit.dsl)
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val request = SampleRequest(tenant, "dry-9", "ENT-A", entity, "2026-03", "2026-04")
    private val functions = SampleDocumentFunctions { code -> UpstreamFunction.BILL_FOR_PAYMENT.takeIf { code == 380 } }
    private val subtotals = listOf(SampleKit.Sub("SR", "100.00", "9.00"))

    private fun seed(count: Int): List<UUID> =
        (1..count).map { index ->
            val day = LocalDate.of(2026, 3, 1).plusDays((index % 60).toLong())
            DocumentKit
                .document(
                    tenant,
                    entity,
                    day,
                    canonical = SampleKit.canonical(subtotals),
                ).also { kit.insert(it) }
                .docUuid
        }

    private fun answer(): List<SamplePage> {
        val publisher = RecordingPublisher()
        val calendar =
            SamplePeriodCalendar {
                _,
                _,
                _,
                _,
                ->
                SamplePeriods.Known(listOf(SampleKit.march, SampleKit.april))
            }
        kit.asTenant(tenant) {
            kit.transaction.inTransaction {
                AnswerSampleRequest(reader, publisher, functions, calendar, SampleKit.maxItems).answer(request)
            }
        }
        return publisher.pages
    }

    @Test
    fun `INV-AC-19916 the page limit is the maxItems of the schema the build consumes`() {
        SampleKit.maxItems shouldBe
            SampleKit.schemaTree
                .path("properties")
                .path("documents")
                .path("maxItems")
                .asInt()
        (SampleKit.maxItems > 1) shouldBe true
    }

    @Test
    fun `INV-AC-19916 exactly maxItems documents yield one last page that validates against the schema`() {
        val stored = seed(SampleKit.maxItems)
        val pages = answer()
        pages.map { Triple(it.pageNumber, it.lastPage, it.documents.size) } shouldBe
            listOf(Triple(1, true, SampleKit.maxItems))
        pages.single().documents.map { it.docUuid.toString() } shouldBe stored.map { it.toString() }.sorted()
        SampleKit.errors(pages.single()).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-19916 maxItems plus one documents yield two pages in doc uuid order that validate`() {
        val stored = seed(SampleKit.maxItems + 1)
        val pages = answer()
        pages.map { Triple(it.pageNumber, it.lastPage, it.documents.size) } shouldBe
            listOf(Triple(1, false, SampleKit.maxItems), Triple(2, true, 1))
        pages.flatMap { it.documents.map { item -> item.docUuid.toString() } } shouldBe
            stored.map { it.toString() }.sorted()
        pages.forEach {
            (it.documents.size <= SampleKit.maxItems) shouldBe true
            it.dryRunId shouldBe "dry-9"
            SampleKit.errors(it).shouldBeEmpty()
        }
    }

    @Test
    fun `INV-AC-19916 a window with no documents yields one empty last page that validates`() {
        val pages = answer()
        pages.map { Triple(it.pageNumber, it.lastPage, it.documents.size) } shouldBe listOf(Triple(1, true, 0))
        SampleKit.errors(pages.single()).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-19916 a window of which one period is empty yields the documents that exist`() {
        val only =
            DocumentKit
                .document(
                    tenant,
                    entity,
                    LocalDate.of(2026, 4, 3),
                    canonical = SampleKit.canonical(subtotals),
                ).also {
                    kit.insert(it)
                }
        val pages = answer()
        pages.single().documents.map { it.docUuid } shouldBe listOf(only.docUuid)
        pages
            .single()
            .documents
            .single()
            .periodRef shouldBe "2026-04"
    }

    @Test
    fun `INV-AC-19916 the main code of the sample holds no document type code literal and no page limit literal`() {
        val root = File("..").canonicalFile
        val app = "canonical-application/src/main/kotlin/com/dynokore/gst/canonical/application/sample"
        val adp = "canonical-adapters/src/main/kotlin/com/dynokore/gst/canonical/adapters/persistence"
        val files =
            listOf(
                "$app/SamplePorts.kt",
                "$app/AnswerSampleRequest.kt",
                "$adp/JooqSampleDocumentReader.kt",
            ).map { File(root, it) }
        files.forEach { it.isFile shouldBe true }
        val hits = files.flatMap { f -> f.readLines().filter { Regex("\\b(38[01]|500)\\b").containsMatchIn(it) } }
        hits.shouldBeEmpty()
    }
}
