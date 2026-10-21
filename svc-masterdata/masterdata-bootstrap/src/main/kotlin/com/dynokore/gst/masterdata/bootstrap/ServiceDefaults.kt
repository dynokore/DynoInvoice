package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.MasterdataEventCatalogue
import com.dynokore.gst.masterdata.adapters.persistence.jooq.Masterdata
import com.dynokore.gst.masterdata.application.identity.ConfirmerPolicy
import com.dynokore.gst.masterdata.domain.identity.ConfirmerSet
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.jooq.Schema
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The service's schema, its event catalogue and the defaults a deployment or a test replaces. */
@Configuration(proxyBeanMethods = false)
class ServiceDefaults {
    @Bean
    fun masterdataSchema(): Schema = Masterdata.MASTERDATA

    @Bean
    fun eventCatalogue(): EventCatalogue = MasterdataEventCatalogue.load()

    /**
     * The confirmer set of VP-09: another `entity.admin` or `tenant.admin`. The contract publishes no operation that
     * sets a tenant's choice (see the merge request, "Gaps"), so the default is the only set in force.
     */
    @Bean
    @ConditionalOnMissingBean
    fun confirmerPolicy(): ConfirmerPolicy = ConfirmerPolicy { ConfirmerSet.DEFAULT }
}
