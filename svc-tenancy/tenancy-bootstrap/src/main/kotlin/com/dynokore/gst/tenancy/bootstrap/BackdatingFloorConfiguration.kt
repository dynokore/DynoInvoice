package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.policy.TenancyBackdatingFloor
import com.dynokore.platform.configresolver.BackdatingFloor
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Binds the config-resolver port [BackdatingFloor] for VP-62 (INV-Q-16675 option A, INV-AC-16774): service-runtime
 * takes the bean through an `ObjectProvider`, so without it every back-dated first value is refused.
 */
@Configuration(proxyBeanMethods = false)
class BackdatingFloorConfiguration {
    @Bean
    fun tenancyBackdatingFloor(dsl: DSLContext): BackdatingFloor = TenancyBackdatingFloor(dsl)
}
