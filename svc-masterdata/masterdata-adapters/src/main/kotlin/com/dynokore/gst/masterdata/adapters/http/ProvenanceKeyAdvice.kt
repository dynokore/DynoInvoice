package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.contract.model.ResolveCounterpartyRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Type

/**
 * INV-REQ-314, INV-REQ-1111, INV-Q-8423 A: a caller never sends `providedBy`, `capturedAt` or `provenance`, and
 * `MIGRATED` is set only by an applied import. The generated request type has no such property and ignores unknown
 * ones, so this advice reads the body of `resolveMasterDataGap` before it is bound and refuses it with 400 SRT-001 when
 * any JSON object in it, at any depth, holds one of those keys with any value (`null` included): the controller never
 * runs and nothing is stored. Keys are matched by exact equality. The same bytes go on to the converter otherwise.
 */
@ControllerAdvice
class ProvenanceKeyAdvice(
    private val mapper: ObjectMapper,
) : RequestBodyAdviceAdapter() {
    override fun supports(
        methodParameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = targetType == ResolveCounterpartyRequest::class.java

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): HttpInputMessage {
        val bytes = inputMessage.body.readAllBytes()
        // an unparseable body is refused by the converter, as any other malformed body
        val tree = runCatching { mapper.readTree(bytes) }.getOrNull()
        if (tree != null && carries(tree)) {
            throw ApiRefusal(
                ClosedErrorCode.CONTRACT_VIOLATION,
                "providedBy, capturedAt and provenance are set by the service and are not accepted",
            )
        }
        return object : HttpInputMessage {
            override fun getHeaders(): HttpHeaders = inputMessage.headers

            override fun getBody(): InputStream = ByteArrayInputStream(bytes)
        }
    }

    private fun carries(node: JsonNode): Boolean =
        when {
            node.isObject -> node.propertyNames().any { it in KEYS } || node.properties().any { carries(it.value) }
            node.isArray -> node.any { carries(it) }
            else -> false
        }

    private companion object {
        val KEYS = setOf("providedBy", "capturedAt", "provenance")
    }
}
