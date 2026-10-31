package com.dynokore.gst.controlPlane.application.release

import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateVerdict
import com.dynokore.gst.controlPlane.domain.release.ReleaseGates
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.SupportWindow
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import com.dynokore.platform.serviceruntime.operation.OperationAudit
import com.dynokore.platform.serviceruntime.operation.Reversibility
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock

private const val SCOPE = "platform"

private fun auditContext(
    key: String,
    request: RequestContext,
) = EventContext(
    tenant = TenantId.PLATFORM,
    entity = EntityRef.PLATFORM,
    docUuid = null,
    transactionDate = null,
    aggregateKey = key,
    aggregateVersion = null,
    correlation = CorrelationId(request.correlationId),
    causation = CausationId(request.causationId),
)

/** The gates every release train and patch train runs (INV-REQ-2657 b1). */
val TRAIN_GATES = setOf(Gate.SCENARIOS, Gate.REGENERATION, Gate.SECURITY, Gate.CERTIFICATION)

/** The refusal detail of a verdict: each blocking gate with the findings the candidate introduced. */
fun GateVerdict.describe(): String =
    blocks.joinToString("; ") { block ->
        "${block.gate} ${block.reason}" +
            block.introduced
                .takeIf { it.isNotEmpty() }
                ?.let { " introduced=${it.sorted()}" }
                .orEmpty()
    }

data class PublishTrainCommand(
    val actor: ActorId,
    val version: TrainVersion,
    val kind: TrainKind,
    val exposureReducing: Boolean,
    val needsReAccreditationAssessment: Boolean,
    val request: RequestContext,
)

/**
 * publishTrain (SCR-37.2): a holder of the publish permission publishes a release train or patch train. The internal
 * gates run first (INV-REQ-2657): a train not certified in both reference environments is refused (INV-AC-1943), and
 * so is one with a failed gate, except that an exposure-reducing train is held back only by a failure it
 * introduced. Accreditation currency, an external rule change and an open question are no gate; the re-accreditation assessment is opened as a
 * tracked task with an alert (INV-REQ-1365). The train is distributed by platform-release, never over the pack feed.
 */
class PublishTrain(
    private val trains: TrainRepository,
    private val permissions: OperatorPermissions,
    private val evidence: TrainEvidenceSource,
    private val accreditation: AccreditationTasks,
    private val clock: Clock,
) : DeclaredOperation<PublishTrainCommand, ReleaseTrain?, ReleaseTrain> {
    override val operationId = "publishTrain"
    override val reversibility = Reversibility.IRREVERSIBLE
    override val audited = true

    override fun permits(
        actor: String,
        command: PublishTrainCommand,
    ) = permissions.holds(actor, ReleasePermission.PUBLISH) && actor == command.actor.value

    override fun repeatKey(command: PublishTrainCommand) = command.version.value

    override fun load(command: PublishTrainCommand) = trains.find(command.version)

    override fun priorResult(
        command: PublishTrainCommand,
        state: ReleaseTrain?,
    ) = state

    override fun unmetPrecondition(
        command: PublishTrainCommand,
        state: ReleaseTrain?,
    ): String? {
        val verdict = verdict(command) ?: return Refusal(Refusal.NO_GATE_EVIDENCE, command.version.value).encode()
        return Refusal(Refusal.GATE_FAILED, "${command.version} ${verdict.describe()}").encode().takeUnless {
            verdict.releasable
        }
    }

    override fun perform(
        command: PublishTrainCommand,
        state: ReleaseTrain?,
    ): ReleaseTrain {
        val verdict = checkNotNull(verdict(command)) { "publishTrain performed without gate evidence" }
        check(verdict.releasable) { "publishTrain performed on a train the gates hold back" }
        val check =
            if (command.needsReAccreditationAssessment) {
                accreditation.open(command.version)
            } else {
                AccreditationCheck(false, null, null)
            }
        val train =
            ReleaseTrain(
                command.version,
                command.kind,
                command.exposureReducing,
                ReleaseRecord(verdict.runs, command.exposureReducing, command.actor, clock.instant()),
                check,
            )
        trains.save(train)
        return train
    }

    override fun audit(
        command: PublishTrainCommand,
        state: ReleaseTrain?,
        result: ReleaseTrain,
    ) = OperationAudit(SCOPE, null, null, auditContext(command.version.value, command.request))

    private fun verdict(command: PublishTrainCommand): GateVerdict? =
        evidence.forTrain(command.version)?.let {
            ReleaseGates.evaluate(TRAIN_GATES, it.runs(), command.exposureReducing)
        }
}

data class SetSupportWindowCommand(
    val actor: ActorId,
    val window: SupportWindow,
    val request: RequestContext,
)

/**
 * setSupportWindow (SCR-37.3): a holder of the publish permission records the supported-version window; a repeat
 * returns the original.
 */
class SetSupportWindow(
    private val windows: SupportWindowRepository,
    private val permissions: OperatorPermissions,
) : DeclaredOperation<SetSupportWindowCommand, SupportWindow?, SupportWindow> {
    override val operationId = "setSupportWindow"
    override val reversibility = Reversibility.UNDOABLE
    override val audited = true

    override fun permits(
        actor: String,
        command: SetSupportWindowCommand,
    ) = permissions.holds(actor, ReleasePermission.PUBLISH) && actor == command.actor.value

    override fun repeatKey(command: SetSupportWindowCommand) = command.window.windowVersion

    override fun load(command: SetSupportWindowCommand) = windows.find(command.window.windowVersion)

    override fun priorResult(
        command: SetSupportWindowCommand,
        state: SupportWindow?,
    ) = state

    override fun unmetPrecondition(
        command: SetSupportWindowCommand,
        state: SupportWindow?,
    ): String? = null

    override fun perform(
        command: SetSupportWindowCommand,
        state: SupportWindow?,
    ): SupportWindow = command.window.also(windows::save)

    override fun audit(
        command: SetSupportWindowCommand,
        state: SupportWindow?,
        result: SupportWindow,
    ) = OperationAudit(SCOPE, null, null, auditContext(command.window.windowVersion, command.request))
}
