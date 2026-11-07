package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.contract.model.PendingIdentityTripleChange
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.MasterdataErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.util.UUID

/**
 * A refused request: the closed service-runtime case and, optionally, what the caller may be told (never a server
 * detail, API-22).
 */
class ApiRefusal(
    val code: ClosedErrorCode,
    val detail: String? = null,
) : RuntimeException("${code.code}: ${detail ?: code.title}")

/**
 * The repeat answer of a FAILED record (INV-Q-18032): the envelope stored with the record, with the status and headers
 * of its code; never a new envelope and never a second run.
 */
class StoredFailure(
    val code: ClosedErrorCode,
    val body: String,
) : RuntimeException("${code.code}: stored failure of a FAILED record", null, false, false)

/** A refusal with a masterdata catalogue code (MDM-005, answered 422), rendered by the service-runtime renderer. */
class MasterdataRefusal(
    val code: MasterdataErrorCode,
    val detail: String? = null,
) : RuntimeException("${code.code}: ${detail ?: code.title}")

/** The 202 answer of `configureIdentityTriple`, carried to [ApiRefusalAdvice] where the compiler types its body. */
class ProposalAccepted(
    val body: PendingIdentityTripleChange,
) : RuntimeException("202: the change is pending confirmation", null, false, false)

/** ARC-19, CS-14: the one place a refusal becomes the API-20 envelope. */
@RestControllerAdvice
class ApiRefusalAdvice {
    @ExceptionHandler(ApiRefusal::class)
    fun refuse(refusal: ApiRefusal): ResponseEntity<String> {
        val problem =
            Problems.render(
                refusal.code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
                refusal.detail,
            )
        val headers = HttpHeaders()
        problem.headers.forEach { (name, value) -> headers.add(name, value) }
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }

    @ExceptionHandler(ProposalAccepted::class)
    fun accepted(accepted: ProposalAccepted): ResponseEntity<PendingIdentityTripleChange> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted.body)

    @ExceptionHandler(MasterdataRefusal::class)
    fun refuse(refusal: MasterdataRefusal): ResponseEntity<String> {
        val problem =
            Problems.renderMasterdata(
                refusal.code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
                refusal.detail,
            )
        val headers = HttpHeaders()
        problem.headers.forEach { (name, value) -> headers.add(name, value) }
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }

    /**
     * Spring MVC refusals before or while binding (INV-Q-17119 A): the envelope with a fixed detail that never echoes
     * the body, a request value or the exception message (API-22). The catalogue holds no 415 or 406 row, so SRT-001
     * is the nearest existing code.
     */
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        ServletRequestBindingException::class,
        HttpMediaTypeNotSupportedException::class,
        HttpMediaTypeNotAcceptableException::class,
    )
    fun malformed(): ResponseEntity<String> = envelope(ClosedErrorCode.CONTRACT_VIOLATION, MALFORMED_DETAIL)

    /**
     * A body that fails bean validation (SRT-001), except an array over its published cap (SRT-014, 413, API-49): the
     * refusal is a 413 only when every violation is a `Size` violation of a list in [CAPPED_LISTS] holding more than
     * [BULK_CAP] items; anything else, or any further violation, stays 400. Nothing of the request is echoed.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun notValid(failure: MethodArgumentNotValidException): ResponseEntity<String> = validation(failure.fieldErrors)

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun notValidMethod(failure: HandlerMethodValidationException): ResponseEntity<String> =
        validation(failure.beanResults.flatMap { it.fieldErrors })

    private fun validation(errors: List<FieldError>): ResponseEntity<String> =
        if (errors.isNotEmpty() && errors.all { oversizedList(it) }) {
            envelope(ClosedErrorCode.REQUEST_TOO_LARGE, null)
        } else {
            envelope(ClosedErrorCode.CONTRACT_VIOLATION, MALFORMED_DETAIL)
        }

    private fun oversizedList(error: FieldError): Boolean =
        error.code == "Size" && error.field in CAPPED_LISTS &&
            ((error.rejectedValue as? Collection<*>)?.size ?: 0) > BULK_CAP

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun unknownPath(): ResponseEntity<String> = envelope(ClosedErrorCode.NOT_FOUND, null)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun wrongMethod(failure: HttpRequestMethodNotSupportedException): ResponseEntity<String> =
        envelope(
            ClosedErrorCode.METHOD_NOT_ALLOWED,
            null,
            failure.supportedHttpMethods?.joinToString(", ") { it.name() },
        )

    private fun envelope(
        code: ClosedErrorCode,
        detail: String?,
        allow: String? = null,
    ): ResponseEntity<String> {
        val problem =
            Problems.render(
                code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
                detail,
            )
        val headers = HttpHeaders()
        problem.headers.forEach { (name, value) -> headers.add(name, value) }
        allow?.let { headers.add(HttpHeaders.ALLOW, it) }
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }

    private companion object {
        const val BULK_CAP = 5000
        val CAPPED_LISTS = setOf("docUuids", "documents", "rows")
        const val MALFORMED_DETAIL = "The request does not match the published contract."
    }
}

/** Renders [StoredFailure]: the stored envelope with the status and headers of its code (INV-Q-18032). */
@RestControllerAdvice
class StoredFailureAdvice {
    @ExceptionHandler(StoredFailure::class)
    fun stored(failure: StoredFailure): ResponseEntity<String> {
        val problem = Problems.render(failure.code, UUID.randomUUID().toString())
        val headers = HttpHeaders()
        problem.headers.forEach { (name, value) -> headers.add(name, value) }
        return ResponseEntity.status(problem.status).headers(headers).body(failure.body)
    }
}
