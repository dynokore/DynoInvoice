package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

/**
 * A refusal whose wire code is a row of the pinned gst-catalogue (MDM-016, MDM-017, MDM-018; INV-T-18947), answered at
 * [status] in the API-20 envelope. The code is the catalogue's own [ErrorCode], never a string typed here (CS-14), so a
 * code the pinned catalogue does not carry does not compile; [CatalogueEnvelope] renders it.
 */
class CatalogueRefusal(
    val code: ErrorCode,
    val status: Int,
    val detail: String? = null,
) : RuntimeException("${code.code}: ${detail ?: code.condition}")

/** ARC-19, CS-14: renders [CatalogueRefusal] and the one unreadable body that is a catalogue refusal. */
@RestControllerAdvice
class CatalogueRefusalAdvice {
    @ExceptionHandler(CatalogueRefusal::class)
    fun refuse(refusal: CatalogueRefusal): ResponseEntity<String> =
        ResponseEntity
            .status(refusal.status)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .body(
                CatalogueEnvelope.render(
                    refusal,
                    CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
                ),
            )

    /**
     * An unreadable body is SRT-001, except a `createConnection` body whose `environmentTag` is not exactly one of the
     * two members of the closed enum: that is 400 MDM-016 and nothing is stored (INV-REQ-834, INV-T-18947). The test is
     * mechanical: the binding failure's own path ends at the property `environmentTag`; the body is never echoed.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(failure: HttpMessageNotReadableException): ResponseEntity<String> =
        if (UnknownEnvironmentTagBody.matches(failure)) {
            refuse(CatalogueRefusal(ErrorCode.MDM_016, HttpStatus.BAD_REQUEST.value(), TAG_DETAIL))
        } else {
            ApiRefusalAdvice().refuse(ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, MALFORMED_DETAIL))
        }

    private companion object {
        const val TAG_DETAIL = "environmentTag must be exactly PRODUCTION or SANDBOX."
        const val MALFORMED_DETAIL = "The request does not match the published contract."
    }
}
