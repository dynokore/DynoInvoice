package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.DryRunDelta
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The run use case over in-memory ports (INV-REQ-772, INV-REQ-686, INV-REQ-703). */
class RunDryRunTest {
    private val t = LocalDate.of(2026, 10, 1)

    private fun uuid(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun item(
        n: Int,
        period: String = "P-A",
        date: LocalDate = t,
        code: String = "X",
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        buildMap {
            put("periodRef", period)
            put("docUuid", uuid(n))
            put("entityRef", "E")
            put("transactionDate", date.toString())
            put("internalTaxCode", code)
            put("documentFunction", "BILL_FOR_PAYMENT")
            put("supplyType", "STANDARD_RATED")
            put("taxableValue", "100.00")
            put("gstValue", "9.00")
            putAll(extra)
        }

    private fun credit(
        n: Int,
        period: String,
        date: LocalDate,
        preceding: LocalDate,
        extra: Map<String, Any?> = emptyMap(),
    ) = item(
        n,
        period,
        date,
        "X",
        mapOf("documentFunction" to "ADJUSTMENT", "precedingDocumentDate" to preceding.toString()) + extra,
    )

    private fun money(
        n: Int,
        taxable: String,
        gst: String,
        extra: Map<String, Any?> = emptyMap(),
    ) = item(n, extra = mapOf("taxableValue" to taxable, "gstValue" to gst) + extra)

    private class FakeSamples(
        var pages: List<SamplePage>,
    ) : HistoricalSamplePort {
        var released = 0
        var pagesCalls = 0

        override fun pages(
            dryRunId: String,
            window: DryRunWindow,
            wait: Duration,
        ): List<SamplePage> {
            pagesCalls++
            return pages
        }

        override fun release(dryRunId: String) {
            released++
        }
    }

    /** Code "X" is SR under v1 and TX under v2; every other code is unchanged. */
    private class FakeEvaluation(
        val failOn: String? = null,
        val fault: RuntimeException = IllegalStateException("boom"),
    ) : PackEvaluationPort {
        val versions = mutableListOf<String>()

        override fun evaluate(
            tenant: TenantId,
            packType: String,
            packVersion: String,
            document: SampledDocument,
        ): DocumentOutcome {
            versions += packVersion
            if (document.internalTaxCode == failOn) throw fault
            return DocumentOutcome(if (document.internalTaxCode == "X" && packVersion == "v2") "TX" else "SR")
        }

        override fun effectiveFrom(
            tenant: TenantId,
            packType: String,
            packVersion: String,
        ): LocalDate = LocalDate.of(2026, 10, 1)
    }

    private class StoreFault : RuntimeException("store down")

    private class FakeStore(
        private val throwsOn: String? = null,
    ) : DryRunDeltaStore {
        var begins = 0
        var completes = 0
        var unavailables = 0
        var run: StoredDryRun? = null

        override fun begin(start: DryRunStart) {
            begins++
            if (throwsOn == "begin") throw StoreFault()
            run = StoredDryRun(start.dryRunId, start.startedBy, DryRunState.RUNNING, null, null)
        }

        override fun complete(
            dryRunId: String,
            completedAt: Instant,
            delta: DryRunDelta,
        ) {
            completes++
            if (throwsOn == "complete") throw StoreFault()
            run = run?.copy(state = DryRunState.COMPLETE, delta = delta)
        }

        override fun unavailable(
            dryRunId: String,
            reason: String,
        ) {
            unavailables++
            if (throwsOn == "unavailable") throw StoreFault()
            run = run?.copy(state = DryRunState.SAMPLE_UNAVAILABLE, reason = reason)
        }

        override fun find(dryRunId: String) = run?.takeIf { it.dryRunId == dryRunId }
    }

    private class Rig(
        val samples: FakeSamples,
        val evaluation: FakeEvaluation = FakeEvaluation(),
        val store: FakeStore = FakeStore(),
    ) {
        fun counts() = listOf(store.begins, store.completes, store.unavailables, samples.released)

        fun run(): DryRunOutcome =
            RunDryRun(samples, evaluation, store, CLOCK).run(
                RunDryRunCommand(
                    DryRunStart(
                        "dr-1",
                        TenantId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
                        "SCOPE",
                        "v1",
                        "v2",
                        "user-1",
                        Instant.parse("2026-11-01T00:00:00Z"),
                    ),
                    DryRunWindow("P-A", "P-B", 2),
                    Duration.ofSeconds(1),
                ),
            )
    }

    private fun rig(vararg pages: SamplePage) = Rig(FakeSamples(pages.toList()))

    private fun page(
        n: Int,
        last: Boolean,
        vararg items: Map<String, Any?>,
    ) = SamplePage(n, last, items.toList())

    private fun Rig.delta(): DryRunDelta = store.run!!.delta!!

    @Test
    fun `an unchanged document is not listed and a changed one is, with the fixture values`() {
        val r = rig(page(1, true, item(1), item(2, code = "U")))
        r.run().shouldBeInstanceOf<DryRunOutcome.Complete>().changedCount shouldBe 1
        r
            .delta()
            .sample
            .single()
            .let { (it.docUuid to it.taxableValue) shouldBe (uuid(1) to "100.00") }
        r
            .delta()
            .sample
            .single()
            .let { (it.oldCategoryCode to it.newCategoryCode) shouldBe ("SR" to "TX") }
    }

    @Test
    fun `the same page is evaluated under the current and under the candidate pack version`() {
        val r = rig(page(1, true, item(1)))
        r.run()
        r.evaluation.versions shouldBe listOf("v1", "v2")
    }

    @Test
    fun `the store sees begin once and exactly one of complete or unavailable, and the sample is released once`() {
        val ok = rig(page(1, true, item(1)))
        ok.run()
        ok.counts() shouldBe listOf(1, 1, 0, 1)
        ok.store.find("dr-1")!!.startedBy shouldBe "user-1"
        ok.store.find("nope") shouldBe null
        val none = rig()
        none.run()
        none.counts() shouldBe listOf(1, 0, 1, 1)
        val boom = Rig(FakeSamples(listOf(page(1, true, item(1)))), FakeEvaluation(failOn = "X"))
        boom.run().shouldBeInstanceOf<DryRunOutcome.Unavailable>()
        boom.counts() shouldBe listOf(1, 0, 1, 1)
    }

    @Test
    fun `a store whose complete throws propagates, writes no unavailable and releases once`() {
        val r = Rig(FakeSamples(listOf(page(1, true, item(1)))), store = FakeStore("complete"))
        shouldThrow<StoreFault> { r.run() }
        listOf(r.store.unavailables, r.samples.released) shouldBe listOf(0, 1)
    }

    @Test
    fun `a store whose begin throws propagates, never asks for pages and releases once`() {
        val r = Rig(FakeSamples(listOf(page(1, true, item(1)))), store = FakeStore("begin"))
        shouldThrow<StoreFault> { r.run() }
        listOf(r.samples.pagesCalls, r.samples.released) shouldBe listOf(0, 1)
    }

    @Test
    fun `a store whose unavailable throws on an incomplete sample propagates and releases once`() {
        val r = Rig(FakeSamples(emptyList()), store = FakeStore("unavailable"))
        shouldThrow<StoreFault> { r.run() }
        r.samples.released shouldBe 1
    }

    @Test
    fun `an evaluation port that throws IllegalStateException ends SAMPLE_UNAVAILABLE with one release`() {
        val r = Rig(FakeSamples(listOf(page(1, true, item(1)))), FakeEvaluation(failOn = "X"))
        r.run().shouldBeInstanceOf<DryRunOutcome.Unavailable>()
        listOf(r.store.unavailables, r.samples.released) shouldBe listOf(1, 1)
    }

    @Test
    fun `an evaluation port that throws UnsupportedOperationException propagates, outside the named set`() {
        val fault = UnsupportedOperationException("no")
        val r = Rig(FakeSamples(listOf(page(1, true, item(1)))), FakeEvaluation(failOn = "X", fault = fault))
        shouldThrow<UnsupportedOperationException> { r.run() }
        listOf(r.store.completes, r.store.unavailables, r.samples.released) shouldBe listOf(0, 0, 1)
    }

    @Test
    fun `the run never calls the sample request port`() {
        var requests = 0
        val port = SampleRequestPort { requests++ }
        rig(page(1, true, item(1))).run()
        requests shouldBe 0
        port.request(sampleRequest())
        requests shouldBe 1
    }

    private fun sampleRequest() =
        DryRunSampleRequest(
            "dr-1",
            TenantId(UUID.randomUUID()),
            "entity-1",
            DryRunWindow("P-A", "P-B", 2),
            "1.0.0",
            "1.1.0",
        )

    @Test
    fun `INV-AC-20125 the sample request port hands over the whole request`() {
        val given = sampleRequest()
        var received: DryRunSampleRequest? = null
        val port = SampleRequestPort { received = it }
        port.request(given)
        val got = received!!
        got shouldBe given
        got.dryRunId shouldBe given.dryRunId
        got.tenant shouldBe given.tenant
        got.entityRef shouldBe given.entityRef
        got.window shouldBe given.window
        got.currentPackVersion shouldBe given.currentPackVersion
        got.candidatePackVersion shouldBe given.candidatePackVersion
    }

    @Test
    fun `INV-AC-20125 the dry run use case does not depend on the sample request port`() {
        val type = RunDryRun::class.java
        val constructorTypes = type.declaredConstructors.flatMap { it.parameterTypes.toList() }
        val fieldTypes = type.declaredFields.map { it.type }
        (SampleRequestPort::class.java in constructorTypes) shouldBe false
        (SampleRequestPort::class.java in fieldTypes) shouldBe false
    }

    @Test
    fun `INV-AC-20125 the sample request port has one entry point`() {
        val methods = SampleRequestPort::class.java.methods
        methods.map { it.name } shouldBe listOf("request")
        methods.single().parameterTypes.toList() shouldBe listOf(DryRunSampleRequest::class.java)
    }

    @Test
    fun `INV-AC-748 byPeriod counts 2 and 1 with the page identities, and the straddler is listed apart`() {
        val r =
            rig(
                page(
                    1,
                    true,
                    item(1, "P-A"),
                    item(2, "P-A"),
                    credit(3, "P-B", t.plusDays(30), t.minusDays(2)),
                ),
            )
        r.run()
        val d = r.delta()
        d.byPeriod.map { it.periodRef to it.documentCount } shouldBe listOf("P-A" to 2, "P-B" to 1)
        d.byPeriod.sumOf { it.documentCount } shouldBe d.changedCount
        d.straddlingCreditNotes.single().docUuid shouldBe uuid(3)
        d.sample.map { it.docUuid } shouldBe listOf(uuid(1), uuid(2))
        d.documents.map { it.docUuid to it.periodRef } shouldBe
            listOf(uuid(1) to "P-A", uuid(2) to "P-A", uuid(3) to "P-B")
    }

    @Test
    fun `INV-AC-748 periods whose strings sort against their dates come out in date order`() {
        val r = rig(page(1, true, item(1, "P-10", LocalDate.of(2026, 10, 1)), item(2, "P-9", LocalDate.of(2026, 9, 1))))
        r.run()
        r.delta().byPeriod.map { it.periodRef } shouldBe listOf("P-9", "P-10")
    }

    @Test
    fun `INV-AC-748 an unattributable item ends SAMPLE_UNAVAILABLE naming page and position, with no delta`() {
        val bad =
            listOf(
                item(2) - "periodRef",
                item(2) + ("periodRef" to ""),
                item(2) + ("periodRef" to "x".repeat(33)),
                item(2) + ("periodRef" to 5),
                item(2) - "periodRef" + ("periodReference" to "P"),
                item(2) - "periodRef" + ("periodRefs" to "P"),
                item(2) - "docUuid",
                item(2) + ("docUuid" to " "),
                item(2) + ("docUuid" to "nope"),
                item(2) + ("docUuid" to 9),
                item(2) - "docUuid" + ("docUUID" to uuid(2)),
                item(2) - "docUuid" + ("documentUuid" to uuid(2)),
            )
        bad.forEach { b ->
            val r = rig(page(1, false, item(1)), page(2, true, item(3), b))
            val out = r.run().shouldBeInstanceOf<DryRunOutcome.Unavailable>()
            out.reason shouldContain "page 2, item 2"
            r.store.run!!.state shouldBe DryRunState.SAMPLE_UNAVAILABLE
            r.store.run!!.delta shouldBe null
        }
    }

    @Test
    fun `INV-AC-748 a page of fully attributable items is not refused`() {
        rig(page(1, true, item(1), item(2, "P-B"))).run().shouldBeInstanceOf<DryRunOutcome.Complete>()
    }

    @Test
    fun `a flagged changed item is counted and listed with placeholders, and its page values never count`() {
        val flagged = money(1, "777.00", "70.00", mapOf("sgdValuesAbsent" to true))
        val r = rig(page(1, true, flagged, money(2, "1000.00", "90.00")))
        r.run().shouldBeInstanceOf<DryRunOutcome.Complete>()
        val d = r.delta()
        d.sample.first { it.docUuid == uuid(1) }.let {
            listOf(it.sgdValuesAbsent, it.taxableValue, it.gstValue) shouldBe listOf(true, "0.00", "0.00")
        }
        d.sample.first { it.docUuid == uuid(2) }.sgdValuesAbsent shouldBe null
        val expected = listOf("1000.00", "90.00", 1)
        d.changeTypeTotals.first().let { listOf(it.taxableValue, it.gstValue, it.valuesAbsentCount) shouldBe expected }
        d.byPeriod.single().let { listOf(it.taxableValue, it.gstValue, it.valuesAbsentCount) shouldBe expected }
        d.changedCount shouldBe 2
        d.changeTypeTotals.sumOf { it.documentCount } shouldBe 2
        d.byPeriod.sumOf { it.documentCount } shouldBe 2
        rig(page(1, true, money(2, "1000.00", "90.00")))
            .also { it.run() }
            .delta()
            .sample.size shouldBe 1
    }

    @Test
    fun `a look-alike member sgdValueAbsent does not flag the item`() {
        val r = rig(page(1, true, item(1, extra = mapOf("sgdValueAbsent" to true))))
        r.run()
        r
            .delta()
            .sample
            .single()
            .let { listOf(it.sgdValuesAbsent, it.taxableValue) shouldBe listOf(null, "100.00") }
        r
            .delta()
            .changeTypeTotals
            .first()
            .taxableValue shouldBe "100.00"
    }

    @Test
    fun `a flagged credit note in straddlingCreditNotes carries the flag and placeholders`() {
        val values = mapOf("sgdValuesAbsent" to true, "taxableValue" to "777.00", "gstValue" to "70.00")
        val cn = credit(1, "P-A", t, t.minusDays(1), values)
        val r = rig(page(1, true, cn))
        r.run()
        r.delta().straddlingCreditNotes.single().let {
            listOf(it.sgdValuesAbsent, it.taxableValue, it.gstValue) shouldBe listOf(true, "0.00", "0.00")
        }
        r.delta().sample shouldBe emptyList()
    }

    @Test
    fun `no page at all ends SAMPLE_UNAVAILABLE naming page 1 and stores no delta`() {
        val r = rig()
        r.run().shouldBeInstanceOf<DryRunOutcome.Unavailable>().reason shouldContain "page 1"
        r.store.run!!.delta shouldBe null
    }

    @Test
    fun `a last page after a missing page ends SAMPLE_UNAVAILABLE naming the first missing number`() {
        val r = rig(page(1, false, item(1)), page(4, true, item(2)))
        r.run().shouldBeInstanceOf<DryRunOutcome.Unavailable>().reason shouldContain "page 2 is missing"
        r.store.completes shouldBe 0
    }

    @Test
    fun `pages 1 up to the last present are not refused`() {
        rig(page(1, false, item(1)), page(2, false, item(2)), page(3, true, item(3)))
            .run()
            .shouldBeInstanceOf<DryRunOutcome.Complete>()
    }

    @Test
    fun `a sample whose last page has not arrived is incomplete`() {
        rig(page(1, false, item(1))).run().shouldBeInstanceOf<DryRunOutcome.Unavailable>().reason shouldContain "page 2"
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    }

    private fun noSupplyRun(vararg pages: SamplePage): Pair<Rig, DryRunOutcome> {
        val r = rig(*pages)
        return r to r.run()
    }

    private fun Pair<Rig, DryRunOutcome>.refusedNaming(
        page: Int,
        entity: String,
    ) {
        val out = second.shouldBeInstanceOf<DryRunOutcome.Unavailable>()
        out.reason shouldContain "dry run dr-1"
        out.reason shouldContain "page $page,"
        out.reason shouldContain "entityRef $entity"
        first.store.run!!.state shouldBe DryRunState.SAMPLE_UNAVAILABLE
        first.store.run!!.delta shouldBe null
        first.store.completes shouldBe 0
        first.samples.released shouldBe 1
    }

    @Test
    fun `INV-AC-20340 a changed item without supplyType ends SAMPLE_UNAVAILABLE naming it and stores no delta`() {
        noSupplyRun(page(1, true, item(1, extra = mapOf("entityRef" to "E-9")) - "supplyType")).refusedNaming(1, "E-9")
    }

    @Test
    fun `INV-AC-20340 the same item unchanged lets the run complete`() {
        val r = rig(page(1, true, item(1, code = "U") - "supplyType", item(2)))
        r.run().shouldBeInstanceOf<DryRunOutcome.Complete>().changedCount shouldBe 1
        r.store.run!!.state shouldBe DryRunState.COMPLETE
    }

    @Test
    fun `INV-AC-20340 two items without supplyType on pages 1 and 2 name the first in page order`() {
        noSupplyRun(
            page(2, true, item(2, extra = mapOf("entityRef" to "E-2")) - "supplyType"),
            page(1, false, item(1, extra = mapOf("entityRef" to "E-1")) - "supplyType"),
        ).refusedNaming(1, "E-1")
    }

    @Test
    fun `INV-AC-20340 the member supplyTypes in place of supplyType on a changed item counts as missing`() {
        noSupplyRun(
            page(
                1,
                true,
                item(1, extra = mapOf("entityRef" to "E-3", "supplyTypes" to "STANDARD_RATED")) - "supplyType",
            ),
        ).refusedNaming(1, "E-3")
    }

    @Test
    fun `INV-AC-20340 an empty string supplyType on a changed item counts as missing`() {
        noSupplyRun(
            page(1, true, item(1, extra = mapOf("entityRef" to "E-4", "supplyType" to ""))),
        ).refusedNaming(1, "E-4")
    }
}
