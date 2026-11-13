package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.OutboxDocumentCodeRaisedPublisher
import com.dynokore.gst.submission.adapters.persistence.JooqRaisedCodes
import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.gst.submission.application.arrival.CodeSignals
import com.dynokore.gst.submission.application.arrival.RaisedCodes
import com.dynokore.gst.submission.application.queue.ErrorCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Wiring of the raised-code producer (INV-AC-21088): the code store, the outbox signal and [CodeRaising]. */
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
}
