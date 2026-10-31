package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.evidence.contract.api.RetentionApi
import com.dynokore.gst.evidence.contract.model.MaxEvidenceRetention
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The retention operations of the evidence contract (TPL-08). An operation answers 501 until the task named for it in
 * `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * getMaxEvidenceRetention (INV-T-6540; INV-REQ-3767): the caller check (`x-sync-edge: control-plane-svc->evidence-svc`)
 * is service-runtime's, before this method. The figure is read in a platform transaction under the service's own
 * workload principal, so no tenant is bound and none is read. When the read fails the answer is 503 with the API-20
 * envelope of the catalogue code `EVD-001` and no figure (INV-Q-12582 A; API-22: a server error carries no detail).
 */
@RestController
open class RetentionController(
    private val maxRetention: PlatformMaxRetention,
) : RetentionApi {
    override fun getMaxEvidenceRetention(): ResponseEntity<MaxEvidenceRetention> {
        val duration = maxRetention.read { it.duration() }
        return ResponseEntity.ok(MaxEvidenceRetention(duration))
    }

    @ExceptionHandler(RuntimeException::class)
    fun unreadable(failure: RuntimeException): ResponseEntity<String> {
        log.error("getMaxEvidenceRetention could not read the VP-43 settings", failure)
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

    private companion object {
        const val CODE = "EVD-001"
        const val TITLE = "Maximum evidence retention cannot be determined"
        const val CORRELATION_ID = "correlation_id"
        val mapper = ObjectMapper()
        val log = LoggerFactory.getLogger(RetentionController::class.java)
    }
}
