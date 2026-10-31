package com.dynokore.gst.controlPlane.domain.release

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReleaseGatesTest {
    private val all = Gate.entries.toSet()

    private fun passed(gate: Gate) = GateRun(gate, GateOutcome.PASSED)

    private fun everyGatePassing() = all.map(::passed)

    @Test
    fun `every required gate passing releases`() {
        ReleaseGates.evaluate(all, everyGatePassing(), exposureReducing = false).releasable shouldBe true
    }

    @Test
    fun `a required gate with no run holds the release back, and so does a duplicated run`() {
        val verdict = ReleaseGates.evaluate(all, everyGatePassing().drop(1), exposureReducing = true)
        verdict.blocks.map { it.reason } shouldBe listOf(GateBlock.Reason.NO_RUN)
        val twice = everyGatePassing() + passed(Gate.SECURITY)
        ReleaseGates.evaluate(all, twice, exposureReducing = false).blocks.map { it.gate } shouldBe
            listOf(Gate.SECURITY)
    }

    @Test
    fun `a run of a gate that is not required is no run of a required one`() {
        val verdict = ReleaseGates.evaluate(setOf(Gate.CORPUS), listOf(passed(Gate.STAGING)), exposureReducing = false)
        verdict.blocks.map { it.gate } shouldBe listOf(Gate.CORPUS)
    }

    @Test
    fun `a failed gate holds a release back unless it only reduces exposure and the failure is inherited`() {
        val inherited = GateRun(Gate.SECURITY, GateOutcome.FAILED, setOf("F1"), GateOutcome.FAILED, setOf("F1"))
        val runs = everyGatePassing().filter { it.gate != Gate.SECURITY } + inherited
        ReleaseGates.evaluate(all, runs, exposureReducing = false).releasable shouldBe false
        ReleaseGates.evaluate(all, runs, exposureReducing = true).releasable shouldBe true
    }

    @Test
    fun `an introduced finding blocks an exposure-reducing release and the block names only the new finding`() {
        val added = GateRun(Gate.SECURITY, GateOutcome.FAILED, setOf("F1", "F2"), GateOutcome.FAILED, setOf("F1"))
        val runs = everyGatePassing().filter { it.gate != Gate.SECURITY } + added
        val block = ReleaseGates.evaluate(all, runs, exposureReducing = true).blocks.single()
        block.reason shouldBe GateBlock.Reason.INTRODUCED
        block.introduced shouldBe setOf("F2")
    }

    @Test
    fun `a failure with no re-run against the released version, or one the released version passes, blocks`() {
        val noBaseline = GateRun(Gate.CORPUS, GateOutcome.FAILED, setOf("D1"))
        ReleaseGates
            .evaluate(setOf(Gate.CORPUS), listOf(noBaseline), true)
            .blocks
            .single()
            .reason shouldBe
            GateBlock.Reason.NO_BASELINE
        val regressed = GateRun(Gate.CORPUS, GateOutcome.FAILED, setOf("D1"), GateOutcome.PASSED)
        ReleaseGates
            .evaluate(setOf(Gate.CORPUS), listOf(regressed), true)
            .blocks
            .single()
            .reason shouldBe
            GateBlock.Reason.INTRODUCED
    }

    @Test
    fun `the regeneration check compares document by document`() {
        val before = mapOf(DocumentId("A") to "SR", DocumentId("B") to "ZR")
        RegenerationCheck.differences(before, before.toMap()) shouldBe emptySet()
        RegenerationCheck.differences(before, before + (DocumentId("B") to "SR")) shouldBe setOf(DocumentId("B"))
        RegenerationCheck.differences(before, before - DocumentId("A")) shouldBe setOf(DocumentId("A"))
        RegenerationCheck.run(emptyMap(), emptyMap()) shouldBe setOf(RegenerationCheck.EMPTY_CORPUS)
    }

    @Test
    fun `certification passes only with both reference environments passed`() {
        Certification(mapOf(ReferenceEnvironment.SAAS to GateOutcome.PASSED)).outcome shouldBe GateOutcome.FAILED
        Certification(
            mapOf(
                ReferenceEnvironment.SAAS to GateOutcome.PASSED,
                ReferenceEnvironment.PRIVATE_MINIMAL to GateOutcome.FAILED,
            ),
        ).missing shouldBe setOf("PRIVATE_MINIMAL")
        Certification(ReferenceEnvironment.entries.associateWith { GateOutcome.PASSED }).outcome shouldBe
            GateOutcome.PASSED
    }

    @Test
    fun `the corpus run is complete only with a document in every section`() {
        val docs = CorpusSection.entries.map { GoldenDocument(DocumentId("G-$it"), it, "SR") }
        ConformanceRun.run(docs) { "SR" }.complete shouldBe true
        ConformanceRun.run(docs.drop(1)) { "SR" }.complete shouldBe false
        ConformanceRun.run(docs) { null }.failing.size shouldBe docs.size
    }
}
