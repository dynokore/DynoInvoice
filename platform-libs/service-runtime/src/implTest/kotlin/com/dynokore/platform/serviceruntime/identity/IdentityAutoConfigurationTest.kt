package com.dynokore.platform.serviceruntime.identity

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * INV-AC-2200: `service-runtime.workload-identity.audience` is mandatory, not
 * merely checked when present — a service that starts without one configured
 * would otherwise accept a workload token bearing any (or no) audience.
 */
class IdentityAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(IdentityAutoConfiguration::class.java),
        )

    @Test
    fun `the application context refuses to start with no audience configured`() {
        contextRunner.run { context ->
            val failure = context.startupFailure
            failure.shouldNotBeNull()
            generateSequence(failure as Throwable?) { it.cause }
                .any { it.message?.contains("service-runtime.workload-identity.audience") == true }
                .shouldBeTrue()
        }
    }

    @Test
    fun `the application context starts once an audience is configured`() {
        contextRunner
            .withPropertyValues("service-runtime.workload-identity.audience=canonical-svc")
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(WorkloadTokenVerifier::class.java)
            }
    }
}
