package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.application.DuplicateEntityRef
import com.dynokore.gst.transport.contract.model.SetConnectionEntitiesRequest
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Type

/**
 * The request model holds `entityRefs` as a set, so a repeated ref would vanish while the body is bound. This reads the
 * raw array first and refuses a repeat as a validation error (`uniqueItems` of the contract; the use case refuses it
 * too).
 * Anything that does not parse is left to the message converter, which refuses it as a contract violation.
 */
@ControllerAdvice(assignableTypes = [AdministrationController::class])
class DuplicateEntityRefAdvice : RequestBodyAdviceAdapter() {
    private val mapper = JsonMapper.builder().build()

    override fun supports(
        methodParameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = targetType == SetConnectionEntitiesRequest::class.java

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): HttpInputMessage {
        val bytes = inputMessage.body.readAllBytes()
        val refs: List<String> =
            try {
                val node = mapper.readTree(bytes)?.get("entityRefs")
                if (node != null && node.isArray) node.toList().map { it.asString() } else emptyList()
            } catch (_: JacksonException) {
                emptyList()
            }
        refs
            .groupBy { it }
            .entries
            .firstOrNull { it.value.size > 1 }
            ?.let { throw DuplicateEntityRef(it.key) }
        return object : HttpInputMessage {
            override fun getBody(): InputStream = ByteArrayInputStream(bytes)

            override fun getHeaders() = inputMessage.headers
        }
    }
}
