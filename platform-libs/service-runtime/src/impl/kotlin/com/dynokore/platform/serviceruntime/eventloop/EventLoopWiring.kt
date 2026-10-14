package com.dynokore.platform.serviceruntime.eventloop

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Runs the service's relay, consumers and replay delivery continuously (INV-T-3938, coordinator ruling INV-Q-3935).
 * `service-runtime.event-loops.enabled=false` leaves them all to the caller. The properties are checked either way.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventLoopProperties::class)
class EventLoopWiring {
    @Bean
    @ConditionalOnProperty(
        name = ["${EventLoopProperties.PREFIX}.enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @ConditionalOnMissingBean(EventLoops::class)
    fun serviceEventLoops(
        beans: ListableBeanFactory,
        properties: EventLoopProperties,
        registry: ObjectProvider<MeterRegistry>,
        events: ApplicationEventPublisher,
        clock: ObjectProvider<Clock>,
    ): EventLoops =
        EventLoops(
            beans,
            properties,
            registry.getIfAvailable(),
            events,
            clock.getIfAvailable {
                Clock.systemUTC()
            },
        )

    /** Bean name `eventLoops` is the health component `eventLoops` of the `ready` group. */
    @Bean(EVENT_LOOPS_HEALTH)
    @ConditionalOnMissingBean(name = [EVENT_LOOPS_HEALTH])
    fun eventLoopsHealth(loops: ObjectProvider<EventLoops>): EventLoopHealthIndicator =
        EventLoopHealthIndicator { loops.getIfAvailable() }

    private companion object {
        const val EVENT_LOOPS_HEALTH = "eventLoops"
    }
}
