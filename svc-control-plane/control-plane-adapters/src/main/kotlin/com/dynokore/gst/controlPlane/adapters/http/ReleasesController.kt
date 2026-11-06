package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.application.accreditation.AccreditationTaskQueries
import com.dynokore.gst.controlPlane.application.accreditation.ConcludeAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.ConcludeAccreditationTaskCommand
import com.dynokore.gst.controlPlane.application.accreditation.GetAccreditation
import com.dynokore.gst.controlPlane.application.accreditation.OpenAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.RecordAccreditation
import com.dynokore.gst.controlPlane.application.accreditation.RecordAccreditationCommand
import com.dynokore.gst.controlPlane.application.accreditation.ReleaseTarget
import com.dynokore.gst.controlPlane.application.accreditation.TaskCursorKey
import com.dynokore.gst.controlPlane.application.accreditation.TaskRead
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditEntries
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditEntriesCommand
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditOutcome
import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.GateRunRaced
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.PublishTrainCommand
import com.dynokore.gst.controlPlane.application.release.RecordGateRun
import com.dynokore.gst.controlPlane.application.release.RecordGateRunCommand
import com.dynokore.gst.controlPlane.application.release.SetSupportWindow
import com.dynokore.gst.controlPlane.application.release.SetSupportWindowCommand
import com.dynokore.gst.controlPlane.contract.api.ReleasesApi
import com.dynokore.gst.controlPlane.contract.model.AccreditationRecord
import com.dynokore.gst.controlPlane.contract.model.AccreditationStatus
import com.dynokore.gst.controlPlane.contract.model.AccreditationTaskPage
import com.dynokore.gst.controlPlane.contract.model.ConcludeAccreditationTaskRequest
import com.dynokore.gst.controlPlane.contract.model.GateRunRequest
import com.dynokore.gst.controlPlane.contract.model.GateRunResponse
import com.dynokore.gst.controlPlane.contract.model.PublishTrainRequest
import com.dynokore.gst.controlPlane.contract.model.PurgeAuditEntriesRequest
import com.dynokore.gst.controlPlane.contract.model.PurgeAuditEntriesResponse
import com.dynokore.gst.controlPlane.contract.model.RecordAccreditationRequest
import com.dynokore.gst.controlPlane.contract.model.RecordAccreditationRequestSandboxAcknowledgementsInner
import com.dynokore.gst.controlPlane.contract.model.ReleaseGateResult
import com.dynokore.gst.controlPlane.contract.model.ReleaseGateResultAccreditationCheck
import com.dynokore.gst.controlPlane.contract.model.ReleaseGateResultGatesInner
import com.dynokore.gst.controlPlane.contract.model.ReleaseTrainResponse
import com.dynokore.gst.controlPlane.contract.model.SetSupportWindowRequest
import com.dynokore.gst.controlPlane.contract.model.SupportWindowResponse
import com.dynokore.gst.controlPlane.domain.accreditation.SandboxAcknowledgement
import com.dynokore.gst.controlPlane.domain.accreditation.SubmissionType
import com.dynokore.gst.controlPlane.domain.accreditation.TaskStatus
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.GateRunRecord
import com.dynokore.gst.controlPlane.domain.release.GateRunSubmission
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.SupportWindow
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.ProblemResponse
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.operation.OperationOutcome
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * publishTrain, setSupportWindow and recordGateRun (TPL-08). The caller is the verified token's `sub`, never a request
 * field (API-24); a train is platform data, so the command runs in a transaction bound to the platform tenant. The
 * accreditation operations of the same contract tag are recordAccreditation and getAccreditation (INV-T-7322) and
 * the task operations listAccreditationTasks, getAccreditationTask and concludeAccreditationTask (INV-T-7323). After
 * publishTrain commits, the task step runs in a transaction of its own and never delays or undoes the release.
 * purgeAuditEntries (INV-T-3099) also sits on the `releases` tag; it runs the audited purge of the operator's local
 * audit entries and maps the outcomes of the use case: SRT-004, CPL-001 and CPL-002, no other.
 */
