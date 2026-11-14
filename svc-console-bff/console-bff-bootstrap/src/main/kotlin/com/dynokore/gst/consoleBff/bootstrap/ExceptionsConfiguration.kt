package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.authz.CatalogueReadPermissions
import com.dynokore.gst.consoleBff.adapters.authz.ReplicaEntityScope
import com.dynokore.gst.consoleBff.adapters.exceptions.JooqExceptionQueue
import com.dynokore.gst.consoleBff.application.exceptions.ListExceptions
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/** The getExceptions read (INV-REQ-940): the use case over the replica scope and the jOOQ queue, and its controller. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsoleExceptionsProperties::class)
class ExceptionsConfiguration {
    @Bean
    fun listExceptions(
        authorityReplica: AuthorityReplica,
        dsl: DSLContext,
        clock: Clock,
        properties: ConsoleExceptionsProperties,
    ) = ListExceptions(
        ReplicaEntityScope(authorityReplica, CatalogueReadPermissions(), clock, Duration.ofSeconds(1)),
        JooqExceptionQueue(dsl),
        properties.maxLimit,
    )

    @Bean
    fun exceptionsController(listExceptions: ListExceptions) = ExceptionsController(listExceptions)

    @Bean
    fun exceptionsProblemAdvice() = ExceptionsProblemAdvice()
}

/**
 * `console.exceptions.*`: [maxLimit] is the largest page size of getExceptions, equal to the `maximum` of the
 * limit parameter in the pinned contract (a test compares them). No default in code: a missing or non-positive
 * value fails the start with a message naming the property (12-factor).
 */
@ConfigurationProperties("console.exceptions")
class ConsoleExceptionsProperties(
    maxLimit: Int?,
) {
    val maxLimit: Int =
        requireNotNull(maxLimit) { "console.exceptions.max-limit is required" }
            .also { require(it >= 1) { "console.exceptions.max-limit must be at least 1, was $it" } }
}
