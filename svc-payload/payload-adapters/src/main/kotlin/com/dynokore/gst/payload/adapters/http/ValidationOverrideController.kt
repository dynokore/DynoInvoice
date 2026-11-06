package com.dynokore.gst.payload.adapters.http

import com.dynokore.gst.payload.adapters.clients.AccessReleaseDecisions
import com.dynokore.gst.payload.application.override.ApproveOverride
import com.dynokore.gst.payload.application.override.OverrideResult
import com.dynokore.gst.payload.application.override.ReadOverrides
import com.dynokore.gst.payload.application.override.RequestOverride
import com.dynokore.gst.payload.application.override.StoredOverride
import com.dynokore.gst.payload.contract.api.ValidationApi
import com.dynokore.gst.payload.contract.model.OverrideValidationRequest
import com.dynokore.gst.payload.contract.model.PendingValidationOverride
import com.dynokore.gst.payload.contract.model.ValidationOverride
import com.dynokore.gst.payload.contract.model.ValidationOverrideEntry
import com.dynokore.gst.payload.contract.model.ValidationOverrideList
import com.dynokore.gst.payload.domain.override.OverrideStatus
import com.dynokore.gst.payload.domain.override.ReleaseOutcome
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * The three override operations of the payload contract (INV-REQ-330, INV-REQ-500, INV-REQ-1293) over the use cases of
 * `payload-application`. The acting user is the `sub` of the verified end-user principal, never a request field. Every
 * refusal is the API-20 envelope: the SRT cases of service-runtime, and PAY-017 for a code that is not an OVERRIDABLE
 * code of the version (INV-AC-1584). The controller is the transaction boundary (ARC-21): each use case runs in one
 * `TransactionRunner.inTransaction`, so the stored row and the audit entry commit together or not at all. A refused release (403 SRT-004) carries the extension member `reasonCode` only
 * when the use case reports a reason; the 403 of a missing permission carries none (INV-Q-17227, INV-Q-17229).
 */
@RestController
open class ValidationOverrideController(
    private val request: RequestOverride,
    private val approve: ApproveOverride,
    private val read: ReadOverrides,
    private val transactions: TransactionRunner,
    private val http: ObjectProvider<HttpServletRequest>,
) : ValidationApi {
    override fun overrideValidation(
        docUuid: UUID,
        version: Int,
        overrideValidationRequest: OverrideValidationRequest,
    ): ResponseEntity<ValidationOverride> {
        val principal = principalOrNull() ?: return OverrideHttp.problem(ClosedErrorCode.UNAUTHENTICATED)
        return when (
            val result =
                transactions.inTransaction {
                    request.execute(
                        principal.actor,
                        docUuid,
                        version,
                        overrideValidationRequest.code,
                        overrideValidationRequest.reason,
                    )
                }
        ) {
            is OverrideResult.Released -> created(result.entry)
            is OverrideResult.AwaitsSecond -> accepted(result.entry)
            else -> OverrideHttp.refusal(result, docUuid)
        }
    }

    override fun approveValidationOverride(
        docUuid: UUID,
        version: Int,
        code: String,
    ): ResponseEntity<ValidationOverride> {
        val principal = principalOrNull() ?: return OverrideHttp.problem(ClosedErrorCode.UNAUTHENTICATED)
        val result = transactions.inTransaction { approve.execute(principal.actor, docUuid, version, code) }
        return when (result) {
            is OverrideResult.Released -> created(result.entry)
            else -> OverrideHttp.refusal(result, docUuid)
        }
    }

    @Suppress("ReturnCount")
    override fun getValidationOverrides(
        docUuid: UUID,
        version: Int,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<ValidationOverrideList> {
        val principal = principalOrNull() ?: return OverrideHttp.problem(ClosedErrorCode.UNAUTHENTICATED)
        val after = cursor?.let(OverrideHttp::decodeCursor)
        if (cursor != null && after == null) return OverrideHttp.problem(ClosedErrorCode.CONTRACT_VIOLATION)
        // One more than the page tells whether a next page exists.
        val result = transactions.inTransaction { read.execute(principal.actor, docUuid, version, after, limit + 1) }
        return when (result) {
            is OverrideResult.Listed -> ResponseEntity.ok(OverrideHttp.page(result.entries, limit))
            else -> OverrideHttp.refusal(result, docUuid)
        }
    }

    private fun principalOrNull(): EndUserPrincipal? =
        http.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal

    private fun created(entry: StoredOverride): ResponseEntity<ValidationOverride> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            ValidationOverride(
                entry.docUuid,
                entry.version,
                entry.code,
                entry.reason,
                checkNotNull(entry.releasedBy) { "a released override names its releasing approver" },
                OverrideHttp.utc(checkNotNull(entry.releasedAt) { "a released override has a release time" }),
            ),
        )

    /** The 202 body is a [PendingValidationOverride]; the generated signature names the 201 type only. */
    @Suppress("UNCHECKED_CAST")
    private fun accepted(entry: StoredOverride): ResponseEntity<ValidationOverride> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(
            PendingValidationOverride(
                entry.docUuid,
                entry.version,
                entry.code,
                entry.reason,
                entry.requestedBy,
                OverrideHttp.utc(entry.requestedAt),
                PendingValidationOverride.Status.PENDING,
            ),
        ) as ResponseEntity<ValidationOverride>
}

