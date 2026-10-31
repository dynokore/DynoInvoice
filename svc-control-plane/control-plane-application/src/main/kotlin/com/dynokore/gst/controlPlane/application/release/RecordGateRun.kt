package com.dynokore.gst.controlPlane.application.release

import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.GateRunKey
import com.dynokore.gst.controlPlane.domain.release.GateRunRecord
import com.dynokore.gst.controlPlane.domain.release.GateRunRules
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
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

data class RecordGateRunCommand(
    val actor: ActorId,
    val trainVersion: TrainVersion,
    val gate: Gate,
    val environment: ReferenceEnvironment?,
    val candidate: GateOutcome,
    val baseline: GateOutcome?,
    val candidateFindings: List<String>,
    val baselineFindings: List<String>,
    val evidenceRef: String?,
    val request: RequestContext,
)

/** What recordGateRun reads: the record that holds the key, and whether the train is already published. */
data class GateRunState(
    val existing: GateRunRecord?,
    val published: Boolean,
)

/**
 * recordGateRun (/internal/v1/control-plane/release-trains/{trainVersion}/gate-runs): a holder of the certify
 * permission records one gate result of a release train (INV-REQ-2657). The record is append-only and holds the user ID
 * of the caller (INV-Q-10647). A repeat of the same result returns the stored record; a different result for the same
 * key, or any record for a train already published, is refused (409); a result that breaks the contract's rules is
 * refused (422).
 */
class RecordGateRun(
    private val runs: GateRunRepository,
    private val trains: TrainRepository,
    private val permissions: OperatorPermissions,
    private val clock: Clock,
) : DeclaredOperation<RecordGateRunCommand, GateRunState, GateRunRecord> {
    override val operationId = "recordGateRun"
    override val reversibility = Reversibility.IRREVERSIBLE
    override val audited = true

    override fun permits(
        actor: String,
        command: RecordGateRunCommand,
    ) = permissions.holds(actor, ReleasePermission.CERTIFY) && actor == command.actor.value

    override fun repeatKey(command: RecordGateRunCommand) =
        "${command.trainVersion.value}|${command.gate}|${command.environment?.name ?: "-"}"

    override fun load(command: RecordGateRunCommand) =
        GateRunState(
            runs.find(GateRunKey(command.trainVersion, command.gate, command.environment)),
            trains.find(command.trainVersion) != null,
        )

    override fun priorResult(
        command: RecordGateRunCommand,
        state: GateRunState,
    ) = state.existing?.takeIf { it.sameResultAs(recordOf(command)) }

    override fun unmetPrecondition(
        command: RecordGateRunCommand,
        state: GateRunState,
    ): String? {
        val violation =
            GateRunRules.violation(
                command.gate,
                command.environment,
                command.candidate,
                command.baseline,
                command.candidateFindings,
                command.baselineFindings,
                command.evidenceRef,
            )
        return when {
            violation != null -> {
                Refusal(Refusal.GATE_RUN_INVALID, violation).encode()
            }

            state.published -> {
                Refusal(Refusal.GATE_RUN_CONFLICT, "${command.trainVersion} is already published").encode()
            }

            state.existing != null -> {
                Refusal(Refusal.GATE_RUN_CONFLICT, "${repeatKey(command)} holds a different result").encode()
            }

            else -> {
                null
            }
        }
    }

    override fun perform(
        command: RecordGateRunCommand,
        state: GateRunState,
    ): GateRunRecord {
        val record = recordOf(command)
        check(runs.insert(record)) { "gate run ${repeatKey(command)} was recorded concurrently" }
        return record
    }

    override fun audit(
        command: RecordGateRunCommand,
        state: GateRunState,
        result: GateRunRecord,
    ) = OperationAudit(
        "platform",
        null,
        null,
        EventContext(
            tenant = TenantId.PLATFORM,
            entity = EntityRef.PLATFORM,
            docUuid = null,
            transactionDate = null,
            aggregateKey = repeatKey(command),
            aggregateVersion = null,
            correlation = CorrelationId(command.request.correlationId),
            causation = CausationId(command.request.causationId),
        ),
    )

    private fun recordOf(command: RecordGateRunCommand) =
        GateRunRecord(
            command.trainVersion,
            command.gate,
            command.environment,
            command.candidate,
            command.baseline,
            command.candidateFindings.toSet(),
            command.baselineFindings.toSet(),
            command.evidenceRef,
            command.actor,
            clock.instant(),
        )
}
