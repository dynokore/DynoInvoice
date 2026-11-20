package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.evidence.application.cases.CaseAuthorizer
import com.dynokore.gst.evidence.application.cases.CasePermissions
import com.dynokore.gst.evidence.application.cases.CaseService
import com.dynokore.gst.evidence.application.cases.CloseResult
import com.dynokore.gst.evidence.application.cases.OpenPrivacyCaseCommand
import com.dynokore.gst.evidence.application.cases.OpenResult
import com.dynokore.gst.evidence.application.cases.PrivacyCaseService
import com.dynokore.gst.evidence.application.cases.RecordSecurityCaseCommand
import com.dynokore.gst.evidence.contract.api.PrivacyApi
import com.dynokore.gst.evidence.contract.model.ClosePrivacyCaseRequest
import com.dynokore.gst.evidence.contract.model.OpenPrivacyCaseRequest
import com.dynokore.gst.evidence.contract.model.PrivacyCase
import com.dynokore.gst.evidence.contract.model.RecordSecurityCaseRequest
import com.dynokore.gst.evidence.contract.model.SecurityCase
import com.dynokore.gst.evidence.domain.cases.CaseOutcome
import com.dynokore.gst.evidence.domain.cases.RequestKind
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.IdempotencyOutcome
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/**
 * The privacy operations of the evidence contract (TPL-08). An operation answers 501 until the task named for it in
 * `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * recordSecurityCase and getCase (INV-T-11694; INV-REQ-880, INV-REQ-1403): the permission is decided per request by
 * [CaseAuthorizer] over `AuthorityReplica.decideTenant` (403 SRT-004, nothing stored or read). `recordSecurityCase`
 * runs as `tx.inTransaction { idempotency.once(...) { CaseService.recordSecurityCase } }` (API-15, TPL-04): a repeat of
 * the key and body answers 200 with the stored body, the key with another body 422 SRT-009, a key in flight 409
 * SRT-007. `openPrivacyCase` and `closePrivacyCase` (INV-T-6842; INV-REQ-1403) check `privacy-request.handle` first,
 * then run the same transaction and replay shape; the refusals of the use cases are 409 and 422 envelopes
 * ([PrivacyRefusalAdvice]). `getCase` answers 404 SRT-005 for an unknown case and for a case of another tenant alike (API-24).
 */
