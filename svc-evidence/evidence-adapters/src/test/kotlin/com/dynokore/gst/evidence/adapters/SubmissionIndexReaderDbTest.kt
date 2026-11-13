package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqSubmissionIndexReader
import com.dynokore.gst.evidence.application.lookup.ReverseLookup
import com.dynokore.gst.evidence.application.lookup.ReverseLookupFilter
import com.dynokore.gst.evidence.application.lookup.ReverseLookupRow
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-20722: the reader of the reverse lookup over `evidence.submission_index` and its category child rows. */
class SubmissionIndexReaderDbTest {
    private val tenant = UUID.randomUUID()
    private val sr = listOf("SR")
    private val zr = listOf("ZR")
    private val ackAt = Instant.parse("2026-02-01T10:00:00Z")

    private fun sql(vararg statements: String) =
        EvidenceDb.superuser().use { c -> c.createStatement().use { s -> statements.forEach { s.execute(it) } } }

    @Suppress("LongParameterList") // one column per argument, each defaulted to null
    private fun put(
        tenantId: UUID = tenant,
        doc: UUID,
        version: Int = 1,
        date: String? = "2026-01-15",
        number: String? = "INV-1",
        counterparty: String? = "201912345A",
        period: String? = "2026-Q1",
        state: String? = "SENT",
        ack: String? = null,
        codes: List<String> = emptyList(),
    ) {
        fun q(v: String?) = v?.let { "'$it'" } ?: "null"
        sql(
            "insert into evidence.submission_index (tenant_id, doc_uuid, version, period_ref, document_number, " +
                "document_date, counterparty_uen, ack_id, acknowledged_at, state, recorded_at) values " +
                "('$tenantId', '$doc', $version, ${q(period)}, ${q(number)}, ${q(date)}, ${q(counterparty)}, " +
                "${q(ack)}, ${if (ack == null) "null" else "'$ackAt'"}, ${q(state)}, now())",
            *codes
                .map {
                    "insert into evidence.submission_index_category values ('$tenantId', '$doc', $version, '$it')"
                }.toTypedArray(),
        )
    }

