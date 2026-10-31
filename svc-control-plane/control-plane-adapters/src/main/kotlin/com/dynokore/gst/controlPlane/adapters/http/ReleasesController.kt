package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.PublishTrainCommand
import com.dynokore.gst.controlPlane.application.release.SetSupportWindow
import com.dynokore.gst.controlPlane.application.release.SetSupportWindowCommand
import com.dynokore.gst.controlPlane.contract.api.ReleasesApi
import com.dynokore.gst.controlPlane.contract.model.PublishTrainRequest
import com.dynokore.gst.controlPlane.contract.model.ReleaseGateResult
import com.dynokore.gst.controlPlane.contract.model.ReleaseGateResultAccreditationCheck
import com.dynokore.gst.controlPlane.contract.model.ReleaseGateResultGatesInner
import com.dynokore.gst.controlPlane.contract.model.ReleaseTrainResponse
import com.dynokore.gst.controlPlane.contract.model.SetSupportWindowRequest
import com.dynokore.gst.controlPlane.contract.model.SupportWindowResponse
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.SupportWindow
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.operation.OperationOutcome
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.ZoneOffset
import java.util.UUID

/**
 * publishTrain and setSupportWindow (TPL-08). The caller is the verified token's `sub`, never a request field
 * (API-24); a train is platform data, so the command runs in a transaction bound to the platform tenant. The
 * accreditation operations of the same contract tag are not overridden here: the generated interface answers 501.
 */
@RestController
class ReleasesController(
    private val principals: EndUserPrincipalResolver,
    private val platform: PlatformTenantBinding,
    private val runner: OperationRunner,
    private val publishTrain: PublishTrain,
    private val setSupportWindow: SetSupportWindow,
) : ReleasesApi {
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
        val train =
            when (outcome) {
                is OperationOutcome.Performed -> outcome.result
                is OperationOutcome.Repeated -> outcome.result
                OperationOutcome.NotPermitted -> problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
                is OperationOutcome.PreconditionUnmet -> refuse(Refusal.parse(outcome.code), caller.correlationId)
            }
        return ResponseEntity.status(HttpStatus.CREATED).body(responseOf(train))
    }

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
            Refusal.GATE_FAILED, Refusal.NO_GATE_EVIDENCE -> {
                problem(ClosedErrorCode.CONTRACT_VIOLATION, correlationId, "${refusal.code}: ${refusal.detail}")
            }

            else -> {
                error("no response is declared for the refusal ${refusal.code}")
            }
        }

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
        train?.accreditation?.let {
            ReleaseGateResultAccreditationCheck(it.assessmentNeeded, it.alertRef, it.taskRef)
        },
    recordedAt = record.recordedAt.atOffset(ZoneOffset.UTC),
)

internal fun corpusResultOf(record: ReleaseRecord): String? =
    record.runs
        .firstOrNull { it.gate == com.dynokore.gst.controlPlane.domain.release.Gate.CORPUS }
        ?.candidate
        ?.let { if (it == GateOutcome.PASSED) "PASSED" else "FAILED" }