@RestController
@Suppress("LongParameterList") // the use case, its ports and the request context; one controller per tag
open class PrivacyController(
    private val cases: CaseService,
    private val privacy: PrivacyCaseService,
    private val clock: Clock,
    private val authorizer: CaseAuthorizer,
    private val idempotency: IdempotencyStore,
    private val transactions: TransactionRunner,
    private val requests: ObjectProvider<HttpServletRequest>,
    private val mapper: ObjectMapper,
) : PrivacyApi {
    override fun recordSecurityCase(
        idempotencyKey: UUID,
        recordSecurityCaseRequest: RecordSecurityCaseRequest,
    ): ResponseEntity<SecurityCase> {
        val caller = caller()
        val tenant = checkNotNull(caller.tenant)
        val principal = Principal(caller.actor, PrincipalKind.HUMAN)
        // the permission first: a replay of an earlier answer never outlives the grant (fail closed)
        authorizer.require(tenant, principal, CasePermissions.SECURITY_CASE_RECORD)
        val command =
            RecordSecurityCaseCommand(
                recordSecurityCaseRequest.reviewReference,
                recordSecurityCaseRequest.auditLogEntryIds,
                recordSecurityCaseRequest.outcome,
                recordSecurityCaseRequest.personalDataExposed,
            )
        val fingerprint = fingerprint(recordSecurityCaseRequest)
        val outcome =
            transactions.inTransaction {
                idempotency.once(tenant, caller.actor, idempotencyKey, fingerprint) {
                    val record = cases.recordSecurityCase(tenant, principal, command)
                    StoredResponse(
                        HttpStatus.CREATED.value(),
                        mapper.writeValueAsString(CaseResponses.securityCase(record)),
                    )
                }
            }
        return when (outcome) {
            is IdempotencyOutcome.Fresh -> reply(HttpStatus.CREATED, outcome.response)
            is IdempotencyOutcome.Replay -> reply(HttpStatus.OK, outcome.response)
            IdempotencyOutcome.InFlight -> throw CaseRefusal(ClosedErrorCode.IN_FLIGHT)
            IdempotencyOutcome.DifferentRequest -> throw CaseRefusal(ClosedErrorCode.DIFFERENT_REQUEST)
        }
    }

    override fun openPrivacyCase(
        idempotencyKey: UUID,
        openPrivacyCaseRequest: OpenPrivacyCaseRequest,
    ): ResponseEntity<PrivacyCase> {
        val caller = caller()
        val tenant = checkNotNull(caller.tenant)
        val principal = Principal(caller.actor, PrincipalKind.HUMAN)
        authorizer.require(tenant, principal, CasePermissions.PRIVACY_REQUEST_HANDLE)
        val command =
            OpenPrivacyCaseCommand(
                openPrivacyCaseRequest.subjectRef,
                RequestKind.valueOf(openPrivacyCaseRequest.requestKind.name),
                openPrivacyCaseRequest.receivedOn,
                openPrivacyCaseRequest.requestReference,
            )
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        val outcome =
            transactions.inTransaction {
                idempotency.once(tenant, caller.actor, idempotencyKey, fingerprint(OPEN_PATH, openPrivacyCaseRequest)) {
                    when (val result = privacy.open(tenant, principal, command, today)) {
                        is OpenResult.Opened -> stored(HttpStatus.CREATED, result.record)
                        is OpenResult.Replayed -> stored(HttpStatus.OK, result.record)
                    }
                }
            }
        return answer(outcome, HttpStatus.OK)
    }

    override fun closePrivacyCase(
        caseId: UUID,
        idempotencyKey: UUID,
        closePrivacyCaseRequest: ClosePrivacyCaseRequest,
    ): ResponseEntity<PrivacyCase> {
        val caller = caller()
        val tenant = checkNotNull(caller.tenant)
        val principal = Principal(caller.actor, PrincipalKind.HUMAN)
        authorizer.require(tenant, principal, CasePermissions.PRIVACY_REQUEST_HANDLE)
        val path = CLOSE_PATH.replace("{caseId}", caseId.toString())
        val outcome =
            transactions.inTransaction {
                idempotency.once(tenant, caller.actor, idempotencyKey, fingerprint(path, closePrivacyCaseRequest)) {
                    val result =
                        privacy.close(
                            tenant,
                            principal,
                            caseId,
                            CaseOutcome.valueOf(closePrivacyCaseRequest.outcome.name),
                            closePrivacyCaseRequest.basis,
                        )
                    when (result) {
                        is CloseResult.Closed -> stored(HttpStatus.OK, result.record)
                        is CloseResult.Replayed -> stored(HttpStatus.OK, result.record)
                        CloseResult.NotFound -> throw CaseRefusal(ClosedErrorCode.NOT_FOUND)
                    }
                }
            }
        return answer(outcome, HttpStatus.OK)
    }

    private fun stored(
        status: HttpStatus,
        record: com.dynokore.gst.evidence.domain.cases.CaseRecord,
    ): StoredResponse = StoredResponse(status.value(), mapper.writeValueAsString(CaseResponses.privacyCase(record)))

    /** A fresh answer keeps the status it was stored with; a replay of the key answers [replayStatus]. */
    private fun answer(
        outcome: IdempotencyOutcome,
        replayStatus: HttpStatus,
    ): ResponseEntity<PrivacyCase> =
        when (outcome) {
            is IdempotencyOutcome.Fresh -> privacyReply(HttpStatus.valueOf(outcome.response.status), outcome.response)
            is IdempotencyOutcome.Replay -> privacyReply(replayStatus, outcome.response)
            IdempotencyOutcome.InFlight -> throw CaseRefusal(ClosedErrorCode.IN_FLIGHT)
            IdempotencyOutcome.DifferentRequest -> throw CaseRefusal(ClosedErrorCode.DIFFERENT_REQUEST)
        }

    @Suppress("UNCHECKED_CAST") // the contract types the body as PrivacyCase; it holds only the members the case has
    private fun privacyReply(
        status: HttpStatus,
        stored: StoredResponse,
    ): ResponseEntity<PrivacyCase> =
        ResponseEntity.status(status).body(mapper.readValue(stored.body, Map::class.java)) as ResponseEntity<PrivacyCase>

    override fun getCase(caseId: UUID): ResponseEntity<PrivacyCase> {
        val caller = caller()
        val tenant: TenantId = checkNotNull(caller.tenant)
        val principal = Principal(caller.actor, PrincipalKind.HUMAN)
        val record =
            transactions.inTransaction { cases.getCase(tenant, principal, caseId) }
                ?: throw CaseRefusal(ClosedErrorCode.NOT_FOUND)
        // The contract types the operation as ResponseEntity<PrivacyCase>; the body holds only the members the case
        // has (a null member would be a property of its own).
        val body: Any = CaseResponses.privacyCase(record)

        @Suppress("UNCHECKED_CAST")
        return ResponseEntity.ok(body) as ResponseEntity<PrivacyCase>
    }

    private fun reply(
        status: HttpStatus,
        stored: StoredResponse,
    ): ResponseEntity<SecurityCase> =
        ResponseEntity.status(status).body(mapper.readValue(stored.body, SecurityCase::class.java))

    /** API-15: the fingerprint of the method, path and body; the body is the request as the contract names it. */
    private fun fingerprint(request: RecordSecurityCaseRequest): String = fingerprint(RECORD_PATH, request)

    private fun fingerprint(
        path: String,
        request: Any,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("POST $path\n".toByteArray(StandardCharsets.UTF_8))
        digest.update(mapper.writeValueAsBytes(request))
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun caller(): EndUserPrincipal {
        val caller =
            requests.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
        return caller?.takeIf { it.tenant != null } ?: throw CaseRefusal(ClosedErrorCode.UNAUTHENTICATED)
    }

    private companion object {
        const val RECORD_PATH = "/internal/v1/evidence/security-cases"
        const val OPEN_PATH = "/internal/v1/evidence/cases"
        const val CLOSE_PATH = "/internal/v1/evidence/cases/{caseId}/closure"
    }
}
