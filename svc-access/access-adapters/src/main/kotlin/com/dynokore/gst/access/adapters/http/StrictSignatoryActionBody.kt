package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.contract.model.SignatoryActionRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.lang.reflect.Type

/**
 * The body of `recordSignatoryAction` has exactly the properties of the contract (`additionalProperties: false`). The
 * consent, the client entity and the actor are never request fields (INV-REQ-899): a body that names `consentRef`,
 * `clientRef` or any other property is a 400 before the use case runs, instead of being dropped silently by the
 * binder. The allowed names are the three properties of the contract's request schema.
 */
@ControllerAdvice
class StrictSignatoryActionBody(
    private val mapper: ObjectMapper,
) : RequestBodyAdviceAdapter() {
    override fun supports(
        methodParameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = targetType == SignatoryActionRequest::class.java

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): HttpInputMessage {
        val bytes = inputMessage.body.readAllBytes()
        val tree = runCatching { mapper.readTree(bytes) }.getOrNull()
        val unknown =
            tree
                ?.takeIf { it.isObject }
                ?.propertyNames()
                ?.filterNot { it in ALLOWED }
                .orEmpty()
        if (unknown.isNotEmpty()) {
            throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "the body has no property named ${unknown.first()}")
        }
        return object : HttpInputMessage {
            override fun getBody() = ByteArrayInputStream(bytes)

            override fun getHeaders() = inputMessage.headers
        }
    }

    private companion object {
        val ALLOWED: Set<String> =
            SignatoryActionRequest::class.java.declaredFields
                .map { it.name }
                .toSet()
    }
}
