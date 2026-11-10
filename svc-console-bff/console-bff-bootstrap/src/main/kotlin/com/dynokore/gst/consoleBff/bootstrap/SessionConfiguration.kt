package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.adapters.session.JooqSessionStore
import com.dynokore.gst.consoleBff.adapters.session.ReplicaPrincipalStanding
import com.dynokore.gst.consoleBff.adapters.session.SessionSweeper
import com.dynokore.gst.consoleBff.application.session.PrincipalStanding
import com.dynokore.gst.consoleBff.application.session.SessionResolver
import com.dynokore.gst.consoleBff.application.session.SessionStore
import com.dynokore.gst.consoleBff.domain.session.SessionPolicy
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import org.jooq.DSLContext
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The console session wiring (TPL-18): store, replica standing, resolver, filter and the expiry sweep. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsoleSessionProperties::class)
class SessionConfiguration {
    @Bean
    fun sessionPolicy(properties: ConsoleSessionProperties) = SessionPolicy(properties.idleTimeout, properties.absoluteTimeout)

    /** The one [SessionStore]; typed as its class so the sweeper reaches `deleteExpired`. */
    @Bean
    fun sessionStore(dsl: DSLContext): JooqSessionStore = JooqSessionStore(dsl)

    @Bean
    fun principalStanding(authorityReplica: AuthorityReplica): PrincipalStanding = ReplicaPrincipalStanding(authorityReplica)

    @Bean
    fun sessionResolver(
        policy: SessionPolicy,
        store: SessionStore,
        standing: PrincipalStanding,
        clock: Clock,
    ) = SessionResolver(policy, store, standing, clock)

    @Bean
    fun consoleSessionFilter(resolver: SessionResolver) = ConsoleSessionFilter(resolver)

    /** The filter on the main port (the management server is a separate context); the order is the filter's constant. */
    @Bean
    fun consoleSessionFilterRegistration(filter: ConsoleSessionFilter) =
        FilterRegistrationBean(filter).apply {
            order = ConsoleSessionFilter.ORDER
            addUrlPatterns("/*")
        }

    @Bean
    fun sessionSweeper(
        store: JooqSessionStore,
        clock: Clock,
        properties: ConsoleSessionProperties,
    ) = SessionSweeper(store, clock, properties.idleTimeout, properties.absoluteTimeout)

    /** Runs the sweep every `console.session.sweep-interval`, validated positive by [ConsoleSessionProperties]. */
    @Bean
    fun sessionSweepSchedule(
        sweeper: SessionSweeper,
        properties: ConsoleSessionProperties,
    ): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("console-session-sweep").daemon().factory()).also {
            val interval = properties.sweepInterval.toMillis()
            it.scheduleWithFixedDelay({ runCatching { sweeper.sweep() } }, interval, interval, TimeUnit.MILLISECONDS)
        }
}
