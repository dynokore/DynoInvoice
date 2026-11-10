package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.IntakeCommand
import com.dynokore.gst.aggregation.application.IntakeOutcome
import com.dynokore.gst.aggregation.application.NewAggregate
import com.dynokore.gst.aggregation.application.StoredAggregate
import com.dynokore.gst.aggregation.contract.api.IntakeApi
import com.dynokore.gst.aggregation.contract.model.AggregationBatchRequest
import com.dynokore.gst.aggregation.contract.model.AggregationReceipt
import com.dynokore.gst.aggregation.domain.AggregateIntakeCheck
import com.dynokore.gst.aggregation.domain.IntakeVerdict
import com.dynokore.platform.serviceruntime.http.IngestionErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The intake operation of the aggregation contract (TPL-08, INV-REQ-427, INV-REQ-434 items 1, 2, 3 and 5,
 * INV-REQ-436). The type check (ING-006) runs while the body is read ([NotAggregatableAdvice]); the rules are
 * [IntakeAggregate]'s. A first intake and a replay both answer 202 with the receipt of the stored aggregate.
 */
@RestController
open class IntakeController(
    private val intake: IntakeAggregate,
) : IntakeApi {
    override fun intakeAggregationBatch(
        idempotencyKey: UUID,
        aggregationBatchRequest: AggregationBatchRequest,
    ): ResponseEntity<AggregationReceipt> {
        val request = aggregationBatchRequest
        val type = (AggregateIntakeCheck.check(request.aggregateType.name) as IntakeVerdict.Accepted).type
        val outcome =
            intake.execute(
                IntakeCommand(
                    entityRef = request.entityRef,
                    businessUnitRef = request.businessUnitRef,
                    aggregateType = type,
                    periodStart = request.periodStart,
                    periodEnd = request.periodEnd,
                    sourceKey = request.sourceKey,
                    sourceRevision = request.sourceRevision.toLong(),
                ),
            )
        return when (outcome) {
            is IntakeOutcome.Created -> {
                accepted(outcome.aggregate)
            }

            is IntakeOutcome.Replayed -> {
                accepted(outcome.aggregate)
            }

            is IntakeOutcome.SourceRevisionLower -> {
                problem(IngestionErrorCode.SOURCE_REVISION_LOWER)
            }

            is IntakeOutcome.NumberNotBuildable -> {
                log.error("ING-022: the aggregate number cannot be built, {} has no usable short code", outcome.reason)
                problem(IngestionErrorCode.AGGREGATE_NUMBER_NOT_BUILDABLE)
            }
        }
    }

    /** The state stored with the aggregate at its first intake; a replay answers it unchanged (INV-REQ-434). */
    private fun windowState(aggregate: NewAggregate): AggregationReceipt.WindowState =
        AggregationReceipt.WindowState.valueOf(
            checkNotNull(aggregate.windowState) { "an aggregate is placed in a window at its first intake" }.name,
        )

    private fun accepted(stored: StoredAggregate): ResponseEntity<AggregationReceipt> {
        val aggregate = stored.aggregate
        val receipt =
            AggregationReceipt(
                entityRef = aggregate.entityRef,
                aggregateType = AggregationReceipt.AggregateType.valueOf(aggregate.aggregateType.name),
                windowState = windowState(aggregate),
                documentNumber = aggregate.documentNumber,
                sequence = aggregate.sequence,
                issueDate = aggregate.issueDate,
                transactionDate = aggregate.transactionDate,
                aggregateId = stored.aggregateId.toString(),
            )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(receipt)
    }

    /**
     * The Problem of [code] (API-20), rendered by [Problems.renderIngestion]; its JSON string is written as the body
     * of the operation's generic response, as [NotAggregatableAdvice] does for ING-006.
     */
    @Suppress("UNCHECKED_CAST")
    private fun problem(code: IngestionErrorCode): ResponseEntity<AggregationReceipt> {
        val rendered = Problems.renderIngestion(code, correlationId())
        return ResponseEntity
            .status(rendered.status)
            .headers { headers -> rendered.headers.forEach { (name, value) -> headers.set(name, value) } }
            .body(rendered.body) as ResponseEntity<AggregationReceipt>
    }

    private fun correlationId(): String =
        CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()

    private companion object {
        val log: Logger = LoggerFactory.getLogger(IntakeController::class.java)
    }
}
