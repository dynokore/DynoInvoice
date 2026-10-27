package com.dynokore.gst.submission.domain.batching

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class PackageBatcherTest {
    private val batcher = PackageBatcher(BatchLimits(10, 10_000_000))

    private fun cand(
        id: String,
        type: String = "380",
        size: Long = 1_000_000,
        version: Int = 1,
    ) = BatchCandidate(id, version, type, size)

    private fun many(
        n: Int,
        type: String = "380",
        size: Long = 1_000_000,
        prefix: String = "d",
    ) = (1..n).map { cand("$prefix$type-$it", type, size) }

    private fun counts(r: BatchResult) = r.packages.map { it.members.size }

    @Test
    fun `INV-AC-450 25 invoices and 8 credit notes give packages of 10, 10, 5 and 8 none mixed`() {
        val r = batcher.batch(many(25, "380") + many(8, "381"))
        counts(r) shouldBe listOf(10, 10, 5, 8)
        r.packages.map { it.documentTypeCode } shouldBe listOf("380", "380", "380", "381")
        r.packages.forEach { p ->
            p.members.all { it.documentTypeCode == p.documentTypeCode } shouldBe true
            (p.members.sumOf { it.sizeBytes } <= 10_000_000) shouldBe true
        }
        r.refused shouldBe emptyList()
    }

    @Test
    fun `INV-AC-1576 ten documents of 1200000 bytes give two packages of 8 and 2`() {
        counts(batcher.batch(many(10, size = 1_200_000))) shouldBe listOf(8, 2)
    }

    @Test
    fun `INV-AC-451 a candidate above the limit is refused as Oversized and the others are packed`() {
        val big = cand("big", size = 10_000_001, version = 3)
        val r = batcher.batch(listOf(cand("a"), cand("b"), big, cand("c")))
        r.refused shouldBe listOf(Oversized("big", 3, 10_000_001))
        counts(r) shouldBe listOf(3)
        r.packages.flatMap { it.members }.contains(big) shouldBe false
    }

    @Test
    fun `INV-AC-451 a candidate of exactly the limit is packed alone`() {
        val r = batcher.batch(listOf(cand("a", size = 10_000_000), cand("b")))
        r.refused shouldBe emptyList()
        counts(r) shouldBe listOf(1, 1)
        r.packages[0].members.map { it.docUuid } shouldBe listOf("a")
    }

    @Test
    fun `INV-AC-1688 two versions of one docUuid land in two different packages`() {
        val r = batcher.batch(listOf(cand("u"), cand("u", version = 2), cand("x"), cand("y"), cand("z")))
        r.packages.size shouldBe 2
        r.packages.forEach { p ->
            p.members
                .map { it.docUuid }
                .distinct()
                .size shouldBe p.members.size
        }
        r.packages.sumOf { it.members.size } shouldBe 5
        r.packages[0].members.map { it.version } shouldBe listOf(1)
    }

    @Test
    fun `INV-AC-1689 resplit of a package of four gives four packages of one in order`() {
        val members = many(4)
        val parts = batcher.resplit(DocumentPackage("380", members))
        parts.map { it.members } shouldBe members.map { listOf(it) }
        parts.map { it.documentTypeCode } shouldBe List(4) { "380" }
    }

    @Test
    fun `exactly 10 candidates give one package and 11 give two`() {
        counts(batcher.batch(many(10, size = 1))) shouldBe listOf(10)
        counts(batcher.batch(many(11, size = 1))) shouldBe listOf(10, 1)
    }

    @Test
    fun `a total of exactly 10000000 bytes gives one package and 10000001 gives two`() {
        counts(batcher.batch(listOf(cand("a", size = 5_000_000), cand("b", size = 5_000_000)))) shouldBe listOf(2)
        counts(batcher.batch(listOf(cand("a", size = 5_000_000), cand("b", size = 5_000_001)))) shouldBe listOf(1, 1)
    }

    @Test
    fun `maxDocuments 3 gives packages of at most 3`() {
        counts(PackageBatcher(BatchLimits(3, 10_000_000)).batch(many(7, size = 1))) shouldBe listOf(3, 3, 1)
    }

    @Test
    fun `interleaved types give one group per code in order of first appearance`() {
        val r = batcher.batch(listOf(cand("a", "381"), cand("b", "380"), cand("c", "381"), cand("d", "380")))
        r.packages.map { it.documentTypeCode } shouldBe listOf("381", "380")
        counts(r) shouldBe listOf(2, 2)
    }

    @Test
    fun `configuration must be positive`() {
        runCatching { BatchLimits(0, 1) }.isFailure shouldBe true
        runCatching { BatchLimits(1, 0) }.isFailure shouldBe true
    }

    @Test
    fun `the same input run from 8 threads gives eight equal results`() {
        val input = many(25, "380") + many(8, "381") + cand("d380-1", version = 2)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(List(8) { Callable { batcher.batch(input) } }).map { it.get() }
            results.distinct().size shouldBe 1
            results[0] shouldBe batcher.batch(input)
        } finally {
            pool.shutdown()
        }
    }
}
