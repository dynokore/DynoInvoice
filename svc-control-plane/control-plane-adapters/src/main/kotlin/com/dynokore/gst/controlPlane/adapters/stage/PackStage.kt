package com.dynokore.gst.controlPlane.adapters.stage

import com.dynokore.gst.controlPlane.application.pack.PackContentHasher
import com.dynokore.gst.controlPlane.application.pack.PackRepository
import com.dynokore.gst.controlPlane.domain.pack.ContentHash
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
import org.jooq.DSLContext

/** One submission the replay of a period produced with the pack (a document payload, opaque to the stage). */
data class StagedSubmission(
    val documentRef: String,
    val payload: String,
)

/** The replay of a full period in the sandbox with the pack (INV-REQ-2662 b1). */
data class PeriodReplay(
    val periodRef: String,
    val submissions: List<StagedSubmission>,
)

/**
 * The pipeline interface the stage replays a period through. It is bound by the deployment (the pipeline of the Wave 3
 * exit); [UnboundPackPipeline] is the default, which fails closed.
 */
fun interface PackPipeline {
    /** Throws [PipelineUnbound] when no pipeline is bound; any other exception is a replay error. */
    fun replay(pack: RegulatoryPack): PeriodReplay
}

/** No pipeline is bound: nothing is staged and no record is written. */
class PipelineUnbound : RuntimeException("no pipeline binding for pack staging", null, false, false)

object UnboundPackPipeline : PackPipeline {
    override fun replay(pack: RegulatoryPack): PeriodReplay = throw PipelineUnbound()
}

/** The verdict of the IRAS sandbox, or of its simulator until a train has the real integration (INV-REQ-2662 b1). */
sealed interface SandboxVerdict {
    data object Accepted : SandboxVerdict

    data class Rejected(
        val reason: String,
    ) : SandboxVerdict
}

interface SandboxSubmitter {
    /** `IRAS_SANDBOX` or `SIMULATOR`: where the submissions go (the `target` of the staging record). */
    val target: String

    fun submit(submission: StagedSubmission): SandboxVerdict
}

/** What one stage run did. [recorded] is false when nothing was written (stage unbound, or a pack not signed). */
data class StageOutcome(
    val packRef: PackRef,
    val result: StagingResult?,
    val recorded: Boolean,
    val detail: String,
)

/**
 * The stage of a regulatory pack (INV-REQ-2662, coordinator ruling INV-Q-4878): replays a full period with the signed
 * pack through the [PackPipeline], submits the result to the [SandboxSubmitter] and records the sandbox acceptance
 * evidence with the hash of the exact content it staged. It is the only writer of a staging record, through
 * [StagingRecordWriter]; it writes PASSED only when the replay ran and every submission was accepted. With no pipeline
 * bound it writes nothing, so `publishPack` is refused. It is not an HTTP operation.
 */
class PackStage(
    dsl: DSLContext,
    private val packs: PackRepository,
    private val hasher: PackContentHasher,
    private val pipeline: PackPipeline,
    private val sandbox: SandboxSubmitter,
) {
    private val writer = StagingRecordWriter(dsl)

    fun run(ref: PackRef): StageOutcome {
        val pack = packs.find(ref)
        return when {
            pack == null -> StageOutcome(ref, null, false, "no such pack version")
            pack.status != PackStatus.SIGNED -> StageOutcome(ref, null, false, "the version is not signed")
            else -> stage(pack)
        }
    }

    private fun stage(pack: RegulatoryPack): StageOutcome {
        // the hash of the content that is staged: read before the replay, so a change after it is a mismatch
        val hash = hasher.hash(pack)
        return try {
            submit(pack, hash, pipeline.replay(pack))
        } catch (unbound: PipelineUnbound) {
            StageOutcome(pack.ref, null, false, unbound.message.orEmpty())
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            record(pack, hash, StagingResult.FAILED, null, "replay error: ${error.javaClass.simpleName}")
        }
    }

    private fun submit(
        pack: RegulatoryPack,
        hash: ContentHash,
        replay: PeriodReplay,
    ): StageOutcome {
        val rejected =
            replay.submissions
                .map {
                    it to
                        sandbox.submit(
                            it,
                        )
                }.filter { it.second is SandboxVerdict.Rejected }
        val (result, detail) =
            when {
                replay.submissions.isEmpty() -> {
                    StagingResult.FAILED to "the replay produced no submission"
                }

                rejected.isNotEmpty() -> {
                    StagingResult.FAILED to "sandbox rejected ${rejected.joinToString { it.first.documentRef }}"
                }

                else -> {
                    StagingResult.PASSED to "all ${replay.submissions.size} submissions accepted"
                }
            }
        return record(pack, hash, result, replay.periodRef, detail)
    }

    private fun record(
        pack: RegulatoryPack,
        hash: ContentHash,
        result: StagingResult,
        periodRef: String?,
        detail: String,
    ): StageOutcome {
        writer.record(
            StagingOutcome(
                pack.ref,
                result,
                hash,
                sandbox.target,
                "evidence/staging/${pack.ref.value}",
                periodRef,
                RECORDED_BY,
            ),
        )
        return StageOutcome(pack.ref, result, true, detail)
    }

    private companion object {
        const val RECORDED_BY = "stage"
    }
}
