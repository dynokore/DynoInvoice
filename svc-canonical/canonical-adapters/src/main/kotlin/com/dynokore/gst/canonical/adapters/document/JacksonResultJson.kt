package com.dynokore.gst.canonical.adapters.document

import com.dynokore.gst.canonical.application.document.ResultJson
import tools.jackson.databind.ObjectMapper

/** [ResultJson] over Jackson; the application module names no serializer (ARC-03). */
class JacksonResultJson(
    private val mapper: ObjectMapper,
) : ResultJson {
    override fun toJson(result: Map<String, Any?>): String = mapper.writeValueAsString(result)
}
