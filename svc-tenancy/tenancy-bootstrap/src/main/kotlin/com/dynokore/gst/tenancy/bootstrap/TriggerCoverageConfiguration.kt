package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.coverage.ReplicaTriggerCoverage
import com.dynokore.gst.tenancy.application.lifecycle.TriggerCoverage
import com.dynokore.gst.tenancy.application.lifecycle.UnboundTriggerCoverage
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Binds the [TriggerCoverage] port of goLive to the coverage replica (INV-REQ-788, INV-AC-20943). The bean makes the
 * unbound default of [LifecycleUseCases] lose whatever the order of the scanned configuration classes: the condition
 * ignores [UnboundTriggerCoverage], and [Primary] makes the replica reader the one goLive gets when the default was
 * registered first. Any other binding of the port (a test stub) still wins. The reader fails closed on a missing row.
 */
@Configuration(proxyBeanMethods = false)
class TriggerCoverageConfiguration {
    @Bean
    @Primary
    @ConditionalOnMissingBean(value = [TriggerCoverage::class], ignored = [UnboundTriggerCoverage::class])
    fun replicaTriggerCoverage(dsl: DSLContext): TriggerCoverage = ReplicaTriggerCoverage(dsl)
}
