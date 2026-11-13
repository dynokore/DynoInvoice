package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.evidence.application.lookup.AcknowledgementChainLookup
import com.dynokore.gst.evidence.application.lookup.ChainCursor
import com.dynokore.gst.evidence.application.lookup.ChainItem
import com.dynokore.gst.evidence.application.lookup.ChainItemKind
import com.dynokore.gst.evidence.application.lookup.ChainLookupResult
import com.dynokore.gst.evidence.contract.api.LookupApi
import com.dynokore.gst.evidence.contract.model.EvidenceChain
import com.dynokore.gst.evidence.contract.model.EvidenceChainItem
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** A refused lookup call: one closed service-runtime case, rendered once as the API-20 envelope. */
class LookupRefusal(
    val code: ClosedErrorCode,
) : RuntimeException(code.code)

/** ARC-19, CS-14: the one place a [LookupRefusal] becomes the API-20 envelope (SRT-001, -003, -004, -005, -010). */
@RestControllerAdvice(assignableTypes = [LookupController::class])
class LookupRefusalAdvice {
    @ExceptionHandler(LookupRefusal::class)
    fun refuse(refusal: LookupRefusal): ResponseEntity<String> {
        val problem =
            Problems.closed(
                refusal.code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
            )
        val headers = HttpHeaders()
        headers.set(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }
}

/**
 * The lookup operations of the evidence contract (TPL-08). An operation answers 501 until the task named for it in
 * `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * `getAcknowledgementChain` (INV-T-20577; INV-REQ-691): the permission is decided inside [AcknowledgementChainLookup]
 * through `AuthorityReplica.decideTenant`; this class reads no role name. Order: the caller's verified tenant (401),
 * the request against the contract (400, no store read), then the use case: Found 200, NotFound 404, Forbidden 403,
 * Incomplete 500 (never an empty 200).
 */
@RestController
open class LookupController(
    private val acknowledgementChain: AcknowledgementChainLookup,
    private val requests: ObjectProvider<HttpServletRequest>,
) : LookupApi {
    override fun getAcknowledgementChain(
        ackId: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<EvidenceChain> {
        val caller =
            requests.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
        val tenant = caller?.tenant ?: throw LookupRefusal(ClosedErrorCode.UNAUTHENTICATED)
        if (ackId.isBlank() || ackId.length > MAX_ACK_ID || ackId.any { it.isISOControl() }) {
            throw LookupRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        }
        if (limit !in 1..AcknowledgementChainLookup.MAX_LIMIT) throw LookupRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        val after = cursor?.let { PageCursor.decode(it, ackId) }
        val context =
            EventContext(
                tenant,
                EntityRef.TENANT_WIDE,
                null,
                null,
                ackId,
                null,
                CorrelationId(correlation()),
                CausationId(correlation()),
            )
        return when (
            val result =
                acknowledgementChain.lookup(
                    tenant,
                    Principal(caller.actor, PrincipalKind.HUMAN),
                    ackId,
                    limit,
                    after,
                    context,
                )
        ) {
            is ChainLookupResult.Found -> ResponseEntity.ok(chain(result))
            ChainLookupResult.NotFound -> throw LookupRefusal(ClosedErrorCode.NOT_FOUND)
            ChainLookupResult.Forbidden -> throw LookupRefusal(ClosedErrorCode.ACCESS_DENIED)
            ChainLookupResult.Incomplete -> throw LookupRefusal(ClosedErrorCode.INTERNAL_ERROR)
        }
    }

    private fun correlation(): String = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()

    private fun chain(found: ChainLookupResult.Found) =
        EvidenceChain(
            ackId = found.ackId,
            docUuid = found.docUuid,
            version = found.version,
            imported = false,
            items = found.items.map(::item),
            nextCursor = found.nextCursor?.let { PageCursor.encode(found.ackId, it) },
        )

    private fun item(item: ChainItem) =
        EvidenceChainItem(
            kind = EvidenceChainItem.Kind.valueOf(item.kind.name),
            recordedAt = item.recordedAt.atOffset(ZoneOffset.UTC),
            reference = item.reference,
            sequence = Math.toIntExact(item.sequence),
        )

    private companion object {
        const val MAX_ACK_ID = 100
    }
}

/** An opaque cursor: the Acknowledgement ID it was made for and the order key of the last item of the page. */
private object PageCursor {
    private const val SEPARATOR = '\u0000'
    private const val FIELDS = 4

    fun encode(
        ackId: String,
        key: ChainCursor,
    ): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                listOf(ackId, key.appliedAt.toString(), key.kind.name, key.seq.toString())
                    .joinToString(SEPARATOR.toString())
                    .toByteArray(StandardCharsets.UTF_8),
            )

    /** A cursor that does not decode, or was made for another Acknowledgement ID, is SRT-001. */
    fun decode(
        cursor: String,
        ackId: String,
    ): ChainCursor {
        val parts =
            runCatching { String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(SEPARATOR) }
                .getOrNull()
        val decoded =
            if (parts == null || parts.size != FIELDS || parts[0] != ackId) {
                null
            } else {
                runCatching {
                    ChainCursor(Instant.parse(parts[1]), ChainItemKind.valueOf(parts[2]), parts[3].toLong())
                }.getOrNull()
            }
        return decoded ?: throw LookupRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
    }
}