@RestController
// One method per operation of the generated ReleasesApi (TPL-08); one constructor-injected collaborator per parameter.
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class ReleasesController(
    private val principals: EndUserPrincipalResolver,
    private val platform: PlatformTenantBinding,
    private val runner: OperationRunner,
    private val publishTrain: PublishTrain,
    private val setSupportWindow: SetSupportWindow,
    private val recordAccreditation: RecordAccreditation,
    private val getAccreditation: GetAccreditation,
    private val purgeAuditEntries: PurgeAuditEntries,
    private val recordGateRun: RecordGateRun,
    private val taskQueries: AccreditationTaskQueries,
    private val concludeTask: ConcludeAccreditationTask,
    private val accreditationStep: OpenAccreditationTask,
    private val cursors: TaskCursor,
) : ReleasesApi {
    override fun recordAccreditation(
        idempotencyKey: UUID,
        recordAccreditationRequest: RecordAccreditationRequest,
    ): ResponseEntity<AccreditationRecord> {
        val caller = caller()
        val command =
            commandOf(caller.correlationId) {
                RecordAccreditationCommand(
                    ActorId(caller.actor),
                    recordAccreditationRequest.grantedOn,
                    recordAccreditationRequest.currentUntil,
                    recordAccreditationRequest.imdaReference,
                    recordAccreditationRequest.coveredSubmissionTypes.map { SubmissionType.valueOf(it.name) },
                    recordAccreditationRequest.sandboxAcknowledgements.orEmpty().map {
                        SandboxAcknowledgement(SubmissionType.valueOf(it.submissionType.name), it.acknowledgementId)
                    },
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(caller.actor) { runner.run(recordAccreditation, caller.actor, command) }
        return when (outcome) {
            is OperationOutcome.Performed -> ResponseEntity.status(HttpStatus.CREATED).body(recordOf(outcome.result))
            is OperationOutcome.Repeated -> ResponseEntity.status(HttpStatus.CREATED).body(recordOf(outcome.result))
            OperationOutcome.NotPermitted -> problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
            is OperationOutcome.PreconditionUnmet -> problem(ClosedErrorCode.CONTRACT_VIOLATION, caller.correlationId)
        }
    }

    override fun getAccreditation(): ResponseEntity<AccreditationStatus> {
        val caller = caller()
        val status =
            platform.bindPlatform(caller.actor) { getAccreditation.get(caller.actor) }
                ?: problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
        return ResponseEntity.ok(
            AccreditationStatus(
                AccreditationStatus.Currency.valueOf(status.currency.name),
                status.asOf,
                status.record?.let(::recordOf),
            ),
        )
    }

    private fun recordOf(record: com.dynokore.gst.controlPlane.domain.accreditation.AccreditationRecord) =
        AccreditationRecord(
            grantedOn = record.grantedOn,
            currentUntil = record.currentUntil,
            imdaReference = record.imdaReference,
            coveredSubmissionTypes =
                record.coveredSubmissionTypes
                    .map {
                        AccreditationRecord.CoveredSubmissionTypes.valueOf(
                            it.name,
                        )
                    }.toSet(),
            recordedAt = record.recordedAt.atOffset(ZoneOffset.UTC),
            recordedBy = record.recordedBy.value,
            sandboxAcknowledgements =
                record.sandboxAcknowledgements.map {
                    RecordAccreditationRequestSandboxAcknowledgementsInner(
                        RecordAccreditationRequestSandboxAcknowledgementsInner.SubmissionType.valueOf(
                            it.submissionType.name,
                        ),
                        it.acknowledgementId,
                    )
                },
        )

    override fun publishTrain(
        idempotencyKey: UUID,
        publishTrainRequest: PublishTrainRequest,
    ): ResponseEntity<ReleaseTrainResponse> {
        val caller = caller()
        val command =
            commandOf(caller.correlationId) {
                PublishTrainCommand(
                    ActorId(caller.actor),
                    TrainVersion(publishTrainRequest.trainVersion),
                    TrainKind.valueOf(publishTrainRequest.kind.name),
                    publishTrainRequest.exposureReducing ?: false,
                    publishTrainRequest.needsReAccreditationAssessment ?: true,
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(caller.actor) { runner.run(publishTrain, caller.actor, command) }
        val released =
            when (outcome) {
                is OperationOutcome.Performed -> outcome.result
                is OperationOutcome.Repeated -> outcome.result
                OperationOutcome.NotPermitted -> problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
                is OperationOutcome.PreconditionUnmet -> refuse(Refusal.parse(outcome.code), caller.correlationId)
            }
        // The release has committed. The task step has a transaction of its own and logs its failure (INV-REQ-2657 b5).
        val check =
            platform.bindPlatform(caller.actor) {
                accreditationStep.afterRelease(
                    ReleaseTarget(ReleaseTarget.Kind.TRAIN, released.version.value),
                    command.needsReAccreditationAssessment,
                    command.request,
                )
            }
        val train = if (check == null) released else released.copy(accreditation = check)
        return ResponseEntity.status(HttpStatus.CREATED).body(responseOf(train))
    }

    override fun listAccreditationTasks(
        status: String?,
        cursor: String?,
        limit: Int,
    ): ResponseEntity<AccreditationTaskPage> {
        val caller = caller()
        // Access control first: a denial is 403 with no key read and no input parsed (ASVS V4).
        if (!platform.bindPlatform(caller.actor) { taskQueries.mayList(caller.actor) }) {
            problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
        }
        val key = cursors.key() // before any query and before a cursor is parsed (INV-Q-15432 A)
        val filter =
            status?.let { name -> TaskStatus.entries.firstOrNull { it.name == name } }
                ?: if (status ==
                    null
                ) {
                    null
                } else {
                    problem(ClosedErrorCode.CONTRACT_VIOLATION, caller.correlationId, "status")
                }
        if (limit !in 1..AccreditationTaskQueries.MAX_LIMIT) {
            problem(ClosedErrorCode.CONTRACT_VIOLATION, caller.correlationId, "limit is 1 to 500")
        }
        val after =
            cursor?.let { cursors.decode(key, it, caller.actor, status) }
                ?: if (cursor ==
                    null
                ) {
                    null
                } else {
                    problem(ClosedErrorCode.CONTRACT_VIOLATION, caller.correlationId, "cursor")
                }
        val read = platform.bindPlatform(caller.actor) { taskQueries.list(caller.actor, filter, after, limit) }
        val page =
            when (read) {
                is TaskRead.Found -> read.value
                TaskRead.Denied, TaskRead.Missing -> problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
            }
        return ResponseEntity.ok(
            AccreditationTaskPage(
                page.items.map(::taskOf),
                page.items.lastOrNull()?.takeIf { page.more }?.let {
                    cursors.encode(key, TaskCursorKey(it.openedAt, it.taskRef), caller.actor, status)
                },
            ),
        )
    }

    override fun getAccreditationTask(
        taskRef: String,
    ): ResponseEntity<com.dynokore.gst.controlPlane.contract.model.AccreditationTask> {
        val caller = caller()
        val read = platform.bindPlatform(caller.actor) { taskQueries.get(caller.actor, taskRef) }
        return when (read) {
            is TaskRead.Found -> ResponseEntity.ok(taskOf(read.value))
            TaskRead.Denied -> problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
            TaskRead.Missing -> problem(ClosedErrorCode.NOT_FOUND, caller.correlationId)
        }
    }

    override fun concludeAccreditationTask(
        taskRef: String,
        idempotencyKey: UUID,
        concludeAccreditationTaskRequest: ConcludeAccreditationTaskRequest,
    ): ResponseEntity<com.dynokore.gst.controlPlane.contract.model.AccreditationTask> {
        val caller = caller()
        val command =
            commandOf(caller.correlationId) {
                ConcludeAccreditationTaskCommand(
                    ActorId(caller.actor),
                    taskRef,
                    concludeAccreditationTaskRequest.conclusion,
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(caller.actor) { runner.run(concludeTask, caller.actor, command) }
        val task =
            when (outcome) {
                is OperationOutcome.Performed -> outcome.result
                is OperationOutcome.Repeated -> outcome.result
                OperationOutcome.NotPermitted -> problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
                is OperationOutcome.PreconditionUnmet -> refuseTask(Refusal.parse(outcome.code), caller.correlationId)
            }
        return ResponseEntity.ok(taskOf(task))
    }

    private fun refuseTask(
        refusal: Refusal,
        correlationId: String,
    ): Nothing =
        when (refusal.code) {
            Refusal.TASK_NOT_FOUND -> {
                problem(ClosedErrorCode.NOT_FOUND, correlationId)
            }

            // CPL-007 (INV-Q-15428 A, INV-Q-15478 A): a different conclusion on a CONCLUDED task
            Refusal.TASK_ALREADY_CONCLUDED -> {
                throw ProblemException(
                    GateRunProblems.render(
                        GateRunProblems.CONCLUSION_DIFFERS,
                        "/conclusion",
                        "the task is already concluded with another conclusion",
                        correlationId,
                    ),
                )
            }

            else -> {
                error("no response is declared for the refusal ${refusal.code}")
            }
        }

    private fun taskOf(task: com.dynokore.gst.controlPlane.domain.accreditation.AccreditationTask) =
        com.dynokore.gst.controlPlane.contract.model.AccreditationTask(
            taskRef = task.taskRef,
            status =
                com.dynokore.gst.controlPlane.contract.model.AccreditationTask.Status
                    .valueOf(task.status.name),
            reason =
                com.dynokore.gst.controlPlane.contract.model.AccreditationTask.Reason
                    .valueOf(task.reason.name),
            openedAt = task.openedAt.atOffset(ZoneOffset.UTC),
            alertRef = task.alertRef,
            releaseRef = task.releaseRef,
            concludedAt = task.concludedAt?.atOffset(ZoneOffset.UTC),
            conclusion = task.conclusion,
        )

    override fun setSupportWindow(
        idempotencyKey: UUID,
        setSupportWindowRequest: SetSupportWindowRequest,
    ): ResponseEntity<SupportWindowResponse> {
        val caller = caller()
        val command =
            commandOf(caller.correlationId) {
                SetSupportWindowCommand(
                    ActorId(caller.actor),
                    SupportWindow(
                        setSupportWindowRequest.windowVersion,
                        setSupportWindowRequest.trainVersions.map(::TrainVersion),
                    ),
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(caller.actor) { runner.run(setSupportWindow, caller.actor, command) }
        val window =
            when (outcome) {
                is OperationOutcome.Performed -> {
                    outcome.result
                }

                is OperationOutcome.Repeated -> {
                    outcome.result
                }

                OperationOutcome.NotPermitted -> {
                    problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
                }

                is OperationOutcome.PreconditionUnmet -> {
                    problem(
                        ClosedErrorCode.CONTRACT_VIOLATION,
                        caller.correlationId,
                    )
                }
            }
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(SupportWindowResponse(window.windowVersion, window.trainVersions.map { it.value }))
    }

    override fun purgeAuditEntries(
        idempotencyKey: UUID,
        purgeAuditEntriesRequest: PurgeAuditEntriesRequest,
    ): ResponseEntity<PurgeAuditEntriesResponse> {
        val caller = caller()
        val command =
            PurgeAuditEntriesCommand(
                caller.actor,
                purgeAuditEntriesRequest.cutOff.toInstant(),
                purgeAuditEntriesRequest.reason,
                RequestContext(caller.correlationId, idempotencyKey.toString()),
            )
        val outcome = platform.bindPlatform(caller.actor) { purgeAuditEntries.execute(command) }
        return purgeResponse(outcome, purgeAuditEntriesRequest.cutOff, caller.correlationId)
    }

    private fun purgeResponse(
        outcome: PurgeAuditOutcome,
        cutOff: OffsetDateTime,
        correlationId: String,
    ): ResponseEntity<PurgeAuditEntriesResponse> =
        when (outcome) {
            is PurgeAuditOutcome.Purged -> {
                ResponseEntity.ok(
                    PurgeAuditEntriesResponse(
                        purgeId = outcome.purgeId,
                        cutOff = cutOff,
                        floor = outcome.floor.toString(),
                        deletedCount = outcome.result.deletedCount,
                        auditEntryId = UUID.fromString(outcome.result.auditEntryId),
                    ),
                )
            }

            PurgeAuditOutcome.NotPermitted -> {
                problem(ClosedErrorCode.ACCESS_DENIED, correlationId)
            }

            PurgeAuditOutcome.CutOffYoungerThanFloor -> {
                val title = "Audit purge cut-off younger than the floor"
                val refusal = purgeRefusal(CUT_OFF_YOUNGER_THAN_FLOOR, HTTP_UNPROCESSABLE, title, correlationId)
                throw ProblemException(refusal)
            }

            PurgeAuditOutcome.FloorUndeterminable -> {
                val title = "Audit purge floor cannot be determined"
                val refusal = purgeRefusal(FLOOR_UNDETERMINABLE, HTTP_UNAVAILABLE, title, correlationId)
                throw ProblemException(refusal)
            }
        }

    /** The API-20 envelope of a catalogued CPL refusal of the purge (BLOCK, TERMINAL, owner Platform). */
    private fun purgeRefusal(
        code: String,
        status: Int,
        title: String,
        correlationId: String,
    ): ProblemResponse {
        val body =
            linkedMapOf(
                "type" to Problems.typeOf(code),
                "title" to title,
                "status" to status,
                "code" to code,
                "severity" to "BLOCK",
                "disposition" to "TERMINAL",
                "remediationOwner" to "Platform",
                "correlationId" to correlationId,
            )
        return ProblemResponse(
            status,
            mapOf(Problems.CONTENT_TYPE_HEADER to Problems.CONTENT_TYPE),
            purgeJson.writeValueAsString(body),
        )
    }

    override fun recordGateRun(
        trainVersion: String,
        idempotencyKey: UUID,
        gateRunRequest: GateRunRequest,
    ): ResponseEntity<GateRunResponse> {
        val caller = caller()
        val command =
            commandOf(caller.correlationId) {
                RecordGateRunCommand(
                    ActorId(caller.actor),
                    TrainVersion(trainVersion),
                    GateRunSubmission(
                        Gate.valueOf(gateRunRequest.gate.name),
                        gateRunRequest.environment?.let { ReferenceEnvironment.valueOf(it.name) },
                        GateOutcome.valueOf(gateRunRequest.candidateResult.name),
                        gateRunRequest.baselineResult?.let { GateOutcome.valueOf(it.name) },
                        gateRunRequest.candidateFindings.orEmpty(),
                        gateRunRequest.baselineFindings.orEmpty(),
                        gateRunRequest.evidenceRef,
                    ),
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome =
            try {
                platform.bindPlatform(caller.actor) { runner.run(recordGateRun, caller.actor, command) }
            } catch (raced: GateRunRaced) {
                refuse(raced.refusal, caller.correlationId)
            }
        val record =
            when (outcome) {
                is OperationOutcome.Performed -> outcome.result
                is OperationOutcome.Repeated -> outcome.result
                OperationOutcome.NotPermitted -> problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
                is OperationOutcome.PreconditionUnmet -> refuse(Refusal.parse(outcome.code), caller.correlationId)
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(responseOf(record))
    }

    private companion object {
        const val CUT_OFF_YOUNGER_THAN_FLOOR = "CPL-001"
        const val FLOOR_UNDETERMINABLE = "CPL-002"
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_UNAVAILABLE = 503
        val purgeJson: JsonMapper = JsonMapper.builder().build()
    }

    private class Caller(
        val actor: String,
        val correlationId: String,
    )

    /** The platform operator of the request: a verified token with no tenant (INV-REQ-1202 b2). */
    private fun caller(): Caller {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        val correlationId =
            request.getHeader("X-Correlation-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val principal =
            principals.resolve(request.getHeader("Authorization"))
                ?: problem(ClosedErrorCode.UNAUTHENTICATED, correlationId)
        if (principal.tenant != null) {
            problem(ClosedErrorCode.ACCESS_DENIED, correlationId, "A platform operator token is required")
        }
        return Caller(principal.actor, correlationId)
    }

    private fun <T> commandOf(
        correlationId: String,
        build: () -> T,
    ): T =
        try {
            build()
        } catch (invalid: IllegalArgumentException) {
            problem(ClosedErrorCode.CONTRACT_VIOLATION, correlationId, invalid.message)
        }

    private fun refuse(
        refusal: Refusal,
        correlationId: String,
    ): Nothing =
        when (refusal.code) {
            // a release gate holds the train back: the detail names each gate and the finding the train introduced
            Refusal.GATE_FAILED,
            Refusal.NO_GATE_EVIDENCE,
            -> {
                problem(ClosedErrorCode.CONTRACT_VIOLATION, correlationId, "${refusal.code}: ${refusal.detail}")
            }

            // recordGateRun (INV-Q-14041 A): the detail of a rule refusal starts with the field of the request
            Refusal.GATE_RUN_INVALID -> {
                throw ProblemException(
                    GateRunProblems.render(
                        GateRunProblems.RULE_BROKEN,
                        refusal.detail.substringBefore(' '),
                        refusal.detail.substringAfter(' '),
                        correlationId,
                    ),
                )
            }

            Refusal.GATE_RUN_TRAIN_PUBLISHED -> {
                throw ProblemException(
                    GateRunProblems.render(
                        GateRunProblems.TRAIN_PUBLISHED,
                        "/trainVersion",
                        refusal.detail,
                        correlationId,
                    ),
                )
            }

            Refusal.GATE_RUN_DIFFERENT -> {
                throw ProblemException(
                    GateRunProblems.render(GateRunProblems.RECORD_DIFFERS, "/gate", refusal.detail, correlationId),
                )
            }

            else -> {
                error("no response is declared for the refusal ${refusal.code}")
            }
        }

    private fun responseOf(record: GateRunRecord) =
        GateRunResponse(
            trainVersion = record.trainVersion.value,
            gate = GateRunResponse.Gate.valueOf(record.gate.name),
            candidateResult = GateRunResponse.CandidateResult.valueOf(record.candidate.name),
            recordedAt = record.recordedAt.atOffset(ZoneOffset.UTC),
            recordedBy = record.recordedBy.value,
            environment = record.environment?.let { GateRunResponse.Environment.valueOf(it.name) },
            baselineResult = record.baseline?.let { GateRunResponse.BaselineResult.valueOf(it.name) },
            candidateFindings = record.candidateFindings.sorted(),
            baselineFindings = record.baselineFindings.sorted(),
            evidenceRef = record.evidenceRef,
        )

    private fun responseOf(train: ReleaseTrain) =
        ReleaseTrainResponse(
            trainVersion = train.version.value,
            kind = ReleaseTrainResponse.Kind.valueOf(train.kind.name),
            status = ReleaseTrainResponse.Status.AVAILABLE,
            releaseGateResult = gateResultOf(train.record, train),
        )
}

/** The contract's `ReleaseGateResult` of a release record; the accreditation check is the train's only. */
internal fun gateResultOf(
    record: ReleaseRecord,
    train: ReleaseTrain? = null,
) = ReleaseGateResult(
    gates =
        record.runs.map {
            ReleaseGateResultGatesInner(
                ReleaseGateResultGatesInner.Gate.valueOf(it.gate.name),
                ReleaseGateResultGatesInner.CandidateResult.valueOf(it.candidate.name),
                it.baseline?.let { baseline -> ReleaseGateResultGatesInner.BaselineResult.valueOf(baseline.name) },
            )
        },
    exposureReducing = record.exposureReducing,
    classifiedBy = record.classifiedBy.value,
    accreditationCheck =
        (train?.accreditation ?: record.accreditation)?.let {
            ReleaseGateResultAccreditationCheck(it.assessmentNeeded, it.alertRef, it.taskRef)
        },
    recordedAt = record.recordedAt.atOffset(ZoneOffset.UTC),
)

internal fun corpusResultOf(record: ReleaseRecord): String? =
    record.runs
        .firstOrNull { it.gate == com.dynokore.gst.controlPlane.domain.release.Gate.CORPUS }
        ?.candidate
        ?.let { if (it == GateOutcome.PASSED) "PASSED" else "FAILED" }
