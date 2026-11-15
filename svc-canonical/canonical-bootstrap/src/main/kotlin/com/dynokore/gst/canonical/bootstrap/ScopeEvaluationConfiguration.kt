package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.clients.MasterDataClient
import com.dynokore.gst.canonical.adapters.clients.MasterDataCounterpartySchemes
import com.dynokore.gst.canonical.adapters.clients.RulesClient
import com.dynokore.gst.canonical.adapters.clients.RulesLineEvaluator
import com.dynokore.gst.canonical.adapters.persistence.JooqProfileElections
import com.dynokore.gst.canonical.application.document.CounterpartySchemeSource
import com.dynokore.gst.canonical.application.document.EntityConstitutions
import com.dynokore.gst.canonical.application.document.LineEvaluator
import com.dynokore.gst.canonical.application.document.SchemeElections
import com.dynokore.gst.canonical.application.document.ScopeEvaluation
import com.dynokore.gst.canonical.application.document.ScopeEvaluationService
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient

/**
 * INV-REQ-479 (INV-AC-358 slice): the scope evaluation of the record chain. The rules and masterdata client beans are
 * declared here with the beans they need (the RestClient.Builder and the ResilienceRegistry of service-runtime, the
 * Environment); their URLs are required, with no default (twelve-factor); the jOOQ context is the one of the open
 * transaction.
 */
@Configuration(proxyBeanMethods = false)
class ScopeEvaluationConfiguration {
    @Bean
    fun rulesClient(
        builder: RestClient.Builder,
        registry: ResilienceRegistry,
        environment: Environment,
    ): RulesClient = RulesClient(builder, registry, environment)

    @Bean
    fun masterDataClient(
        builder: RestClient.Builder,
        registry: ResilienceRegistry,
        environment: Environment,
    ): MasterDataClient = MasterDataClient(builder, environment, registry)

    @Bean
    fun lineEvaluator(client: RulesClient): LineEvaluator = RulesLineEvaluator(client)

    @Bean
    fun counterpartySchemeSource(client: MasterDataClient): CounterpartySchemeSource =
        MasterDataCounterpartySchemes(client)

    @Bean
    fun schemeElections(dsl: DSLContext): SchemeElections {
        val elections = JooqProfileElections(dsl)
        return SchemeElections(elections::electedSchemesOf)
    }

    @Bean
    fun scopeEvaluation(
        constitutions: EntityConstitutions,
        schemes: SchemeElections,
        counterparty: CounterpartySchemeSource,
        evaluator: LineEvaluator,
    ): ScopeEvaluation = ScopeEvaluationService(constitutions, schemes, counterparty, evaluator)
}
