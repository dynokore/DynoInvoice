package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.authz.CatalogueReadPermissions
import com.dynokore.gst.consoleBff.adapters.authz.ConsoleReadAuthorisationFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The console read-authorisation wiring (TPL-18; INV-REQ-871 behavior 1): the filter over the production permission
 * table and the application's [AuthorityReplica], registered once after the session and rate-limit filters.
 */
@Configuration(proxyBeanMethods = false)
class ReadAuthorisationConfiguration {
    @Bean
    fun consoleReadAuthorisationFilter(authorityReplica: AuthorityReplica) =
        ConsoleReadAuthorisationFilter(CatalogueReadPermissions(), authorityReplica)

    @Bean
    fun consoleReadAuthorisationFilterRegistration(filter: ConsoleReadAuthorisationFilter) =
        FilterRegistrationBean(filter).apply {
            order = ConsoleReadAuthorisationFilter.ORDER
            addUrlPatterns("/*")
        }
}
