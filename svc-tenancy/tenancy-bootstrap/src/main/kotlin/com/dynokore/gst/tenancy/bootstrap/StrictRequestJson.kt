package com.dynokore.gst.tenancy.bootstrap

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature

/**
 * Request bodies are read as the contract writes them (OWASP ASVS V5.1 input validation: type and shape checked on
 * the way in): a member the schema does not name is refused and a JSON string is never taken for a boolean or a number
 * (INV-AC-9769: `recordsPurchases` as the string `false` and the unknown name `recordsSales` are each 400 SRT-001). A
 * model the generator marks `ignoreUnknown` keeps ignoring unknown members.
 */
@Configuration(proxyBeanMethods = false)
class StrictRequestJson {
    @Bean
    fun requestJsonStrictness(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            builder.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        }
}
