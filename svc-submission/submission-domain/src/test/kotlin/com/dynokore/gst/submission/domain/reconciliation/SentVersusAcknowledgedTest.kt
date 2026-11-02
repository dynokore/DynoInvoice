package com.dynokore.gst.submission.domain.reconciliation

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.DEAD_LETTERED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.NOT_SUBMITTABLE
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SUPERSEDED
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

class SentVersusAcknowledgedTest {
    private val now = Instant.parse("2026-11-02T10:00:00Z")
    private val past = now.minusSeconds(3600)
    private val future = now.plusSeconds(3600)

    private fun v(
        n: Int,
        s: LifecycleState,
        d: Instant? = null,
    ) = VersionFact(n, s, d)

    private fun doc(
        id: String,
        amount: String,
        vararg vs: VersionFact,
    ) = DocFact(id, vs.toList(), BigDecimal(amount))

    private fun single(
        s: LifecycleState,
        d: Instant? = null,
    ) = doc("d", "1.00", v(1, s, d))

    private val criterionDocs =
        listOf(
            doc("a", "1.00", v(1, ACKNOWLEDGED), v(2, QUEUED)),
            doc("b", "2.00", v(1, SENT, past)),
            doc("c", "4.00", v(1, SENT, future)),
            doc("d", "8.00", v(1, REJECTED)),
            doc("e", "16.00", v(1, DEAD_LETTERED)),
            doc("f", "32.00", v(1, QUEUED)),
            doc("g", "64.00", v(1, NOT_SUBMITTABLE)),
        )

    @Test
    fun `INV-AC-16293 the seven documents of the criterion get their classes in input order`() {
        criterionDocs.map { SentVersusAcknowledged.bucketOf(it, now) } shouldBe
            listOf(
                ReconciliationBucket.SENT_ACKNOWLEDGED,
                ReconciliationBucket.SENT_NOT_ACKNOWLEDGED,
                null,
                ReconciliationBucket.REJECTED,
                ReconciliationBucket.DEAD_LETTERED,
                ReconciliationBucket.NEVER_SENT,
                null,
            )
    }

    @Test
    fun `INV-AC-16293 the view of the criterion documents counts one each with their amounts`() {
        val view = SentVersusAcknowledged.view(criterionDocs, now)
        view.map { it.bucket } shouldBe ReconciliationBucket.entries
        view.map { it.count } shouldBe listOf(1, 1, 1, 1, 1)
        view.map { it.totalAmount } shouldBe listOf("1.00", "2.00", "8.00", "16.00", "32.00").map { BigDecimal(it) }
    }

    @Test
    fun `INV-AC-16293 a deadline equal to now is no class and one second earlier is not acknowledged`() {
        SentVersusAcknowledged.bucketOf(single(SENT, now), now) shouldBe null
        SentVersusAcknowledged.bucketOf(single(SENT, now.minusSeconds(1)), now) shouldBe
            ReconciliationBucket.SENT_NOT_ACKNOWLEDGED
    }

    @Test
    fun `INV-AC-16293 a SENT document with a null deadline is in no class`() {
        SentVersusAcknowledged.bucketOf(single(SENT, null), now) shouldBe null
    }

    @Test
    fun `INV-AC-16293 an acknowledged version wins over a later rejected dead-lettered or superseded one`() {
        listOf(REJECTED, DEAD_LETTERED, SUPERSEDED).forEach { later ->
            SentVersusAcknowledged.bucketOf(doc("x", "1.00", v(1, ACKNOWLEDGED), v(2, later)), now) shouldBe
                ReconciliationBucket.SENT_ACKNOWLEDGED
        }
    }

    @Test
    fun `INV-AC-16293 REJECTED alone is REJECTED`() {
        SentVersusAcknowledged.bucketOf(single(REJECTED), now) shouldBe ReconciliationBucket.REJECTED
    }

