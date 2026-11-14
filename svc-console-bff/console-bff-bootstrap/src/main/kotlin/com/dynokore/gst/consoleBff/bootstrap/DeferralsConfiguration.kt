package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.deferral.JooqDeferralStore
import com.dynokore.gst.consoleBff.adapters.deferral.JooqDeferralTargets
import com.dynokore.gst.consoleBff.adapters.deferral.ReplicaDeferAuthority
import com.dynokore.gst.consoleBff.application.deferral.DeferTarget
import com.dynokore.gst.consoleBff.application.deferral.UndeferTarget
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** deferDocument and undeferDocument (INV-REQ-960): use cases over the jOOQ store and the replica authority. */
@Configuration(proxyBeanMethods = false)
class DeferralsConfiguration {
    @Bean
    fun deferTarget(
        authorityReplica: AuthorityReplica,
        dsl: DSLContext,
        clock: Clock,
    ) = DeferTarget(
        ReplicaDeferAuthority(authorityReplica, clock, Duration.ofSeconds(1)),
        JooqDeferralTargets(dsl),
        JooqDeferralStore(dsl),
        clock,
        UUID::randomUUID,
    )

    @Bean
    fun undeferTarget(
        authorityReplica: AuthorityReplica,
        dsl: DSLContext,
        clock: Clock,
    ) = UndeferTarget(ReplicaDeferAuthority(authorityReplica, clock, Duration.ofSeconds(1)), JooqDeferralStore(dsl))

    @Bean
    fun deferralsController(
        deferTarget: DeferTarget,
        undeferTarget: UndeferTarget,
    ) = DeferralsController(deferTarget, undeferTarget)

    @Bean
    fun deferralsProblemAdvice() = DeferralsProblemAdvice()
}
