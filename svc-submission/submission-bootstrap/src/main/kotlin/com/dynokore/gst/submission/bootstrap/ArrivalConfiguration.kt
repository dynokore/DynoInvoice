package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.OutboxDocumentCodeRaisedPublisher
import com.dynokore.gst.submission.adapters.persistence.FilingFactFiledPeriods
import com.dynokore.gst.submission.adapters.persistence.JooqFilingFactStore
import com.dynokore.gst.submission.adapters.persistence.JooqRaisedCodes
import com.dynokore.gst.submission.adapters.persistence.JooqRegisteredDocuments
import com.dynokore.gst.submission.application.arrival.ArrivalMetrics
import com.dynokore.gst.submission.application.arrival.ArrivalPlanning
import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.gst.submission.application.arrival.CodeSignals
import com.dynokore.gst.submission.application.arrival.FiledPeriods
import com.dynokore.gst.submission.application.arrival.RaisedCodes
import com.dynokore.gst.submission.application.arrival.RegisteredDocuments
import com.dynokore.gst.submission.application.filing.FilingFactStore
import com.dynokore.gst.submission.application.queue.ErrorCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Wiring of the arrival handler (INV-AC-21088, INV-AC-21095): the code store, the outbox signal, [CodeRaising], the
 * register and filing readers, the meters and [ArrivalPlanning].
 */
@Configuration(proxyBeanMethods = false)
class ArrivalConfiguration {
    @Bean
    fun raisedCodes(dsl: DSLContext): RaisedCodes = JooqRaisedCodes(dsl)

    @Bean
    fun codeSignals(
        outbox: OutboxWriter,
        tenants: TenantContext,
    ): CodeSignals = OutboxDocumentCodeRaisedPublisher(outbox, tenants)

    @Bean
    fun codeRaising(
        catalogue: ErrorCatalogue,
        codes: RaisedCodes,
        signals: CodeSignals,
        clock: Clock,
    ): CodeRaising = CodeRaising(catalogue, codes, signals, clock)

    @Bean
    fun filingFactStore(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): FilingFactStore = JooqFilingFactStore(dsl, tenants, clock)

    @Bean
    fun registeredDocuments(dsl: DSLContext): RegisteredDocuments = JooqRegisteredDocuments(dsl)

    @Bean
    fun filedPeriods(facts: FilingFactStore): FiledPeriods = FilingFactFiledPeriods(facts)

    @Bean
    fun arrivalMetrics(registry: MeterRegistry): ArrivalMetrics = MicrometerArrivalMetrics(registry)

    @Bean
    fun arrivalPlanning(
        documents: RegisteredDocuments,
        filed: FiledPeriods,
        metrics: ArrivalMetrics,
    ): ArrivalPlanning = ArrivalPlanning(documents, filed, metrics)
}