    private fun read(
        filter: ReverseLookupFilter = ReverseLookupFilter(),
        tenantId: UUID = tenant,
        limit: Int = 100,
        after: com.dynokore.gst.evidence.application.lookup.ReverseLookupKey? = null,
    ): List<ReverseLookupRow> =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenantId)
            JooqSubmissionIndexReader(DSL.using(c, SQLDialect.POSTGRES))
                .read(TenantId(tenantId), filter, after, limit)
                .also { c.commit() }
        }

    private fun docs(filter: ReverseLookupFilter) = read(filter).map { it.key.docUuid }.toSet()

    @Test
    fun `INV-AC-20722 each filter alone returns only matching versions`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        put(doc = a, number = "N-A", counterparty = "201912345A", period = "P1", date = "2026-01-10", codes = sr)
        put(doc = b, number = "N-B", counterparty = "200000001B", period = "P2", date = "2026-02-10", codes = zr)
        docs(ReverseLookupFilter(counterparty = "201912345A")) shouldBe setOf(a)
        docs(ReverseLookupFilter(documentNumber = "N-B")) shouldBe setOf(b)
        docs(ReverseLookupFilter(docUuid = a)) shouldBe setOf(a)
        docs(ReverseLookupFilter(periodRef = "P2")) shouldBe setOf(b)
        docs(ReverseLookupFilter(dateFrom = LocalDate.of(2026, 2, 1))) shouldBe setOf(b)
        docs(ReverseLookupFilter(dateTo = LocalDate.of(2026, 1, 31))) shouldBe setOf(a)
        docs(ReverseLookupFilter(categoryCode = "ZR")) shouldBe setOf(b)
        docs(ReverseLookupFilter(counterparty = "nobody")) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-20722 two filters return the intersection`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        put(doc = a, counterparty = "201912345A", period = "P1", codes = listOf("SR"))
        put(doc = b, counterparty = "201912345A", period = "P2", codes = listOf("SR"))
        docs(ReverseLookupFilter(counterparty = "201912345A", periodRef = "P2")) shouldBe setOf(b)
        docs(ReverseLookupFilter(categoryCode = "SR", periodRef = "P1")) shouldBe setOf(a)
        docs(ReverseLookupFilter(categoryCode = "SR", periodRef = "P3")) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-20722 the date range includes both ends and excludes the day before and after`() {
        val before = UUID.randomUUID()
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        val after = UUID.randomUUID()
        put(doc = before, date = "2026-01-09")
        put(doc = from, date = "2026-01-10")
        put(doc = to, date = "2026-01-20")
        put(doc = after, date = "2026-01-21")
        val f = ReverseLookupFilter(dateFrom = LocalDate.of(2026, 1, 10), dateTo = LocalDate.of(2026, 1, 20))
        docs(f) shouldBe setOf(from, to)
    }

    @Test
    fun `INV-AC-20722 a partial row with acknowledgement columns only is never returned`() {
        val d = UUID.randomUUID()
        put(
            doc = d,
            date = null,
            number = null,
            counterparty = null,
            period = null,
            state = "ACKNOWLEDGED",
            ack = "A1",
        )
        read() shouldBe emptyList()
        docs(ReverseLookupFilter(docUuid = d)) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-20722 a row with a document date and state null is never returned`() {
        put(doc = UUID.randomUUID(), state = null)
        read() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20722 a row with state SENT returns SENT and no ackId and no acknowledgedAt`() {
        val d = UUID.randomUUID()
        put(doc = d)
        val r = read().single()
        r.state shouldBe "SENT"
        r.ackId shouldBe null
        r.acknowledgedAt shouldBe null
        r.key.docUuid shouldBe d
        r.key.documentDate shouldBe LocalDate.of(2026, 1, 15)
    }

    @Test
    fun `INV-AC-20722 a row with the acknowledgement has state ACKNOWLEDGED its ackId and acknowledgedAt`() {
        put(doc = UUID.randomUUID(), state = "ACKNOWLEDGED", ack = "A1")
        val r = read().single()
        r.state shouldBe "ACKNOWLEDGED"
        r.ackId shouldBe "A1"
        r.acknowledgedAt shouldBe ackAt
    }

    @Test
    fun `INV-AC-20722 the categoryCode returns a two-code version once and look-alike sr does not match SR`() {
        val two = UUID.randomUUID()
        val other = UUID.randomUUID()
        val none = UUID.randomUUID()
        put(doc = two, codes = listOf("SR", "ZR"))
        put(doc = other, codes = listOf("ZR"))
        put(doc = none)
        read(ReverseLookupFilter(categoryCode = "SR")).map { it.key.docUuid } shouldBe listOf(two)
        read(ReverseLookupFilter(categoryCode = "ZR")).map { it.key.docUuid }.toSet() shouldBe setOf(two, other)
        docs(ReverseLookupFilter(categoryCode = "sr")) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-20722 a version with no child row matches no categoryCode`() {
        put(doc = UUID.randomUUID())
        docs(ReverseLookupFilter(categoryCode = "SR")) shouldBe emptySet()
        read().size shouldBe 1
    }

    @Test
    fun `INV-AC-20722 paging by limit 2 over five matches returns every match once`() {
        (1..5).forEach { put(doc = UUID.randomUUID(), date = "2026-01-1$it") }
        val seen = mutableListOf<ReverseLookupRow>()
        var after: com.dynokore.gst.evidence.application.lookup.ReverseLookupKey? = null
        val nextFlags = mutableListOf<Boolean>()
        do {
            val rows = read(limit = 3, after = after) // limit + 1 as the use case asks
            val page = rows.take(2)
            seen += page
            nextFlags += rows.size > 2
            after = page.lastOrNull()?.key
        } while (rows.size > 2)
        seen.size shouldBe 5
        seen.map { it.key }.toSet().size shouldBe 5
        seen.map { it.key.documentDate } shouldBe seen.map { it.key.documentDate }.sorted()
        nextFlags shouldBe listOf(true, true, false)
        ReverseLookup.decode(ReverseLookup.encode(seen[0].key)) shouldBe seen[0].key
    }

    @Test
    fun `INV-AC-20722 a second tenant's rows are never returned`() {
        val other = UUID.randomUUID()
        put(tenantId = other, doc = UUID.randomUUID(), codes = listOf("SR"))
        put(doc = UUID.randomUUID())
        read().size shouldBe 1
        read(ReverseLookupFilter(categoryCode = "SR")) shouldBe emptyList()
        read(tenantId = other).size shouldBe 1
    }

    @Test
    fun `INV-AC-20722 the look-alike counterparty 201912345a does not match 201912345A`() {
        val d = UUID.randomUUID()
        put(doc = d, counterparty = "201912345A")
        docs(ReverseLookupFilter(counterparty = "201912345a")) shouldBe emptySet()
        docs(ReverseLookupFilter(counterparty = "201912345A")) shouldBe setOf(d)
    }
}
