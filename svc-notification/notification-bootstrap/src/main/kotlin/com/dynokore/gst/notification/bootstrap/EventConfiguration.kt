package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.events.NotificationEventCatalogue
import com.dynokore.platform.outboxandqueue.EventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events notification-svc may emit (INV-REQ-815 behaviour 2): built from the published contract, the replica feeds
 * and the platform catalogue by [NotificationEventCatalogue]. The emission guard of the outbox refuses any other type.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = NotificationEventCatalogue.load()
}
