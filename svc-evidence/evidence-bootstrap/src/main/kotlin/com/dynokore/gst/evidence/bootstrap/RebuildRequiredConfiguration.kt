package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Ruling INV-Q-10834 A (as INV-Q-10031 for tenancy-svc): until the product binds an incident sink, a consumer that
 * needs a rebuild is logged at ERROR (consumer, topic, next offset, purge watermark) and nothing else happens; the
 * delivery of the alert to a person is open for every consumer service (tooling/followups.md).
 */
@Configuration(proxyBeanMethods = false)
class RebuildRequiredConfiguration {
    @Bean
    @ConditionalOnMissingBean(RebuildRequiredSink::class)
    fun rebuildRequiredSink(): RebuildRequiredSink = LoggingRebuildRequiredSink()
}

/** The log-only [RebuildRequiredSink]: ERROR with the consumer, the topic and the offsets, nothing else. */
class LoggingRebuildRequiredSink : RebuildRequiredSink {
    override fun raise(incident: RebuildRequiredIncident) {
        LoggerFactory
            .getLogger(LoggingRebuildRequiredSink::class.java)
            .error(
                "rebuild required: consumer {} on topic {}: next offset {} is at or below purge watermark {}",
                incident.consumer,
                incident.topic,
                incident.nextOffset,
                incident.watermark,
            )
    }
}
