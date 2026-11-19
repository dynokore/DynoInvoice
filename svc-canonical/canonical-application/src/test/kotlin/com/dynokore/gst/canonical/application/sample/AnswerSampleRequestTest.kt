package com.dynokore.gst.canonical.application.sample

import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import com.dynokore.gst.canonical.domain.derivation.UpstreamFunction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The use case over fake ports: the window, the periods, the functions and the pages (INV-REQ-772 b1). */
class AnswerSampleRequestTest {
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val march = PeriodSpan("2026-03", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31))
    private val april = PeriodSpan("2026-04", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30))
    private val request = SampleRequest(tenant, "dry-1", "ENT-1", entity, "2026-03", "2026-04")

    private class Recording : SamplePagePublisher {
        val pages = mutableListOf<SamplePage>()

        override fun publish(
            tenantId: UUID,
            page: SamplePage,
        ) {
            pages += page
        }
    }

    private class FakeReader(
        val documents: List<SampleDocument>,
    ) : SampleDocumentReader {
        val windows = mutableListOf<Pair<LocalDate, LocalDate>>()

        override fun distinctTypeCodes(
            tenantId: UUID,
            entityId: UUID,
            from: LocalDate,
            to: LocalDate,
        ): Set<Int> {
            windows += from to to
            return inWindow(from, to).map { it.documentTypeCode }.toSet()
        }

        override fun read(
            tenantId: UUID,
            entityId: UUID,
            from: LocalDate,
            to: LocalDate,
            limit: Int,
            afterDocUuid: UUID?,
        ): List<SampleDocument> {
            windows += from to to
            return inWindow(from, to).filter { afterDocUuid == null || it.docUuid > afterDocUuid }.take(limit)
        }

        private fun inWindow(
            from: LocalDate,
            to: LocalDate,
        ) = documents
            .filter { !it.transactionDate.isBefore(from) && !it.transactionDate.isAfter(to) }
            .sortedBy { it.docUuid }
    }

    private fun document(
        on: LocalDate,
        code: Int = 380,
        absent: Boolean = false,
        preceding: String? = null,
    ) = SampleDocument(
        UUID.randomUUID(),
        on,
        code,
        "SR",
        preceding,
        if (absent) "0.00" else "100.00",
        if (absent) "0.00" else "9.00",
        absent,
    )

    private val functions =
        SampleDocumentFunctions {
            mapOf(
                380 to UpstreamFunction.BILL_FOR_PAYMENT,
                381 to UpstreamFunction.ADJUSTMENT,
                751 to UpstreamFunction.NOT_A_BILL,
            )[it]
        }

    private fun calendar(answer: SamplePeriods) = SamplePeriodCalendar { _, _, _, _ -> answer }

    private fun use(
        reader: SampleDocumentReader,
        publisher: SamplePagePublisher,
        answer: SamplePeriods = SamplePeriods.Known(listOf(march, april)),
        maxItems: Int = 3,
    ) = AnswerSampleRequest(reader, publisher, functions, calendar(answer), maxItems)

    @Test
    fun `INV-AC-19916 the reader is called with the start of the first span and the end of the last`() {
        val reader = FakeReader(emptyList())
        use(reader, Recording()).answer(request)
        reader.windows.toSet() shouldBe setOf(march.start to april.end)
    }

    @Test
    fun `INV-AC-19916 an unknown calendar answer fails before any read and any page`() {
        val reader = FakeReader(listOf(document(march.start)))
        val publisher = Recording()
        shouldThrow<SampleWindowUnresolvedException> { use(reader, publisher, SamplePeriods.Unknown).answer(request) }
        reader.windows.shouldBeEmpty()
        publisher.pages.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-19916 a reference not in the spans, a reversed window, a gap and an overlap fail with no page`() {
        val gap = PeriodSpan("2026-04", LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 30))
        val overlap = PeriodSpan("2026-04", LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30))
        listOf(
            request.copy(lastPeriodRef = "2026-09") to listOf(march, april),
            request.copy(firstPeriodRef = "2026-09") to listOf(march, april),
            request.copy(firstPeriodRef = "2026-04", lastPeriodRef = "2026-03") to listOf(march, april),
            request to listOf(march, gap),
            request to listOf(march, overlap),
            request to listOf(april, march),
        ).forEach { (asked, spans) ->
            val reader = FakeReader(listOf(document(march.start)))
            val publisher = Recording()
            shouldThrow<SampleWindowUnresolvedException> {
                use(reader, publisher, SamplePeriods.Known(spans)).answer(asked)
            }
            reader.windows.shouldBeEmpty()
            publisher.pages.shouldBeEmpty()
        }
    }

    @Test
    fun `INV-AC-19916 documents on the boundary days of two spans get the period of their own span`() {
        val lastOfMarch = document(march.end)
        val firstOfApril = document(april.start)
        val publisher = Recording()
        use(FakeReader(listOf(lastOfMarch, firstOfApril)), publisher).answer(request)
        val items =
            publisher.pages
                .single()
                .documents
                .associate { it.docUuid to it.periodRef }
        items shouldBe mapOf(lastOfMarch.docUuid to "2026-03", firstOfApril.docUuid to "2026-04")
    }

    @Test
    fun `INV-AC-19916 the function of every item is the answer of the port for its type code`() {
        val documents = listOf(document(march.start, 380), document(march.start, 381), document(april.start, 751))
        val publisher = Recording()
        use(FakeReader(documents), publisher).answer(request)
        val byDoc =
            publisher.pages
                .single()
                .documents
                .associate { it.docUuid to it.documentFunction }
        byDoc shouldBe
            mapOf(
                documents[0].docUuid to "BILL_FOR_PAYMENT",
                documents[1].docUuid to "ADJUSTMENT",
                documents[2].docUuid to "NOT_A_BILL",
            )
    }

    @Test
    fun `INV-AC-19916 an unmapped type code fails with no page even when it is the last document`() {
        val documents = (1..4).map { document(march.start) } + document(april.end, 999)
        val publisher = Recording()
        val failure =
            shouldThrow<SampleFunctionUnmappedException> { use(FakeReader(documents), publisher).answer(request) }
        failure.documentTypeCode shouldBe 999
        publisher.pages.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-19916 a window with no documents yields one empty last page`() {
        val publisher = Recording()
        use(FakeReader(emptyList()), publisher).answer(request)
        publisher.pages shouldBe listOf(SamplePage("dry-1", 1, true, emptyList()))
    }

    @Test
    fun `INV-AC-19916 a window with one empty period yields the documents that exist`() {
        val only = document(march.start)
        val publisher = Recording()
        use(FakeReader(listOf(only)), publisher).answer(request)
        publisher.pages
            .single()
            .documents
            .map { it.docUuid } shouldBe listOf(only.docUuid)
    }

    @Test
    fun `INV-AC-19916 exactly maxItems documents yield one last page and one more yields two pages`() {
        val three = (1..3).map { document(march.start) }
        val one = Recording()
        use(FakeReader(three), one).answer(request)
        one.pages.map { Triple(it.pageNumber, it.lastPage, it.documents.size) } shouldBe listOf(Triple(1, true, 3))

        val four = three + document(april.start)
        val two = Recording()
        use(FakeReader(four), two).answer(request)
        two.pages.map { Triple(it.pageNumber, it.lastPage, it.documents.size) } shouldBe
            listOf(Triple(1, false, 3), Triple(2, true, 1))
        two.pages.flatMap { it.documents.map { item -> item.docUuid } } shouldBe four.map { it.docUuid }.sorted()
    }

    @Test
    fun `INV-AC-19916 sgdValuesAbsent is written only when true and a credit note keeps its preceding date`() {
        val flagged = document(march.start, absent = true)
        val credit = document(april.start, 381, preceding = "2026-02-27")
        val publisher = Recording()
        use(FakeReader(listOf(flagged, credit)), publisher).answer(request)
        val items =
            publisher.pages
                .single()
                .documents
                .associateBy { it.docUuid }
        items.getValue(flagged.docUuid).payload()["sgdValuesAbsent"] shouldBe true
        items.getValue(credit.docUuid).payload().containsKey("sgdValuesAbsent") shouldBe false
        items.getValue(credit.docUuid).payload()["precedingDocumentDate"] shouldBe "2026-02-27"
        items.getValue(flagged.docUuid).payload().containsKey("precedingDocumentDate") shouldBe false
        items.getValue(flagged.docUuid).entityRef shouldBe "ENT-1"
    }
}
