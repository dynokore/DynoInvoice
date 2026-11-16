package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.offboarding.JooqTenantEntityCheck
import com.dynokore.gst.tenancy.adapters.offboarding.JooqUnsubmittedCountReplica
import com.dynokore.gst.tenancy.adapters.offboarding.ReplicaUnsubmittedDocuments
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/**
 * `inv.offboarding.unsubmitted-count.max-age` (ISO-8601 duration): how old the received count may be and still be
 * current. There is no default in code (12-factor III); a missing, unparsable or non-positive value fails the start.
 */
@ConfigurationProperties(prefix = "inv.offboarding.unsubmitted-count")
class OffboardingCountProperties(
    val maxAge: Duration?,
)

/**
 * TPL-18: binds the documents-count port to the read model (INV-REQ-956, INV-AC-22872). Ordering: [OffboardingUseCases]
 * imports this class, and Spring registers an imported configuration before the importing one, so this bean exists
 * when the `@ConditionalOnMissingBean` default of [OffboardingUseCases] is evaluated and the default backs off. The
 * default stays for a context that does not load this configuration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OffboardingCountProperties::class)
class OffboardingCountConfiguration {
    @Bean
    fun replicaUnsubmittedDocuments(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
        properties: OffboardingCountProperties,
    ): UnsubmittedDocuments {
        val maxAge = checkNotNull(properties.maxAge) { "inv.offboarding.unsubmitted-count.max-age is not set" }
        check(!maxAge.isZero && !maxAge.isNegative) { "inv.offboarding.unsubmitted-count.max-age must be positive" }
        val replica = JooqUnsubmittedCountReplica(dsl)
        return ReplicaUnsubmittedDocuments(
            { replica.read(tenants.current()) },
            JooqTenantEntityCheck(dsl, tenants),
            clock,
            maxAge,
        )
    }
}
