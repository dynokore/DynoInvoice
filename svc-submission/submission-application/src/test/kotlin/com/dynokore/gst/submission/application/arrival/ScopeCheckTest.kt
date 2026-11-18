package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.application.queue.CatalogueEntry
import com.dynokore.gst.submission.application.queue.ErrorCatalogue
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.scope.NotSubmittableReason
import com.dynokore.gst.submission.domain.scope.ScopeVerdict
import com.dynokore.gst.submission.domain.scope.Verdict
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The scope check over fakes (INV-AC-24504). */
class ScopeCheckTest {
    private val t1 = TenantId(UUID.randomUUID())
    private val entries = mutableMapOf<Pair<TenantId, String>, ScopeEntry>()
    private val states = mutableMapOf<Triple<TenantId, String, Int>, String>()
    private val blockCalls = mutableListOf<Triple<TenantId, String, Int>>()
    private val appended = mutableListOf<RaisedCode>()
    private val order = mutableListOf<String>()

    private val facts =
        object : ScopeFacts {
            override fun of(
                tenant: TenantId,
                docUuid: String,
            ): ScopeEntry? = entries[tenant to docUuid]
        }
    private val blocks =
        object : ScopeBlocks {
            override fun blockReady(
                tenant: TenantId,
                docUuid: String,
                version: Int,
            ): Int {
                blockCalls += Triple(tenant, docUuid, version)
                order += "block"
                val key = Triple(tenant, docUuid, version)
                if (states[key] != "READY") return 0
                states[key] = "BLOCKED"
                return 1
            }
        }
    private val codes =
        object : RaisedCodes {
            override fun append(
                tenant: TenantId,
                raised: RaisedCode,
                raisedAt: Instant,
            ): Boolean {
                order += "raise"
                appended += raised
                return true
            }
        }
    private val signals =
        object : CodeSignals {
            override fun stage(raised: RaisedCode) = Unit
        }
    private val catalogue =
        ErrorCatalogue {
            if (it ==
                "SUB-013"
            ) {
                CatalogueEntry(it, "Tax operations", "MANUAL")
            } else {
                null
            }
        }
    private val sut =
        ScopeCheck(
            facts,
            blocks,
            CodeRaising(catalogue, codes, signals, Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"), ZoneOffset.UTC)),
        )

    private val sub = Verdict(ScopeVerdict.SUBMITTABLE)
    private val notSub = Verdict(ScopeVerdict.NOT_SUBMITTABLE)

    private fun put(
        doc: String,
        kind: RowKind,
        verdict: Verdict?,
        preceding: String? = null,
    ) {
        entries[t1 to doc] = ScopeEntry("E-001", "2026-Q1", kind, verdict, preceding)
        if (kind == RowKind.CREDIT_NOTE) states[Triple(t1, doc, 1)] = "READY"
    }

    private fun pair(
        doc: String,
        note: Verdict?,
        pre: Verdict?,
        kind: RowKind = RowKind.CREDIT_NOTE,
    ) {
        put("P-$doc", RowKind.INVOICE, pre)
        put(doc, kind, note, "P-$doc")
        if (kind == RowKind.INVOICE) states[Triple(t1, doc, 1)] = "READY"
    }

    private fun proceeds(doc: String) {
        sut.check(t1, doc, 1) shouldBe ScopeOutcome.Proceed
        blockCalls shouldBe emptyList()
        appended shouldBe emptyList()
        states[Triple(t1, doc, 1)] shouldBe "READY"
    }

    private fun blocked(doc: String) {
        sut.check(t1, doc, 1) shouldBe ScopeOutcome.Blocked
        blockCalls shouldBe listOf(Triple(t1, doc, 1))
        states[Triple(t1, doc, 1)] shouldBe "BLOCKED"
        appended.map { it.code } shouldBe listOf("SUB-013")
        appended.map { it.docUuid to it.version } shouldBe listOf(doc to 1)
        appended.map { it.entityRef to it.periodRef } shouldBe listOf("E-001" to "2026-Q1")
    }

    @Test
    fun `INV-AC-24504 C1 submittable credit note over a not submittable original is blocked`() {
        pair("C1", sub, notSub)
        blocked("C1")
    }

    @Test
    fun `INV-AC-24504 C5 the reverse pair is blocked`() {
        pair("C5", notSub, sub)
        blocked("C5")
    }

    @Test
    fun `INV-AC-24504 C2 agreeing pair proceeds`() {
        pair("C2", sub, sub)
        proceeds("C2")
    }

    @Test
    fun `INV-AC-24504 C3 a reason on the original is not compared`() {
        pair("C3", sub, Verdict(ScopeVerdict.NOT_SUBMITTABLE, NotSubmittableReason.BUYER_REJECTED))
        proceeds("C3")
    }

    @Test
    fun `INV-AC-24504 C4 pre obligation original is not compared`() {
        pair("C4", sub, Verdict(ScopeVerdict.PRE_OBLIGATION))
        proceeds("C4")
    }

    @Test
    fun `INV-AC-24504 C6 a preceding document with no entry proceeds`() {
        put("C6", RowKind.CREDIT_NOTE, sub, "GONE")
        proceeds("C6")
    }

    @Test
    fun `INV-AC-24504 C6 a preceding entry with a null verdict proceeds`() {
        pair("C6b", sub, null)
        proceeds("C6b")
    }

    @Test
    fun `INV-AC-24504 C7 a null scope verdict proceeds`() {
        pair("C7", null, notSub)
        proceeds("C7")
    }

    @Test
    fun `INV-AC-24504 C8 no preceding document proceeds`() {
        put("C8", RowKind.CREDIT_NOTE, sub, null)
        proceeds("C8")
    }

    @Test
    fun `INV-AC-24504 I1 an invoice with the pair of C1 proceeds`() {
        pair("I1", sub, notSub, RowKind.INVOICE)
        proceeds("I1")
    }

    @Test
    fun `INV-AC-24504 an unknown document proceeds`() {
        sut.check(t1, "none", 1) shouldBe ScopeOutcome.Proceed
        blockCalls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24504 the entry of another tenant is not read`() {
        pair("C1", sub, notSub)
        val t2 = TenantId(UUID.randomUUID())
        sut.check(t2, "C1", 1) shouldBe ScopeOutcome.Proceed
        blockCalls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24504 a version that is not READY throws and raises nothing`() {
        pair("C1", sub, notSub)
        states[Triple(t1, "C1", 1)] = "QUEUED"
        val ex = shouldThrow<IllegalStateException> { sut.check(t1, "C1", 1) }
        ex.message!! shouldContain "C1"
        ex.message!! shouldContain "version 1"
        appended shouldBe emptyList()
        states[Triple(t1, "C1", 1)] shouldBe "QUEUED"
    }

    @Test
    fun `INV-AC-24504 a second check of the blocked version throws and never raises twice`() {
        pair("C1", sub, notSub)
        blocked("C1")
        shouldThrow<IllegalStateException> { sut.check(t1, "C1", 1) }
        appended.size shouldBe 1
    }

    @Test
    fun `INV-AC-24504 the state change comes before the raise`() {
        pair("C1", sub, notSub)
        sut.check(t1, "C1", 1)
        order shouldBe listOf("block", "raise")
    }
}
