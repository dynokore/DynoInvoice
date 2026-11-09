package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.RateImportEntity
import com.dynokore.gst.masterdata.adapters.events.ReferenceRateLoadedOutbox
import com.dynokore.gst.masterdata.adapters.http.RateImport
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.application.rate.ApprovedRateSources
import com.dynokore.gst.masterdata.application.rate.ElectedRateSource
import com.dynokore.gst.masterdata.application.rate.GetRegisterAndRates
import com.dynokore.gst.masterdata.application.rate.ImportRates
import com.dynokore.gst.masterdata.application.rate.LookupRate
import com.dynokore.gst.masterdata.application.rate.RateStore
import com.dynokore.gst.masterdata.application.rate.ReferenceRateLoadedPublisher
import com.dynokore.gst.masterdata.application.rate.RegisterLookup
import com.dynokore.gst.masterdata.application.registry.LookupGstRegister
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * TPL-18: `importRates` and `getRegisterAndRates` (INV-T-18775). The permission of `importRates` is the scoped key
 * `exchange-rate.import` decided through [TenantPermission]; `getRegisterAndRates` decides none.
 */
@Configuration(proxyBeanMethods = false)
class RateUseCases {
    @Bean
    fun rateImportEntity() = RateImportEntity()

    @Bean
    fun referenceRateLoadedPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
        tx: TransactionRunner,
        entity: RateImportEntity,
    ): ReferenceRateLoadedPublisher = ReferenceRateLoadedOutbox(outbox, mapper, tenants, tx, entity)

    @Bean
    @Suppress("LongParameterList") // the ports of one use case
    fun importRates(
        tx: TransactionRunner,
        store: RateStore,
        elected: ElectedRateSource,
        approved: ApprovedRateSources,
        publisher: ReferenceRateLoadedPublisher,
    ) = ImportRates(tx, store, elected, approved, publisher)

    @Bean
    fun rateImport(
        import: ImportRates,
        permission: TenantPermission,
        tenants: TenantContext,
        entity: RateImportEntity,
    ) = RateImport(import, permission, tenants, entity)

    @Bean
    fun lookupRate(
        tx: TransactionRunner,
        store: RateStore,
    ) = LookupRate(tx, store)

    @Bean
    fun getRegisterAndRates(
        register: LookupGstRegister,
        elected: ElectedRateSource,
        rates: LookupRate,
    ) = GetRegisterAndRates(RegisterLookup { register.handle(it) }, elected, rates)
}
