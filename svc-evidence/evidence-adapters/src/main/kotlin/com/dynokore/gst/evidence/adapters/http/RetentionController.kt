package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.evidence.adapters.persistence.DeletionOperation
import com.dynokore.gst.evidence.adapters.persistence.JooqPartitionDeletionOperations
import com.dynokore.gst.evidence.application.retention.DeleteAtRetentionEnd
import com.dynokore.gst.evidence.application.retention.DeleteAtRetentionEndResult
import com.dynokore.gst.evidence.application.retention.PartitionRegistry
import com.dynokore.gst.evidence.application.retention.PartitionState
import com.dynokore.gst.evidence.contract.api.RetentionApi
import com.dynokore.gst.evidence.contract.model.DeletionResult
import com.dynokore.gst.evidence.contract.model.DeletionStatus
import com.dynokore.gst.evidence.contract.model.ErrorEnvelope
import com.dynokore.gst.evidence.contract.model.MaxEvidenceRetention
import com.dynokore.gst.evidence.contract.model.OperationState
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The read of the VP-43 settings failed: the only failure the 503 EVD-001 handler of [RetentionController] answers. */
class MaxRetentionUnreadable(
    cause: RuntimeException,
) : RuntimeException(cause)

/** A refused retention call: one closed service-runtime case, rendered once as the API-20 envelope. */
class RetentionRefusal(
    val code: ClosedErrorCode,
    val detail: String? = null,
) : RuntimeException(code.code)

/** A 409 of the deletion: a stop of the rules ([reason], the use case result) or the chain verification block. */
class DeletionConflict(
    val reason: String,
    val chainVerificationFailed: Boolean = false,
) : RuntimeException(reason)

/**
 * How long the status of a deletion is answered after it ended (API-17): at least [FLOOR], 7 days. A shorter period is
 * refused here, so a service configured with one does not start (fail closed).
 */
class PartitionDeletionVisibility(
    val period: Duration,
) {
    init {
        require(period >= FLOOR) { "the deletion status visibility must be at least $FLOOR, was $period" }
    }

    companion object {
        val FLOOR: Duration = Duration.ofDays(7)
    }
}

/** ARC-19, CS-14: the one place a [RetentionRefusal] or a [DeletionConflict] becomes the API-20 envelope. */
@RestControllerAdvice(assignableTypes = [RetentionController::class])
class RetentionRefusalAdvice {
    @ExceptionHandler(RetentionRefusal::class)
    fun refuse(refusal: RetentionRefusal): ResponseEntity<String> {
        val problem = Problems.closed(refusal.code, correlation(), refusal.detail)
        return ResponseEntity
            .status(problem.status)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .body(problem.body)
    }

    @ExceptionHandler(DeletionConflict::class)
    fun conflict(conflict: DeletionConflict): ResponseEntity<String> {
        val envelope = RetentionController.conflictEnvelope(conflict.reason, conflict.chainVerificationFailed, correlation())
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ObjectMapper().writeValueAsString(envelope))
    }

    private fun correlation() = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
}

/**
 * The retention operations of the evidence contract (TPL-08). An operation answers 501 until the task named for it in
 * `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * getMaxEvidenceRetention (INV-T-6540; INV-REQ-3767): the caller check (`x-sync-edge: control-plane-svc->evidence-svc`)
 * is service-runtime's, before this method. The figure is read in a platform transaction under the service's own
 * workload principal, so no tenant is bound and none is read. When the read fails the answer is 503 with the API-20
 * envelope of the catalogue code `EVD-001` and no figure (INV-Q-12582 A; API-22: a server error carries no detail).
 * The EVD-001 handler answers only for [MaxRetentionUnreadable], which only that method throws.
 *
 * deleteAtRetentionEnd and getPartitionDeletion (INV-T-22971; INV-REQ-639, INV-REQ-1011; API-17): the permission
 * `retention.delete.request` is decided per request by `AuthorityReplica.decide` for the partition's entity (403
 * SRT-004, nothing called). The single-flight claim is the RUNNING row of `partition_deletion_operation`, a unique
 * index of the database and never a flag; the deletion runs on a worker in a tenant transaction of its own and the
 * row ends SUCCEEDED or FAILED there. A deletion still running after the contract timeout answers 202 with the status
 * resource. The status is read only by the user ID that started it (equality), for the visibility period.
 */
