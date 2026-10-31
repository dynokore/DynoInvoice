package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.persistence.StagingEvidenceReaderJooq
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_VERSION
import com.dynokore.gst.controlPlane.adapters.stage.PackStage
import com.dynokore.gst.controlPlane.adapters.stage.PackStageRun
import com.dynokore.gst.controlPlane.adapters.stage.StageCandidates
import com.dynokore.gst.controlPlane.adapters.stage.StagingOutcome
import com.dynokore.gst.controlPlane.adapters.stage.StagingRecordWriter
import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The stage job of INV-T-4985 (coordinator ruling INV-Q-8725): which versions it selects and what a run does. The job
 * body is [PackStageRun], the only caller of [PackStage.run].
 */
class StageJobIT {
    private fun candidates(flow: PackReleaseFlow) =
        StageCandidates(flow.dslContext, flow.packs, flow.bundles.hasher(), StagingEvidenceReaderJooq(flow.dslContext))

    private fun signed(
        flow: PackReleaseFlow,
        version: String = "2026.3",
    ): PackRef {
        val ref = flow.author(version)
        flow.sign(ref) shouldBe null
        return ref
    }

    private fun write(
        flow: PackReleaseFlow,
        ref: PackRef,
        result: StagingResult,
        hashOf: PackRef = ref,
    ) {
        StagingRecordWriter(flow.dslContext).record(
            StagingOutcome(
                ref,
                result,
                flow.bundles.hasher().hash(checkNotNull(flow.packs.find(hashOf))),
                "SIMULATOR",
                null,
                null,
                "probe",
            ),
        )
    }

    @Test
    fun `a signed version with no record is selected`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        candidates(flow).select() shouldContain ref
    }

    @Test
    fun `the job run with the stage unbound writes no record and publishPack stays refused`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        val run = PackStageRun(candidates(flow), flow.unboundStage())
        run
            .runOnce()
            .single { it.packRef == ref }
            .recorded shouldBe false
        StagingEvidenceReaderJooq(flow.dslContext).forPack(ref) shouldBe emptyList()
        Refusal.parse(flow.publish(ref)!!).code shouldBe Refusal.NO_STAGING_RECORD
        candidates(flow).select() shouldContain ref
    }

    @Test
    fun `a bound stage run records PASSED and the version is then not selected`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        PackStageRun(candidates(flow), flow.stage)
            .runOnce()
            .single { it.packRef == ref }
            .result shouldBe
            StagingResult.PASSED
        candidates(flow).select() shouldNotContain ref
    }

    @Test
    fun `a PENDING record is selected again`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        write(flow, ref, StagingResult.PENDING)
        candidates(flow).select() shouldContain ref
    }

    @Test
    fun `a version whose record has the current content hash and PASSED is not selected`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        write(flow, ref, StagingResult.PASSED)
        candidates(flow).select() shouldNotContain ref
    }

    @Test
    fun `a version whose PASSED record has another content hash is selected again`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        flow.stage.run(ref).result shouldBe StagingResult.PASSED
        flow.dslContext
            .update(PACK_VERSION)
            .set(PACK_VERSION.CONTENTS, """{"rules":[{"code":"SR","rate":10}]}""")
            .where(PACK_VERSION.PACK_REF.eq(ref.value))
            .execute()
        candidates(flow).select() shouldContain ref
    }

    @Test
    fun `a look-alike record of another version with the same hash is not counted`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow, "2026.3")
        val other = signed(flow, "2026.30")
        // the record is the other version's, carrying the hash of this one: it is not this version's record
        write(flow, other, StagingResult.PASSED, hashOf = ref)
        val selected = candidates(flow).select()
        selected shouldContain ref
        selected shouldContain other
    }
}