    @Test
    fun `INV-AC-16293 every one of the 18 states as the only version gives the answer of the rule`() {
        val expected =
            mapOf(
                LifecycleState.SENT to ReconciliationBucket.SENT_NOT_ACKNOWLEDGED,
                LifecycleState.SENT_UNCONFIRMED to ReconciliationBucket.SENT_NOT_ACKNOWLEDGED,
                ACKNOWLEDGED to ReconciliationBucket.SENT_ACKNOWLEDGED,
                REJECTED to ReconciliationBucket.REJECTED,
                DEAD_LETTERED to ReconciliationBucket.DEAD_LETTERED,
                LifecycleState.INGESTED to ReconciliationBucket.NEVER_SENT,
                LifecycleState.HELD to ReconciliationBucket.NEVER_SENT,
                LifecycleState.BLOCKED to ReconciliationBucket.NEVER_SENT,
                LifecycleState.READY to ReconciliationBucket.NEVER_SENT,
                LifecycleState.AWAITING_PRECEDING to ReconciliationBucket.NEVER_SENT,
                QUEUED to ReconciliationBucket.NEVER_SENT,
                LifecycleState.RETRY to ReconciliationBucket.NEVER_SENT,
            )
        LifecycleState.entries.size shouldBe 18
        LifecycleState.entries.forEach { s ->
            SentVersusAcknowledged.bucketOf(single(s, past), now) shouldBe expected[s]
        }
    }

    @Test
    fun `INV-AC-16293 an empty input gives five buckets with count 0 and amount 0 00`() {
        val view = SentVersusAcknowledged.view(emptyList(), now)
        view.size shouldBe 5
        view.forEach {
            it.count shouldBe 0
            it.totalAmount shouldBe BigDecimal("0.00")
            it.totalAmount.scale() shouldBe 2
        }
    }

    @Test
    fun `INV-AC-16293 amounts 10 10 and 0 20 sum to exactly 10 30`() {
        val docs = listOf(doc("a", "10.10", v(1, REJECTED)), doc("b", "0.20", v(1, REJECTED)))
        val rejected = SentVersusAcknowledged.view(docs, now)[2]
        rejected.totalAmount shouldBe BigDecimal("10.30")
    }

    @Test
    fun `INV-AC-16293 a total beyond double precision stays exact`() {
        val docs = listOf(doc("a", "9007199254740993.01", v(1, REJECTED)), doc("b", "0.01", v(1, REJECTED)))
        SentVersusAcknowledged.view(docs, now)[2].totalAmount shouldBe BigDecimal("9007199254740993.02")
    }

    @Test
    fun `INV-AC-16293 the same call twice gives equal results`() {
        SentVersusAcknowledged.view(criterionDocs, now) shouldBe SentVersusAcknowledged.view(criterionDocs, now)
    }

    @Test
    fun `INV-AC-16293 a repeated docUuid an empty versions and an amount of scale 3 throw`() {
        assertThrows<IllegalArgumentException> {
            SentVersusAcknowledged.view(listOf(doc("a", "1.00", v(1, REJECTED)), doc("a", "2.00", v(1, REJECTED))), now)
        }
        assertThrows<IllegalArgumentException> { DocFact("a", emptyList(), BigDecimal("1.00")) }
        assertThrows<IllegalArgumentException> { doc("a", "1.001", v(1, REJECTED)) }
        assertThrows<IllegalArgumentException> { doc("a", "1.00", v(2, REJECTED), v(2, QUEUED)) }
        assertThrows<IllegalArgumentException> { doc(" ", "1.00", v(1, REJECTED)) }
        assertThrows<IllegalArgumentException> { VersionFact(0, REJECTED, null) }
    }

    @Test
    fun `INV-AC-16293 fromKey returns each member for its exact name and throws otherwise`() {
        ReconciliationBucket.entries.forEach { ReconciliationBucket.fromKey(it.name) shouldBe it }
        assertThrows<IllegalArgumentException> { ReconciliationBucket.fromKey("sent_acknowledged") }
        assertThrows<IllegalArgumentException> { ReconciliationBucket.fromKey("") }
    }
}
