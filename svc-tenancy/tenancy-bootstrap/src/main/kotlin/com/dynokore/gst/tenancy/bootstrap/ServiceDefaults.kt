package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.adapters.persistence.jooq.Tenancy
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.jooq.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The service's schema and its event catalogue. */
@Configuration(proxyBeanMethods = false)
class ServiceDefaults {
    @Bean
    fun tenancySchema(): Schema = Tenancy.TENANCY

    @Bean
    fun eventCatalogue(): EventCatalogue = TenancyEventCatalogue.load()
}
