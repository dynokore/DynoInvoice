package com.dynokore.platform.productruntime.feed

import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * Reads an event's `data` into its generated payload type (STK-74, INV-Q-3852). A payload that does not bind,
 * a missing required field or a malformed value, is a [ContractViolation]: the consumer sends the record to
 * dead-letter triage and continues (ARC 4.2 step 5).
 */
internal object ContractJson {
    private val mapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    fun <T : Any> read(
        json: String,
        type: Class<T>,
    ): T = try {
        mapper.readValue(json, type)
    } catch (e: JacksonException) {
        throw ContractViolation("payload does not match ${type.simpleName}: ${e.originalMessage}", e)
    }

    fun write(value: Any): String = mapper.writeValueAsString(value)
}
