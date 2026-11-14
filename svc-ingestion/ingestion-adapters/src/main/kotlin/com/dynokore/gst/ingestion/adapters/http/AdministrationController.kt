package com.dynokore.gst.ingestion.adapters.http

import com.dynokore.gst.ingestion.application.permission.CallerSource
import com.dynokore.gst.ingestion.application.triggers.CoverageOutcome
import com.dynokore.gst.ingestion.application.triggers.MapTriggerClass
import com.dynokore.gst.ingestion.application.triggers.MapTriggerClassCommand
import com.dynokore.gst.ingestion.application.triggers.WaiveTriggerClass
import com.dynokore.gst.ingestion.application.triggers.WaiveTriggerClassCommand
import com.dynokore.gst.ingestion.application.triggers.WaiverMode
import com.dynokore.gst.ingestion.contract.api.AdministrationApi
import com.dynokore.gst.ingestion.contract.model.TriggerMappingRequest
import com.dynokore.gst.ingestion.contract.model.TriggerMappingResult
import com.dynokore.gst.ingestion.contract.model.TriggerWaiverRequest
import com.dynokore.gst.ingestion.contract.model.TriggerWaiverResult
import com.dynokore.gst.ingestion.domain.triggers.CoverageRecord
import com.dynokore.gst.ingestion.domain.triggers.TriggerClass
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The operations of one tag of the ingestion contract (TPL-08). The two trigger-coverage commands run the use cases
 * of `application.triggers` in one transaction of the caller's tenant (ARC-21). Every other operation answers 501
 * until the task named for it in `operations.tsv` overrides its method: the generated interface's default is
 * NOT_IMPLEMENTED.
 * A refusal is an [ApiRefusal], rendered once by [ApiRefusalAdvice] (ARC-19).
 */
@RestController
@Suppress("SwallowedException") // a domain validation failure or a non-user subject becomes the closed refusal (ARC-19)
open class AdministrationController(
    private val callers: CallerSource,
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val mapTriggerClass: MapTriggerClass,
    private val waiveTriggerClass: WaiveTriggerClass,
) : AdministrationApi {
    override fun mapTriggerClass(
        entityRef: String,
        triggerClass: String,
        idempotencyKey: UUID,
        triggerMappingRequest: TriggerMappingRequest,
    ): ResponseEntity<TriggerMappingResult> {
        val actor = actorUserId()
        val outcome =
            try {
                val command =
                    MapTriggerClassCommand(
                        tenants.current().value,
                        entityRef,
                        actor,
                        classOf(triggerClass),
                        triggerMappingRequest.adapterRef,
                    )
                transactions.inTransaction { mapTriggerClass.execute(command) }
            } catch (e: IllegalArgumentException) {
                // a blank or over-long adapter reference
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, e.message)
            }
        val (record, at) = recorded(outcome)
        val mapped = record as CoverageRecord.Mapped
        return ResponseEntity.ok(TriggerMappingResult(entityRef, triggerClass, mapped.adapterRef, utc(at)))
    }

    override fun waiveTriggerClass(
        entityRef: String,
        triggerClass: String,
        idempotencyKey: UUID,
        triggerWaiverRequest: TriggerWaiverRequest,
    ): ResponseEntity<TriggerWaiverResult> {
        val actor = actorUserId()
        val command =
            WaiveTriggerClassCommand(
                tenants.current().value,
                entityRef,
                actor,
                classOf(triggerClass),
                WaiverMode.valueOf(triggerWaiverRequest.mode.name),
                triggerWaiverRequest.reviewDate,
                triggerWaiverRequest.coveringSolution,
                triggerWaiverRequest.evidenceReference,
            )
        val outcome =
            try {
                transactions.inTransaction { waiveTriggerClass.execute(command) }
            } catch (e: IllegalArgumentException) {
                // a missing review date, or a blank or over-long covering solution or evidence reference
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, e.message)
            }
        val (record, at) = recorded(outcome)
        return ResponseEntity.ok(waiverResult(entityRef, triggerClass, record, at))
    }

    private fun waiverResult(
        entityRef: String,
        triggerClass: String,
        record: CoverageRecord,
        at: Instant,
    ): TriggerWaiverResult =
        when (record) {
            is CoverageRecord.Waived -> {
                TriggerWaiverResult(
                    entityRef,
                    triggerClass,
                    TriggerWaiverResult.Mode.REVIEW_WAIVER,
                    utc(at),
                    reviewDate = record.reviewDate,
                )
            }

            is CoverageRecord.CoveredElsewhere -> {
                TriggerWaiverResult(
                    entityRef,
                    triggerClass,
                    TriggerWaiverResult.Mode.COVERED_BY_OTHER_SOLUTION,
                    utc(at),
                    coveringSolution = record.coveringSolution,
                    evidenceReference = record.evidenceReference,
                )
            }

            is CoverageRecord.Mapped -> {
                throw ApiRefusal(ClosedErrorCode.STALE_AGGREGATE)
            }
        }

    /** The stored record and its stored time; every other outcome is a refusal (403 or 409), never a body. */
    private fun recorded(outcome: CoverageOutcome): Pair<CoverageRecord, Instant> =
        when (outcome) {
            is CoverageOutcome.Stored -> outcome.record to outcome.recordedAt
            is CoverageOutcome.Unchanged -> outcome.record to outcome.recordedAt
            is CoverageOutcome.PreconditionFailed -> throw ApiRefusal(ClosedErrorCode.STALE_AGGREGATE)
            CoverageOutcome.Forbidden -> throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }

    /** The acting user id of the verified token; a subject that is no user id is not an end user (401). */
    private fun actorUserId(): UUID =
        try {
            UUID.fromString(callers.actor())
        } catch (e: IllegalArgumentException) {
            throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)
        }

    private fun classOf(name: String): TriggerClass =
        TriggerClass.parse(name) ?: throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "unknown trigger class")

    private fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)
}
