package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.persistence.StagingEvidenceReaderJooq
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_VERSION
import com.dynokore.gst.controlPlane.adapters.stage.PipelineUnbound
import com.dynokore.gst.controlPlane.adapters.stage.StagedSubmission
import com.dynokore.gst.controlPlane.adapters.stage.UnboundPackPipeline
import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
import com.dynokore.gst.controlPlane.domain.release.Gate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * The stage of INV-T-4985 over the real tables (coordinator ruling INV-Q-4878): the stage is the only writer of a
 * PASSED record, and `publishPack` publishes only over a PASSED record with the hash of the content it publishes.
 */
class StageIT {
    private fun signed(flow: PackReleaseFlow): PackRef {
        val ref = flow.author("2026.3")
        flow.sign(ref) shouldBe null
        return ref
    }

    private fun records(
        flow: PackReleaseFlow,
        ref: PackRef,
    ) = StagingEvidenceReaderJooq(flow.dslContext).forPack(ref)

    @Test
    fun `the stage records PASSED over the hash of the staged content and publishPack then publishes`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        val outcome = flow.stage.run(ref)
        outcome.result shouldBe StagingResult.PASSED
        val record = records(flow, ref).single()
        record.result shouldBe StagingResult.PASSED
        record.packContentHash shouldBe flow.bundles.hasher().hash(checkNotNull(flow.packs.find(ref)))
        flow.publish(ref) shouldBe null
        flow.packs.find(ref)!!.status shouldBe PackStatus.PUBLISHED
        flow.records
            .find(ref)!!
            .runs
            .map { it.gate } shouldBe listOf(Gate.CORPUS, Gate.STAGING)
    }

    @Test
    fun `INV-AC-2663 a pack the sandbox rejects has a FAILED record and publishPack is refused`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        flow.pipeline.rejected = true
        flow.stage.run(ref).result shouldBe StagingResult.FAILED
        records(flow, ref).single().result shouldBe StagingResult.FAILED
        Refusal.parse(flow.publish(ref)!!).code shouldBe Refusal.STAGING_NOT_PASSED
        flow.packs.find(ref)!!.status shouldBe PackStatus.SIGNED
    }

    @Test
    fun `a replay error records FAILED and publishPack is refused`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        flow.pipeline.failure = IllegalStateException("replay broke")
        flow.stage.run(ref).result shouldBe StagingResult.FAILED
        Refusal.parse(flow.publish(ref)!!).code shouldBe Refusal.STAGING_NOT_PASSED
    }

    @Test
    fun `an unbound pipeline writes no record and publishPack is refused`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        shouldThrow<PipelineUnbound> { UnboundPackPipeline.replay(flow.packs.find(ref)!!) }
        flow.unboundStage().run(ref).recorded shouldBe false
        records(flow, ref) shouldBe emptyList()
        Refusal.parse(flow.publish(ref)!!).code shouldBe Refusal.NO_STAGING_RECORD
    }

    @Test
    fun `a record of an earlier version of the same pack does not let a later version publish`() {
        val flow = PackReleaseFlow()
        val earlier = flow.author("2026.2")
        flow.sign(earlier) shouldBe null
        flow.stage.run(earlier).result shouldBe StagingResult.PASSED
        val later = signed(flow)
        Refusal.parse(flow.publish(later)!!).code shouldBe Refusal.NO_STAGING_RECORD
    }

    @Test
    fun `the content changed after the stage passed, same version number, is refused with a hash mismatch`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        flow.stage.run(ref).result shouldBe StagingResult.PASSED
        flow.dslContext
            .update(PACK_VERSION)
            .set(PACK_VERSION.CONTENTS, """{"rules":[{"code":"SR","rate":10}]}""")
            .where(PACK_VERSION.PACK_REF.eq(ref.value))
            .execute()
        Refusal.parse(flow.publish(ref)!!).code shouldBe Refusal.STAGING_HASH_MISMATCH
    }

    @Test
    fun `the stage stages the hash it read before the replay, so content changed during it is a mismatch`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        flow.pipeline.during = {
            flow.dslContext
                .update(PACK_VERSION)
                .set(PACK_VERSION.CONTENTS, """{"rules":[{"code":"SR","rate":11}]}""")
                .where(PACK_VERSION.PACK_REF.eq(ref.value))
                .execute()
        }
        flow.stage.run(ref).result shouldBe StagingResult.PASSED
        Refusal.parse(flow.publish(ref)!!).code shouldBe Refusal.STAGING_HASH_MISMATCH
    }

    @Test
    fun `after the real stage ran, a direct INSERT of a PASSED row by the runtime login is still refused`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        val other = flow.author("2026.4")
        flow.stage.run(ref).result shouldBe StagingResult.PASSED
        val error =
            shouldThrow<Exception> {
                PostgresDb.probe.unbound {
                    fetchValue(
                        "insert into controlplane.pack_staging_record " +
                            "(pack_ref, result, pack_content_hash, recorded_by) values (?, 'PASSED', ?, 'runtime')",
                        other.value,
                        "0".repeat(64),
                    )
                }
            }
        generateSequence<Throwable>(error) { it.cause }.filterIsInstance<SQLException>().first().sqlState shouldBe
            "42501"
        records(flow, other) shouldBe emptyList()
    }

    @Test
    fun `the submissions the stage sends are those of the replay, one verdict each`() {
        val flow = PackReleaseFlow()
        val ref = signed(flow)
        flow.stage.run(ref).detail shouldBe "all 2 submissions accepted"
        flow.pipeline.submissions = listOf(StagedSubmission("DOC-9", "REJECT"))
        flow.stage.run(ref).detail shouldBe "sandbox rejected DOC-9"
    }
}
