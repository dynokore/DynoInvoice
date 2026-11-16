package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.override.JooqOverrideTargets
import com.dynokore.gst.consoleBff.adapters.override.ReplicaOverrideAuthority
import com.dynokore.gst.consoleBff.application.override.OverrideException
import com.dynokore.gst.consoleBff.application.override.PayloadOverrides
import com.dynokore.gst.consoleBff.application.override.SubmissionOverrides
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/**
 * overrideException (INV-REQ-500): the routing use case over the jOOQ read of the exception row, the replica
 * authority and the two owner clients (`PayloadOverrideClient`, `SubmissionOverrideClient`, whose base URLs are
 * required properties), and the problem mapper that `ExceptionsController` uses. Nothing here writes the exception row (single writer per state).
 */
@Configuration(proxyBeanMethods = false)
class OverridesConfiguration {
    @Bean
    fun overrideException(
        authorityReplica: AuthorityReplica,
        dsl: DSLContext,
        clock: Clock,
        payload: PayloadOverrides,
        submission: SubmissionOverrides,
    ) = OverrideException(
        JooqOverrideTargets(dsl),
        ReplicaOverrideAuthority(authorityReplica, clock, Duration.ofSeconds(1)),
        payload,
        submission,
    )

    @Bean
    fun overridesProblemMapper() = OverridesProblemMapper()
}
