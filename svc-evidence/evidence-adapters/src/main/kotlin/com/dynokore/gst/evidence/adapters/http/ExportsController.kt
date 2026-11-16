package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.evidence.application.export.ExportJobs
import com.dynokore.gst.evidence.application.export.ExportScope
import com.dynokore.gst.evidence.application.export.ExportState
import com.dynokore.gst.evidence.contract.api.ExportsApi
import com.dynokore.gst.evidence.contract.model.ExportManifest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import java.util.UUID

/** A refused export read: a closed service-runtime case (401, 403), rendered once as the API-20 envelope. */
class ExportsRefusal(
    val code: ClosedErrorCode,
) : RuntimeException(code.code)

/** The bound tenant has no READY export: 409 with the catalogue code EVD-002 and the state in the detail. */
class ExportNotReady(
    val state: String,
) : RuntimeException("EVD-002: $state")

/** ARC-19, CS-14: the one place an [ExportsRefusal] or [ExportNotReady] becomes the API-20 envelope. */
@RestControllerAdvice(assignableTypes = [ExportsController::class])
class ExportsRefusalAdvice {
    @ExceptionHandler(ExportsRefusal::class)
    fun refuse(refusal: ExportsRefusal): ResponseEntity<String> {
        val problem = Problems.closed(refusal.code, correlation())
        val headers = HttpHeaders()
        headers.set(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }

    @ExceptionHandler(ExportNotReady::class)
    fun notReady(refusal: ExportNotReady): ResponseEntity<String> {
        val code = ErrorCode.entries.single { it.code == NOT_READY }
        val envelope =
            mapper.createObjectNode().apply {
                put("type", "https://errors.invoice.internal/${code.code}")
                put("title", code.condition)
                put("status", HttpStatus.CONFLICT.value())
                put("detail", "The export is not ready: ${refusal.state}")
                put("correlationId", correlation())
                put("code", code.code)
                put("severity", code.severity.name)
                code.disposition?.let { put("disposition", it.name) }
                put("remediationOwner", code.owner)
            }
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(mapper.writeValueAsString(envelope))
    }

    private fun correlation() = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()

    private companion object {
        const val NOT_READY = "EVD-002"
        val mapper = ObjectMapper()
    }
}

/**
 * The exports operations of the evidence contract (TPL-08). An operation answers 501 until the task named for it in
 * `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * `getEvidenceExport` (INV-T-22957; INV-REQ-877): the tenant is the bound caller's, never the query value alone
 * (OWASP ASVS V4.1.1). No bound tenant answers 401; a `tenantId` that differs from it answers 403 SRT-004 and nothing
 * is read. The newest TENANT-scope job answers 200 with its manifest when READY; a FAILED, REQUESTED or absent job
 * answers 409 EVD-002 with the state (FAILED, REQUESTED, none) in the detail. This class reads no role name.
 */
@RestController
open class ExportsController(
    private val jobs: ExportJobs,
    private val requests: ObjectProvider<HttpServletRequest>,
) : ExportsApi {
    override fun getEvidenceExport(tenantId: UUID): ResponseEntity<ExportManifest> {
        val tenant = boundTenant(tenantId)
        val job = jobs.findLatest(tenant, ExportScope.TENANT)
        if (job == null || job.state != ExportState.READY) throw ExportNotReady(job?.state?.name ?: "none")
        return ResponseEntity.ok(
            ExportManifest(
                tenantId = tenant.value,
                kind = ExportManifest.Kind.OFFBOARDING,
                generatedAt =
                    checkNotNull(
                        job.finishedAt,
                    ) { "a READY export job carries finished_at" }.atOffset(ZoneOffset.UTC),
                manifestUri = checkNotNull(job.manifestRef) { "a READY export job carries manifest_uri" },
                itemCount = checkNotNull(job.itemCount) { "a READY export job carries item_count" },
            ),
        )
    }

    /** The bound tenant: 401 when none, 403 SRT-004 when the query value is another tenant (ASVS V4.1.1). */
    private fun boundTenant(tenantId: UUID): TenantId {
        val caller =
            requests.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
        val tenant = caller?.tenant ?: throw ExportsRefusal(ClosedErrorCode.UNAUTHENTICATED)
        return tenant.also { if (it.value != tenantId) throw ExportsRefusal(ClosedErrorCode.ACCESS_DENIED) }
    }
}
