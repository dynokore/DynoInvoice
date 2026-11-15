package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.events.OutboxPayloadReadyPublisher
import com.dynokore.gst.payload.application.payload.PayloadReadyPublisher
import com.dynokore.gst.payload.application.payload.PublishPayloadReady
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The `PayloadReady` publisher over the outbox and the use case that checks the facts before staging them. */
@Configuration(proxyBeanMethods = false)
class PayloadReadyPublisherConfiguration {
    @Bean
    fun payloadReadyPublisher(
        outbox: OutboxWriter,
        tenants: TenantContext,
    ): PayloadReadyPublisher = OutboxPayloadReadyPublisher(outbox, tenants)

    @Bean
    fun publishPayloadReady(publisher: PayloadReadyPublisher): PublishPayloadReady = PublishPayloadReady(publisher)
}
