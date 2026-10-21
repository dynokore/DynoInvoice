package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.contract.model.ConfigureIdentityTripleRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Type

/**
 * INV-Q-8123 A: the generated [ConfigureIdentityTripleRequest] cannot tell an absent `secondConfirmation` from `null`,
 * so this advice reads the body of the proposal before it is bound and refuses it when the JSON object holds the key
 * with any value (`null` and `{}` included): 400 SRT-001, and the controller never runs, so nothing is recorded. The
 * key is matched by exact equality. The same bytes go on to the converter when the key is absent.
 */
@ControllerAdvice
class SecondConfirmationKeyAdvice(
    private val mapper: ObjectMapper,
) : RequestBodyAdviceAdapter() {
    override fun supports(
        methodParameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean = targetType == ConfigureIdentityTripleRequest::class.java

    override fun beforeBodyRead(
        inputMessage: HttpInputMessage,
        parameter: MethodParameter,
        targetType: Type,
        converterType: Class<out HttpMessageConverter<*>>,
    ): HttpInputMessage {
        val bytes = inputMessage.body.readAllBytes()
        // an unparseable body is refused by the converter, as any other malformed body
        val carried = runCatching { mapper.readTree(bytes)?.has(KEY) }.getOrNull() == true
        if (carried) {
            throw ApiRefusal(
                ClosedErrorCode.CONTRACT_VIOLATION,
                "secondConfirmation is not accepted: the confirmation is a separate call",
            )
        }
        return object : HttpInputMessage {
            override fun getHeaders(): HttpHeaders = inputMessage.headers

            override fun getBody(): InputStream = ByteArrayInputStream(bytes)
        }
    }

    private companion object {
        const val KEY = "secondConfirmation"
    }
}
