package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.ReferenceRateLoadedOutbox
import com.dynokore.gst.masterdata.adapters.http.RateImport
import com.dynokore.gst.masterdata.adapters.persistence.JooqRateImportStore
import com.dynokore.gst.masterdata.application.gapreport.LongRunner
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.application.rate.ApprovedRateSources
import com.dynokore.gst.masterdata.application.rate.ElectedRateSource
import com.dynokore.gst.masterdata.application.rate.GetRegisterAndRates
import com.dynokore.gst.masterdata.application.rate.ImportRates
import com.dynokore.gst.masterdata.application.rate.LookupRate
import com.dynokore.gst.masterdata.application.rate.RateImportStore
import com.dynokore.gst.masterdata.application.rate.RateStore
import com.dynokore.gst.masterdata.application.rate.ReadRateImportRecord
import com.dynokore.gst.masterdata.application.rate.ReferenceRateLoadedPublisher
import com.dynokore.gst.masterdata.application.rate.RegisterLookup
import com.dynokore.gst.masterdata.application.registry.LookupGstRegister
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18: `importRates` and `getRegisterAndRates` (INV-T-18775). The permission of `importRates` is the scoped key
 * `exchange-rate.import` decided through [TenantPermission]; `getRegisterAndRates` decides none.
 */
@Configuration(proxyBeanMethods = false)
class RateUseCases {
    @Bean
    fun referenceRateLoadedPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
        tx: TransactionRunner,
        imports: RateImportStore,
    ): ReferenceRateLoadedPublisher = ReferenceRateLoadedOutbox(outbox, mapper, tenants, tx, imports)

    @Bean
    @Suppress("LongParameterList") // the ports of one use case
    fun importRates(
        tx: TransactionRunner,
        store: RateStore,
        elected: ElectedRateSource,
        approved: ApprovedRateSources,
        publisher: ReferenceRateLoadedPublisher,
        imports: RateImportStore,
        clock: Clock,
        runner: LongRunner,
    ) = ImportRates(tx, store, elected, approved, publisher, imports, clock, runner)

    @Bean
    fun rateImportStore(
        dsl: DSLContext,
        clock: Clock,
        mapper: ObjectMapper,
    ): RateImportStore = JooqRateImportStore(dsl, clock, mapper)

    @Bean
    fun readRateImportRecord(
        tx: TransactionRunner,
        store: RateImportStore,
    ) = ReadRateImportRecord(tx, store)

    @Bean
    @Suppress("LongParameterList") // the ports of one controller
    fun rateImport(
        import: ImportRates,
        reader: ReadRateImportRecord,
        permission: TenantPermission,
        tenants: TenantContext,
        mapper: ObjectMapper,
    ) = RateImport(import, reader, permission, tenants, mapper)

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