@RestController
@Suppress("LongParameterList", "TooManyFunctions") // the two operations and their ports; one controller per tag
open class RetentionController(
    private val maxRetention: PlatformMaxRetention,
    private val deletion: DeleteAtRetentionEnd,
    private val registry: PartitionRegistry,
    private val operations: JooqPartitionDeletionOperations,
    private val replica: AuthorityReplica,
    private val scope: TenantScope,
    private val requests: ObjectProvider<HttpServletRequest>,
    private val clock: Clock,
    private val visibility: PartitionDeletionVisibility,
    private val waitMillis: Long = CONTRACT_TIMEOUT_MILLIS,
) : RetentionApi {
    override fun getMaxEvidenceRetention(): ResponseEntity<MaxEvidenceRetention> {
        val duration =
            try {
                maxRetention.read { it.duration() }
            } catch (failure: RuntimeException) {
                throw MaxRetentionUnreadable(failure)
            }
        return ResponseEntity.ok(MaxEvidenceRetention(duration))
    }

    override fun deleteAtRetentionEnd(
        partitionId: String,
        idempotencyKey: UUID?,
    ): ResponseEntity<DeletionResult> {
        val caller = caller()
        val tenant = checkNotNull(caller.tenant)
        val partition =
            inTenant(tenant) { registry.find(partitionId) } ?: throw RetentionRefusal(ClosedErrorCode.NOT_FOUND)
        val request =
            AuthorizationRequest(tenant, Principal(caller.actor, PrincipalKind.HUMAN), partition.entityRef, REQUEST_KEY)
        if (!replica.decide(request, Deadline.after(DECISION_BUDGET, clock)).allowed) {
            throw RetentionRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
        if (partition.state == PartitionState.DROPPED) {
            throw DeletionConflict(DeleteAtRetentionEndResult.ALREADY_DELETED.name)
        }
        val operation =
            inTenant(tenant) { operations.claim(tenant, partitionId, caller.actor, clock.instant()) }
                ?: throw DeletionConflict(RUNNING_REASON)
        val outcome = CompletableFuture<DeleteAtRetentionEndResult>()
        Thread.ofVirtual().name("partition-deletion").start { run(tenant, caller.actor, operation, outcome) }
        val result =
            try {
                outcome.get(waitMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                return running(operation)
            } catch (failure: ExecutionException) {
                log.error("the deletion of {} failed", partitionId, failure.cause)
                throw RetentionRefusal(ClosedErrorCode.INTERNAL_ERROR)
            }
        return when (result) {
            DeleteAtRetentionEndResult.DELETED -> {
                val at = clock.instant().atOffset(ZoneOffset.UTC)
                ResponseEntity.ok(DeletionResult(partitionId, DeletionResult.Status.DELETED, at))
            }

            DeleteAtRetentionEndResult.NOT_FOUND -> {
                throw RetentionRefusal(ClosedErrorCode.NOT_FOUND)
            }

            DeleteAtRetentionEndResult.CHAIN_VERIFICATION_FAILED -> {
                throw DeletionConflict(result.name, chainVerificationFailed = true)
            }

            else -> {
                throw DeletionConflict(result.name)
            }
        }
    }

    override fun getPartitionDeletion(partitionId: String): ResponseEntity<DeletionStatus> {
        val caller = caller()
        val tenant = checkNotNull(caller.tenant)
        val operation =
            inTenant(tenant) { operations.latest(tenant, partitionId) } ?: throw RetentionRefusal(ClosedErrorCode.NOT_FOUND)
        if (operation.starterUserId != caller.actor) throw RetentionRefusal(ClosedErrorCode.ACCESS_DENIED)
        val kept = (operation.completedAt ?: operation.startedAt).plus(visibility.period)
        if (!clock.instant().isBefore(kept)) throw RetentionRefusal(ClosedErrorCode.NOT_FOUND)
        return ResponseEntity.ok(status(operation))
    }

    /** The worker: the deletion in a tenant transaction, then the row ends in one of its own; the outcome follows. */
    @Suppress("TooGenericExceptionCaught") // any failure of the worker ends the row FAILED and fails the call
    private fun run(
        tenant: TenantId,
        actor: String,
        operation: DeletionOperation,
        outcome: CompletableFuture<DeleteAtRetentionEndResult>,
    ) {
        try {
            val result = inTenant(tenant) { deletion.execute(tenant.value.toString(), actor, operation.partitionId) }
            val code =
                when (result) {
                    DeleteAtRetentionEndResult.DELETED -> null
                    DeleteAtRetentionEndResult.CHAIN_VERIFICATION_FAILED -> CHAIN_CODE
                    else -> "${ClosedErrorCode.STALE_AGGREGATE.code}:${result.name}"
                }
            end(tenant, operation, code)
            outcome.complete(result)
        } catch (failure: Exception) {
            try {
                end(tenant, operation, ClosedErrorCode.INTERNAL_ERROR.code, force = true)
            } catch (second: Exception) {
                failure.addSuppressed(second)
            }
            outcome.completeExceptionally(failure)
        }
    }

    private fun end(
        tenant: TenantId,
        operation: DeletionOperation,
        errorCode: String?,
        force: Boolean = false,
    ) {
        inTenant(tenant) {
            operations.finish(tenant, operation.operationId, errorCode == null && !force, errorCode, clock.instant())
        }
    }

    private fun running(operation: DeletionOperation): ResponseEntity<DeletionResult> {
        val headers = HttpHeaders()
        headers.set(HttpHeaders.LOCATION, "$STATUS_PATH_PREFIX${operation.partitionId}$STATUS_PATH_SUFFIX")
        headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS.toString())
        // The contract types the operation as ResponseEntity<DeletionResult>; its 202 body is a DeletionStatus.
        val body: Any = status(operation)

        @Suppress("UNCHECKED_CAST")
        return ResponseEntity.status(HttpStatus.ACCEPTED).headers(headers).body(body) as ResponseEntity<DeletionResult>
    }

    private fun status(operation: DeletionOperation): DeletionStatus {
        val started = operation.startedAt.atOffset(ZoneOffset.UTC)
        val completed = operation.completedAt?.atOffset(ZoneOffset.UTC)
        return when (operation.state) {
            JooqPartitionDeletionOperations.RUNNING -> {
                DeletionStatus(operation.partitionId, OperationState.RUNNING, started)
            }

            JooqPartitionDeletionOperations.SUCCEEDED -> {
                val result = DeletionResult(operation.partitionId, DeletionResult.Status.DELETED, checkNotNull(completed))
                DeletionStatus(operation.partitionId, OperationState.SUCCEEDED, started, completed, result)
            }

            else -> {
                DeletionStatus(
                    operation.partitionId,
                    OperationState.FAILED,
                    started,
                    completed,
                    error = failure(operation.errorCode),
                )
            }
        }
    }

    /** The API-20 envelope of a stored error code (`<code>` or `<code>:<reason>`). */
    private fun failure(stored: String?): ErrorEnvelope {
        val code = stored?.substringBefore(':') ?: ClosedErrorCode.INTERNAL_ERROR.code
        val reason = stored?.substringAfter(':', "")?.takeIf { it.isNotEmpty() }
        val closed = ClosedErrorCode.entries.firstOrNull { it.code == code }
        val chain = code == CHAIN_CODE
        return ErrorEnvelope(
            type = URI.create(Problems.typeOf(code)),
            title = if (chain) chainCode().condition else closed?.title ?: ClosedErrorCode.INTERNAL_ERROR.title,
            status = if (chain) HttpStatus.CONFLICT.value() else closed?.httpStatus ?: HttpStatus.INTERNAL_SERVER_ERROR.value(),
            code = code,
            correlationId = MDC.get(CORRELATION_ID) ?: "http:${UUID.randomUUID()}",
            detail = reason,
        )
    }

    private fun caller(): EndUserPrincipal {
        val caller =
            requests.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
        if (caller?.tenant == null) throw RetentionRefusal(ClosedErrorCode.UNAUTHENTICATED)
        return caller
    }

    /**
     * [block] in a tenant transaction of its own on a separate thread, so no transaction of the request is joined and
     * the claim and the end of an operation commit when they are made. A failure is rethrown on the caller's thread.
     */
    private fun <T> inTenant(
        tenant: TenantId,
        block: () -> T,
    ): T {
        var outcome: Result<T>? = null
        val worker =
            Thread.ofVirtual().name("partition-deletion-tx").start {
                outcome = runCatching { scope.bind(tenant, scope.workloadPrincipal) { scope.transactions.inTransaction(block) } }
            }
        worker.join()
        return checkNotNull(outcome).getOrThrow()
    }

    @ExceptionHandler(MaxRetentionUnreadable::class)
    fun unreadable(failure: MaxRetentionUnreadable): ResponseEntity<String> {
        log.error("getMaxEvidenceRetention could not read the VP-43 settings", failure.cause)
        val code = ErrorCode.entries.single { it.code == CODE }
        val envelope =
            mapper.createObjectNode().apply {
                put("type", "https://errors.invoice.internal/${code.code}")
                put("title", TITLE)
                put("status", HttpStatus.SERVICE_UNAVAILABLE.value())
                put("correlationId", MDC.get(CORRELATION_ID) ?: "http:${UUID.randomUUID()}")
                put("code", code.code)
                put("severity", code.severity.name)
                put("disposition", checkNotNull(code.disposition) { "EVD-001 has no disposition" }.name)
                put("remediationOwner", code.owner)
            }
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(mapper.writeValueAsString(envelope))
    }

    companion object {
        /** The permission key that decides `deleteAtRetentionEnd` (INV-Q-10027): a key, never a role name. */
        const val REQUEST_KEY = "retention.delete.request"

        /** The `x-timeout` of `deleteAtRetentionEnd` in the pinned contract bundle (2000 ms). */
        const val CONTRACT_TIMEOUT_MILLIS = 2000L
        private val DECISION_BUDGET: Duration = Duration.ofMillis(CONTRACT_TIMEOUT_MILLIS)
        private const val RETRY_AFTER_SECONDS = 1
        private const val RUNNING_REASON = "RUNNING"
        private const val CHAIN_CODE = "DEP-007"
        private const val STATUS_PATH_PREFIX = "/internal/v1/evidence/partitions/"
        private const val STATUS_PATH_SUFFIX = "/deletion"
        private const val CODE = "EVD-001"
        private const val TITLE = "Maximum evidence retention cannot be determined"
        private const val CORRELATION_ID = "correlation_id"
        private val mapper = ObjectMapper()
        private val log: Logger = LoggerFactory.getLogger(RetentionController::class.java)

        private fun chainCode(): ErrorCode = ErrorCode.entries.single { it.code == CHAIN_CODE }

        /** The 409 envelope of a deletion stop: the DEP-007 block, else SRT-008 (SRT-007 while running) with the result. */
        fun conflictEnvelope(
            reason: String,
            chainVerificationFailed: Boolean,
            correlationId: String,
        ): tools.jackson.databind.node.ObjectNode =
            mapper.createObjectNode().apply {
                if (chainVerificationFailed) {
                    val code = chainCode()
                    put("type", Problems.typeOf(code.code))
                    put("title", code.condition)
                    put("code", code.code)
                    put("severity", code.severity.name)
                    code.disposition?.let { put("disposition", it.name) }
                    put("remediationOwner", code.owner)
                } else {
                    val closed = if (reason == RUNNING_REASON) ClosedErrorCode.IN_FLIGHT else ClosedErrorCode.STALE_AGGREGATE
                    put("type", Problems.typeOf(closed.code))
                    put("title", closed.title)
                    put("code", closed.code)
                    put("disposition", closed.disposition)
                    put("remediationOwner", closed.remediationOwner)
                    put("detail", reason)
                }
                put("status", HttpStatus.CONFLICT.value())
                put("correlationId", correlationId)
            }
    }
}
