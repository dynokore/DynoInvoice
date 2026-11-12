package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqHoldingChecks
import com.dynokore.gst.submission.adapters.persistence.JooqOverrideStore
import com.dynokore.gst.submission.adapters.persistence.JooqReleasedOverrideChecks
import com.dynokore.gst.submission.application.gate.OverrideStore
import com.dynokore.gst.submission.application.readiness.HoldingChecks
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The stored overrides and the two reads of the readiness entry source (INV-REQ-72). The readiness entry source, the
 * use case and the controller are bound by the readiness binding slice, not here.
 */
@Configuration(proxyBeanMethods = false)
class OverrideConfiguration {
    @Bean
    fun overrideStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): OverrideStore = JooqOverrideStore(dsl, tenants)

    @Bean
    fun holdingChecks(
        dsl: DSLContext,
        tenants: TenantContext,
    ): HoldingChecks = JooqHoldingChecks(dsl, tenants)

    @Bean
    fun releasedOverrideChecks(
        dsl: DSLContext,
        tenants: TenantContext,
    ): ReleasedOverrideChecks = JooqReleasedOverrideChecks(dsl, tenants)
}
