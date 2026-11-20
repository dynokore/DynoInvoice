package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.evidence.application.cases.CloseConflict
import com.dynokore.gst.evidence.application.cases.NotAPrivacyCase
import com.dynokore.gst.evidence.application.cases.ReceivedInFuture
import com.dynokore.gst.evidence.application.cases.ReferenceConflict
import com.dynokore.gst.evidence.contract.model.ClosePrivacyCaseRequest
import com.dynokore.gst.evidence.contract.model.OpenPrivacyCaseRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.core.MethodParameter
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.lang.reflect.Type
import java.util.UUID

/**
 * The refusals of `openPrivacyCase` and `closePrivacyCase` that the evidence contract names by status only
 * (`x-error-codes: []`): 409 for a requestReference held with other data, a case that is not a privacy case and a close
 * with another outcome or basis; 422 for a `receivedOn` after today. No catalogue code applies, so the API-20 envelope
 * carries `type` `about:blank` and no `code`; the detail is fixed text and never a value the caller sent.
 */
@RestControllerAdvice(assignableTypes = [PrivacyController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class PrivacyRefusalAdvice(
    private val mapper: ObjectMapper,
) {
    @ExceptionHandler(ReferenceConflict::class)
    fun referenceConflict(): ResponseEntity<String> =
        answer(HttpStatus.CONFLICT, "The requestReference is held by a case with other data")

    @ExceptionHandler(NotAPrivacyCase::class)
    fun notAPrivacyCase(): ResponseEntity<String> =
        answer(HttpStatus.CONFLICT, "The case was not opened as a privacy request")

    @ExceptionHandler(CloseConflict::class)
    fun closeConflict(): ResponseEntity<String> =
        answer(HttpStatus.CONFLICT, "The case is closed with another outcome or basis")

    @ExceptionHandler(ReceivedInFuture::class)
    fun receivedInFuture(): ResponseEntity<String> =
        answer(HttpStatus.valueOf(UNPROCESSABLE_CONTENT), "receivedOn is later than the day of the call")

    private companion object {
        const val UNPROCESSABLE_CONTENT = 422
    }

    private fun answer(
        status: HttpStatus,
        detail: String,
    ): ResponseEntity<String> {
        val body = mapper.createObjectNode()
        body.put("type", "about:blank")
        body.put("title", status.reasonPhrase)
        body.put("status", status.value())
        body.put("detail", detail)
        body.put("correlationId", CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString())
        return ResponseEntity
            .status(status)
            .header(HttpHeaders.CONTENT_TYPE, "application/problem+json")
            .body(mapper.writeValueAsString(body))
    }
}

/**
 * `additionalProperties: false` of `OpenPrivacyCaseRequest` and `ClosePrivacyCaseRequest` (the caller supplies no
 * `responseDeadline`, no period and no status; INV-REQ-1403). The generated model ignores a member it does not know,
 * so the body is read once here and any member that is not a field of the model is refused as 400 SRT-001 before the
 * use case runs. The known names are those the model serialises back, not a list kept here; every member of both
 * models is required.
 */
@RestControllerAdvice(assignableTypes = [PrivacyController::class])
class PrivacyBodyMembersAdvice(
    private val mapper: ObjectMapper,
) : RequestBodyAdviceAdapter() {
    override fun supports(
        methodParameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = targetType == OpenPrivacyCaseRequest::class.java || targetType == ClosePrivacyCaseRequest::class.java

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): HttpInputMessage {
        val bytes = inputMessage.body.readAllBytes()
        val sent = runCatching { mapper.readTree(bytes) }.getOrNull()
        val model = runCatching { mapper.readValue(bytes, targetType as Class<*>) }.getOrNull()
        if (sent != null && sent.isObject && model != null) {
            val known = mapper.valueToTree<tools.jackson.databind.JsonNode>(model).propertyNames().toSet()
            if (sent.propertyNames().any { it !in known }) {
                throw CaseRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "the request body holds an unknown member")
            }
        }
        return object : HttpInputMessage {
            override fun getBody() = ByteArrayInputStream(bytes)

            override fun getHeaders() = inputMessage.headers
        }
    }
}
