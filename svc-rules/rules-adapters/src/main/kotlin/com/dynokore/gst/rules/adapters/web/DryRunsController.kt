package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.web.MappingPackMapping.problemBody
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.refusalOf
import com.dynokore.gst.rules.application.MappingPackResult
import com.dynokore.gst.rules.application.RefusalReason
import com.dynokore.gst.rules.contract.api.DryRunsApi
import com.dynokore.gst.rules.contract.model.DryRunRequest
import com.dynokore.gst.rules.contract.model.DryRunResult
import com.dynokore.gst.rules.contract.model.RulesDryRunStatus
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The dry-run operations of the rules contract (`dryRun`, `getDryRun`; tag `dry-runs`), moved from `PacksController`
 * unchanged (INV-T-4974; INV-REQ-772, INV-REQ-1303, INV-REQ-1352; INV-API-3473). The contract gives each operation one
 * tag, so each is in exactly one generated interface and the parallel-constraint rule of Hibernate Validator stays on.
 */
@RestController
open class DryRunsController(
    private val dryRuns: DryRunCalls,
    private val transactions: TransactionRunner,
) : DryRunsApi {
    /** A refusal of a dry-run command or read as the API-20 envelope. */
    @ExceptionHandler(MappingRefusal::class)
    fun refuse(refusal: MappingRefusal): ResponseEntity<String> =
        ResponseEntity
            .status(refusal.status)
            .header("Content-Type", "application/problem+json")
            .body(problemBody(refusal, correlationId()))

    /** The dry-run capacity is in use: 503 SRT-013 with `Retry-After`; nothing is written. */
    @ExceptionHandler(DryRunCapacityInUse::class)
    fun capacityInUse(e: DryRunCapacityInUse): ResponseEntity<String> {
        val refusal = unavailable(e.message.orEmpty())
        return ResponseEntity
            .status(refusal.status)
            .header("Content-Type", "application/problem+json")
            .header("Retry-After", e.retryAfterSeconds.toString())
            .body(problemBody(refusal, correlationId()))
    }

    /** A body that does not match the contract (unknown enum value, malformed JSON) is SRT-001; nothing is stored. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException): ResponseEntity<String> =
        refuse(
            checkNotNull(
                refusalOf(MappingPackResult.Refused(RefusalReason.INPUT_INVALID)),
            ).also { it.addSuppressed(e) },
        )

    /** A request the bean validation of the generated model refuses is 400 SRT-001 in the API-20 envelope. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ResponseEntity<String> =
        refuse(
            checkNotNull(
                refusalOf(MappingPackResult.Refused(RefusalReason.INPUT_INVALID)),
            ).also { it.addSuppressed(e) },
        )

    override fun dryRun(
        packRef: String,
        idempotencyKey: UUID,
        dryRunRequest: DryRunRequest?,
    ): ResponseEntity<DryRunResult> {
        val window = DryRunWindows.of(dryRunRequest)
        return when (val started = dryRuns.start(packRef, window)) {
            is DryRunStarted.Finished -> {
                val record = checkNotNull(readRun(packRef, started.dryRunId)) { "the run vanished" }
                val result = DryRunMapping.resultOf(packRef, record)
                if (result != null) ResponseEntity.ok(result) else accepted(packRef, record)
            }

            is DryRunStarted.Running -> {
                accepted(packRef, checkNotNull(readRun(packRef, started.dryRunId)) { "the run vanished" })
            }
        }
    }

    @Suppress("UNCHECKED_CAST") // the 202 body is the status resource; the generated signature types the 200 body only
    private fun accepted(
        packRef: String,
        record: DryRunRecord,
    ): ResponseEntity<DryRunResult> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .header("Location", "/internal/v1/rules/packs/$packRef/dry-runs/${record.stored.dryRunId}")
            .header("Retry-After", dryRuns.retryAfterSeconds.toString())
            .body(DryRunMapping.statusOf(packRef, record, correlationId())) as ResponseEntity<DryRunResult>

    private fun readRun(
        packRef: String,
        dryRunId: String,
    ): DryRunRecord? = transactions.inTransaction { dryRuns.find(packRef, dryRunId) }

    override fun getDryRun(
        packRef: String,
        dryRunId: String,
    ): ResponseEntity<RulesDryRunStatus> {
        val record = readRun(packRef, dryRunId) ?: throw checkNotNull(refusalOf(MappingPackResult.NotFound))
        return ResponseEntity.ok(DryRunMapping.statusOf(packRef, record, correlationId()))
    }

    private fun correlationId(): String =
        CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
}
