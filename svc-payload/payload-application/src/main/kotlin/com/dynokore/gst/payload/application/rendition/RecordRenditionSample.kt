package com.dynokore.gst.payload.application.rendition

import com.dynokore.gst.payload.domain.EntityRenditionStanding
import com.dynokore.gst.payload.domain.RenditionWordingChecker
import com.dynokore.gst.payload.domain.SampleCheckResult
import com.dynokore.gst.payload.domain.SampleResult
import com.dynokore.gst.payload.domain.SubmissionType
import com.dynokore.platform.outboxandqueue.EventContext
import java.time.Instant

/** One rendition sample; `checkedBy` is a user ID, never a role (INV-Q-10647). */
data class RenditionSampleCommand(
    val entityRef: String,
    val scenario: String,
    val submissionType: SubmissionType,
    val pdf: ByteArray,
    val sampleSha256: String,
    val checkId: String,
    val checkedAt: Instant,
    val checkedBy: String,
    val context: EventContext,
) {
    init {
        require(checkedBy.isNotBlank()) { "checkedBy must not be blank" }
    }
}

sealed interface RenditionSampleOutcome {
    data class Recorded(
        val result: SampleCheckResult,
        val findingOpen: Boolean,
        val findingChanged: Boolean,
    ) : RenditionSampleOutcome

    data object NotListed : RenditionSampleOutcome
}

/**
 * Records one rendition sample (INV-REQ-1033): checks it, updates the entity standing and emits the SAMPLE_CHECK
 * evidence, all inside one unit of work. The decision logic is pure; side effects sit behind ports.
 */
class RecordRenditionSample(
    private val wording: RenditionWordingSource,
    private val extractor: SampleTextExtractor,
    private val store: RenditionSampleStore,
    private val unitOfWork: RenditionUnitOfWork,
    private val evidence: RecordSampleCheckEvidence,
) {
    fun record(command: RenditionSampleCommand): RenditionSampleOutcome =
        unitOfWork.run {
            val repeat =
                store.findRecorded(command.entityRef, command.sampleSha256, command.scenario, command.submissionType)
            if (repeat != null) {
                val open = store.latest(command.entityRef).values.any { it == SampleResult.FAILED }
                return@run RenditionSampleOutcome.Recorded(repeat, open, false)
            }
            val requirements =
                wording.requirementsFor(command.entityRef, command.scenario, command.submissionType)
                    ?: return@run RenditionSampleOutcome.NotListed
            val check =
                RenditionWordingChecker.check(requirements, command.submissionType, extractor.extract(command.pdf))
            val standing =
                EntityRenditionStanding.record(
                    store.latest(command.entityRef),
                    command.scenario,
                    command.submissionType,
                    check.result,
                )
            store.save(
                command.entityRef,
                command.sampleSha256,
                command.scenario,
                command.submissionType,
                check,
                standing.latest,
            )
            emit(command, check)
            RenditionSampleOutcome.Recorded(check, standing.open, standing.changed)
        }

    private fun emit(
        command: RenditionSampleCommand,
        check: SampleCheckResult,
    ) = evidence.record(
        command.entityRef,
        command.checkId,
        command.scenario,
        command.submissionType,
        check.result,
        check.missingWording,
        check.textFound,
        command.sampleSha256,
        command.checkedAt,
        command.checkedBy,
        command.context,
    )
}
