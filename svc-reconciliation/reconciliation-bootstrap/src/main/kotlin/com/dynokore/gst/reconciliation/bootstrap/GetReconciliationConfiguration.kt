package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.http.PendingRetryAfter
import com.dynokore.gst.reconciliation.adapters.persistence.JooqReturnRecorded
import com.dynokore.gst.reconciliation.application.run.GetReconciliation
import com.dynokore.gst.reconciliation.application.run.ReturnRecorded
import com.dynokore.gst.reconciliation.application.run.RunReader
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The beans of `getReconciliation` (INV-T-24421, INV-REQ-657): the return-existence adapter, the use case over the
 * run reader, and the `Retry-After` of the pending answer. The value is configuration (12-factor III); a value below 1
 * stops the start.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReadsProperties::class)
class GetReconciliationConfiguration {
    @Bean
    fun returnRecorded(dsl: DSLContext): ReturnRecorded = JooqReturnRecorded(dsl)

    @Bean
    fun getReconciliation(
        reader: RunReader,
        returns: ReturnRecorded,
    ): GetReconciliation = GetReconciliation(reader, returns)

    @Bean
    fun pendingRetryAfter(properties: ReadsProperties): PendingRetryAfter =
        PendingRetryAfter(properties.pendingRetryAfterSeconds)
}

/**
 * `inv.reconciliation.reads.*`: the `Retry-After` of the 503 PER-016 answer, in whole seconds. No default in code
 * (12-factor III): an absent key binds 0, and a value below 1 stops the start.
 */
@ConfigurationProperties(prefix = "inv.reconciliation.reads")
data class ReadsProperties(
    val pendingRetryAfterSeconds: Int,
) {
    init {
        require(pendingRetryAfterSeconds >= 1) {
            "inv.reconciliation.reads.pending-retry-after-seconds must be at least 1, was $pendingRetryAfterSeconds"
        }
    }
}
