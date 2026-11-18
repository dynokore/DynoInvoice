package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.EventLogHead
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.productruntime.feed.PolicyFeed
import com.dynokore.platform.productruntime.feed.PolicyTopicReader
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The shared policy reader for every service (INV-T-24768, INV-AC-24766): [PolicyTopicReader] and a runner that polls
 * it on one daemon thread every `product-runtime.policy-reader.poll-interval` (default `PT1S`). Present only when the
 * context has an [EventLogReader] and a [PolicyReplica] and `product-runtime.policy-reader.enabled` is not `false`;
 * an [EventLogHead] is used when present, otherwise no head is reported.
 */
@AutoConfiguration(
    afterName = [
        "com.dynokore.platform.productruntime.ProductRuntimeConfiguration",
        "com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration",
    ],
)
@ConditionalOnProperty(name = [PolicyReaderWiring.ENABLED], havingValue = "true", matchIfMissing = true)
class PolicyReaderWiring {
    @Bean
    @ConditionalOnBean(EventLogReader::class, PolicyReplica::class, PolicyFeed::class)
    fun policyTopicReader(
        reader: EventLogReader,
        head: ObjectProvider<EventLogHead>,
        feed: PolicyFeed,
        replica: PolicyReplica,
    ): PolicyTopicReader = PolicyTopicReader(reader, head.ifAvailable, feed, replica)

    @Bean
    @ConditionalOnBean(PolicyTopicReader::class)
    fun policyTopicReaderRunner(
        reader: PolicyTopicReader,
        environment: Environment,
    ): PolicyTopicReaderRunner =
        PolicyTopicReaderRunner(
            reader,
            Duration.parse(environment.getProperty(POLL_INTERVAL, DEFAULT_INTERVAL)),
        )

    companion object {
        const val ENABLED = "product-runtime.policy-reader.enabled"
        const val POLL_INTERVAL = "product-runtime.policy-reader.poll-interval"
        const val DEFAULT_INTERVAL = "PT1S"
    }
}

/** Polls [reader] every [period] on one daemon thread while the context runs. */
class PolicyTopicReaderRunner(
    private val reader: PolicyTopicReader,
    private val period: Duration,
) : SmartLifecycle {
    private var executor: ScheduledExecutorService? = null

    init {
        require(!period.isZero && !period.isNegative) { "${PolicyReaderWiring.POLL_INTERVAL} must be positive" }
    }

    @Synchronized
    override fun start() {
        if (executor != null) return
        executor =
            Executors
                .newSingleThreadScheduledExecutor { r -> Thread(r, "policy-topic-reader").also { it.isDaemon = true } }
                .also { it.scheduleWithFixedDelay(reader::pollOnce, 0, period.toMillis(), TimeUnit.MILLISECONDS) }
    }

    @Synchronized
    override fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    @Synchronized
    override fun isRunning(): Boolean = executor != null
}
