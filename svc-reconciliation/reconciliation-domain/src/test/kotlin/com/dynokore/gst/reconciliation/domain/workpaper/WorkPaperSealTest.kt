package com.dynokore.gst.reconciliation.domain.workpaper

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class WorkPaperSealTest {
    private val json = """{"runId":"R1","header":{"entityRef":"E1"}}""".toByteArray()

    @Test
    fun `INV-AC-24173 the seal hash is the lowercase 64 character SHA-256 of exactly the bytes`() {
        val seal = WorkPaperSeal.of("abc".toByteArray())
        seal.sha256Hex shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        seal.sha256Hex shouldMatch Regex("[0-9a-f]{64}")
        seal.bytes shouldBe "abc".toByteArray()
    }

    @Test
    fun `INV-AC-24173 equal bytes give an equal seal and different bytes a different hash`() {
        WorkPaperSeal.of(json) shouldBe WorkPaperSeal.of(json.copyOf())
        (WorkPaperSeal.of(json).sha256Hex == WorkPaperSeal.of(json + 32).sha256Hex) shouldBe false
    }

    @Test
    fun `INV-AC-24173 the reference is reconciliation tenant run and seal version`() {
        WorkPaperReference.of("T1", "R1", 2) shouldBe "reconciliation/T1/R1/v2"
    }

    @Test
    fun `INV-AC-24173 the seal does not alias the caller bytes`() {
        val input = "abc".toByteArray()
        val seal = WorkPaperSeal.of(input)
        input[0] = 'x'.code.toByte()
        seal.bytes shouldBe "abc".toByteArray()
    }
}