/** The mapping of results to pages and API-20 envelopes, so that the controller keeps to its three operations. */
internal object OverrideHttp {
    fun page(
        entries: List<StoredOverride>,
        limit: Int,
    ): ValidationOverrideList {
        val shown = entries.take(limit)
        val next = if (entries.size > limit) shown.last().let { encodeCursor(it.requestedAt, it.code) } else null
        return ValidationOverrideList(shown.map(::entryOf), next)
    }

    private fun entryOf(row: StoredOverride) =
        ValidationOverrideEntry(
            row.code,
            row.reason,
            ValidationOverrideEntry.Status.valueOf(row.status.name),
            row.requestedBy,
            utc(row.requestedAt),
            row.releasedBy.takeIf { row.status == OverrideStatus.RELEASED },
            row.releasedAt?.let(::utc),
        )

    /** Every non-success result is one envelope; a result that cannot reach this adapter fails closed as 403. */
    fun <T : Any> refusal(
        result: OverrideResult,
        docUuid: UUID,
    ): ResponseEntity<T> =
        when (result) {
            OverrideResult.BadRequest -> {
                problem(ClosedErrorCode.CONTRACT_VIOLATION)
            }

            OverrideResult.NotFound -> {
                problem(ClosedErrorCode.NOT_FOUND)
            }

            OverrideResult.NotOverridable -> {
                notOverridable(docUuid)
            }

            is OverrideResult.Refused -> {
                problem(
                    ClosedErrorCode.ACCESS_DENIED,
                    reasonCode = reasonCodeOf(result.reasonCode),
                )
            }

            else -> {
                problem(ClosedErrorCode.ACCESS_DENIED)
            }
        }

    /** Exact string equality; any other code is NOT_OVERRIDABLE (INV-Q-17227). */
    private fun reasonCodeOf(code: String): String = if (code in REASON_CODES) code else ReleaseOutcome.NOT_OVERRIDABLE

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> problem(
        code: ClosedErrorCode,
        reasonCode: String? = null,
    ): ResponseEntity<T> {
        val rendered = Problems.render(code, correlationId())
        val body =
            if (reasonCode == null) {
                rendered.body
            } else {
                (MAPPER.readTree(rendered.body) as ObjectNode).put("reasonCode", reasonCode).toString()
            }
        return ResponseEntity
            .status(rendered.status)
            .headers(headersOf(rendered.headers))
            .body(body) as ResponseEntity<T>
    }

    /** 422 PAY-017 (catalogue: BLOCK, MANUAL, Tax operations); the code is not in service-runtime's closed set. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> notOverridable(docUuid: UUID): ResponseEntity<T> {
        val body =
            MAPPER
                .createObjectNode()
                .apply {
                    put("type", Problems.typeOf(PAY_017))
                    put("title", "Override refused: the code is not an overridable blocking code of this version")
                    put("status", HttpStatus.UNPROCESSABLE_CONTENT.value())
                    put("correlationId", correlationId())
                    put("code", PAY_017)
                    put("severity", "BLOCK")
                    put("disposition", "MANUAL")
                    put("remediationOwner", "Tax operations")
                    put("docUuid", docUuid.toString())
                }.toString()
        return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_CONTENT)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .body(body) as ResponseEntity<T>
    }

    private fun headersOf(map: Map<String, String>): HttpHeaders =
        HttpHeaders().also { h -> map.forEach { (k, v) -> h.add(k, v) } }

    private fun correlationId(): String =
        CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()

    fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)

    private fun encodeCursor(
        at: Instant,
        code: String,
    ): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$at|$code".toByteArray(StandardCharsets.UTF_8))

    /** The position of a cursor, or nothing when it is not one this operation issued. */
    fun decodeCursor(cursor: String): Pair<Instant, String>? =
        runCatching {
            val text = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
            val at = Instant.parse(text.substringBefore('|'))
            val code = text.substringAfter('|', "")
            if (code.isEmpty()) null else at to code
        }.getOrNull()

    private const val PAY_017 = "PAY-017"

    /** The four values of the extension member `reasonCode`, compared by string equality (INV-Q-17227, INV-Q-17335 A). */
    private val REASON_CODES =
        setOf(
            ReleaseOutcome.NOT_OVERRIDABLE,
            "SAME_PERSON_TWICE",
            "NO_SOLE_OPERATOR_ACK",
            AccessReleaseDecisions.ACCESS_DECISION_UNAVAILABLE,
        )
    private val MAPPER: JsonMapper = JsonMapper.builder().build()
}
