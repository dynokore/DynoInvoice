package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.clients.MasterDataClient
import com.dynokore.gst.canonical.adapters.clients.MasterDataEdge
import com.dynokore.gst.canonical.adapters.clients.RulesClient
import com.dynokore.gst.canonical.adapters.clients.RulesEdge
import com.dynokore.gst.canonical.application.document.CounterpartySchemeSource
import com.dynokore.gst.canonical.application.document.EntityConstitutions
import com.dynokore.gst.canonical.application.document.LineEvaluator
import com.dynokore.gst.canonical.application.document.SchemeElections
import com.dynokore.gst.canonical.application.document.ScopeEvaluation
import com.dynokore.gst.canonical.application.document.ScopeEvaluationService
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.client.RestClient

/** INV-AC-21475: the scope evaluation beans of the record chain (the client beans are INV-T-21416's, faked here). */
class ScopeEvaluationConfigurationTest {
    private class Registry : ResilienceRegistry {
        override fun forEdge(edge: String): ResilientEdge =
            object : ResilientEdge {
                override fun <T> execute(
                    deadline: Deadline,
                    onCallNotPermitted: () -> T,
                    onConnectFailure: () -> T,
                    onTimeout: () -> T,
                    block: () -> T,
                ): T = block()
            }
    }

    @Configuration(proxyBeanMethods = false)
    class Collaborators {
        @Bean
        fun rulesClient(): RulesClient =
            RulesClient(
                RestClient.builder(),
                Registry(),
                MockEnvironment().withProperty(RulesEdge.URL_PROPERTY, "http://127.0.0.1:1"),
            )

        @Bean
        fun masterDataClient(): MasterDataClient =
            MasterDataClient(
                RestClient.builder(),
                MockEnvironment().withProperty(MasterDataEdge.URL_PROPERTY, "http://127.0.0.1:1"),
                Registry(),
            )

        @Bean
        fun dsl(): DSLContext = DSL.using(SQLDialect.POSTGRES)

        @Bean
        fun constitutions(): EntityConstitutions =
            object : EntityConstitutions {
                override fun constitutionOf(
                    tenantId: java.util.UUID,
                    entityRef: String,
                ): String? = null
            }
    }

    private val runner =
        ApplicationContextRunner().withUserConfiguration(
            Collaborators::class.java,
            ScopeEvaluationConfiguration::class.java,
        )

    @Test
    fun `INV-AC-21475 the context starts with exactly one ScopeEvaluation bean of class ScopeEvaluationService`() {
        runner.run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(ScopeEvaluation::class.java).size shouldBe 1
            context.getBean(ScopeEvaluation::class.java).javaClass shouldBe ScopeEvaluationService::class.java
        }
    }

    @Test
    fun `INV-AC-21475 the context holds one LineEvaluator, one CounterpartySchemeSource and one SchemeElections`() {
        runner.run { context ->
            context.getBeansOfType(LineEvaluator::class.java).size shouldBe 1
            context.getBeansOfType(CounterpartySchemeSource::class.java).size shouldBe 1
            context.getBeansOfType(SchemeElections::class.java).size shouldBe 1
        }
    }
}
