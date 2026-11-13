package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AuthorityHeadOutbox
import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore
import com.dynokore.gst.access.adapters.persistence.JooqAuthorityHeadStore
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * `access.authority-head.*`: how often the AuthorityHeadObserved heartbeat is published (INV-Q-9047 A: every 500 ms, so
 * that a replica's lag tracker, which expects one within 1000 ms, sees a live feed). A value that is zero or negative
 * stops the start (fail fast, twelve-factor).
 */
@ConfigurationProperties(prefix = "access.authority-head")
data class AuthorityHeadProperties(
    val interval: Duration = Duration.ofMillis(DEFAULT_INTERVAL_MILLIS),
) {
    init {
        require(interval.isPositive) { "access.authority-head.interval must be a positive ISO-8601 duration" }
    }

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 500L
    }
}

/**
 * Runs [AuthorityHeadOutbox.publish] every [AuthorityHeadProperties.interval]. A failed publish is logged and the
 * schedule goes on; the single-writer lock lives in the publish transaction, so a second instance is safe.
 */
class AuthorityHeadLifecycle(
    private val outbox: AuthorityHeadOutbox,
    private val properties: AuthorityHeadProperties,
) : SmartLifecycle {
    private var executor: ScheduledExecutorService? = null

    override fun start() {
        val pool = Executors.newSingleThreadScheduledExecutor { Thread(it, "authority-head").apply { isDaemon = true } }
        val millis = properties.interval.toMillis()
        pool.scheduleWithFixedDelay(
            {
                runCatching { outbox.publish() }
                    .onFailure { log.warn("AuthorityHeadObserved publish failed: {}", it.message) }
            },
            millis,
            millis,
            TimeUnit.MILLISECONDS,
        )
        executor = pool
    }

    override fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    override fun isRunning(): Boolean = executor != null

    private companion object {
        val log: Logger = LoggerFactory.getLogger(AuthorityHeadLifecycle::class.java)
    }
}

/** TPL-18, continued: the AuthorityHeadObserved heartbeat (INV-T-9060). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthorityHeadProperties::class)
class AuthorityHeadHeartbeat {
    /** The head record of the authority versions (INV-Q-17072 A), advanced by every AuthorityChanged append. */
    @Bean
    fun authorityHeadStore(dsl: DSLContext): AuthorityHeadStore = JooqAuthorityHeadStore(dsl)

    @Suppress("LongParameterList") // one collaborator per port of the publish
    @Bean
    fun authorityHeadOutbox(
        platform: PlatformTenantBinding,
        tx: TransactionRunner,
        dsl: DSLContext,
        head: AuthorityHeadStore,
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        clock: Clock,
    ) = AuthorityHeadOutbox(platform, tx, dsl, head, outbox, mapper, clock)

    @Bean
    fun authorityHeadLifecycle(
        outbox: AuthorityHeadOutbox,
        properties: AuthorityHeadProperties,
    ) = AuthorityHeadLifecycle(outbox, properties)
}
