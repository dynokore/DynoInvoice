package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.http.SuggestionCursors
import com.dynokore.gst.masterdata.adapters.persistence.JooqDirectoryCache
import com.dynokore.gst.masterdata.adapters.persistence.JooqLookupEvidenceLog
import com.dynokore.gst.masterdata.adapters.persistence.JooqNetworkRegistrations
import com.dynokore.gst.masterdata.adapters.persistence.JooqOwnPeppolIds
import com.dynokore.gst.masterdata.adapters.persistence.JooqRegisterCache
import com.dynokore.gst.masterdata.adapters.persistence.JooqRegisterSuggestions
import com.dynokore.gst.masterdata.adapters.registry.HttpDirectoryClient
import com.dynokore.gst.masterdata.adapters.registry.HttpRegisterClient
import com.dynokore.gst.masterdata.adapters.registry.PolicyRegistryPolicies
import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.application.registry.ConfirmNetworkRegistration
import com.dynokore.gst.masterdata.application.registry.DirectoryCache
import com.dynokore.gst.masterdata.application.registry.DirectoryClient
import com.dynokore.gst.masterdata.application.registry.DirectoryReply
import com.dynokore.gst.masterdata.application.registry.LookupEvidenceLog
import com.dynokore.gst.masterdata.application.registry.LookupGstRegister
import com.dynokore.gst.masterdata.application.registry.NetworkRegistrations
import com.dynokore.gst.masterdata.application.registry.OwnPeppolIds
import com.dynokore.gst.masterdata.application.registry.ReadNetworkRegistration
import com.dynokore.gst.masterdata.application.registry.RegisterAccess
import com.dynokore.gst.masterdata.application.registry.RegisterCache
import com.dynokore.gst.masterdata.application.registry.RegisterClient
import com.dynokore.gst.masterdata.application.registry.RegisterPorts
import com.dynokore.gst.masterdata.application.registry.RegisterReply
import com.dynokore.gst.masterdata.application.registry.RegisterSuggestionUseCases
import com.dynokore.gst.masterdata.application.registry.RegisterSuggestions
import com.dynokore.gst.masterdata.application.registry.RegistryPolicies
import com.dynokore.gst.masterdata.application.registry.ResolveReachability
import com.dynokore.platform.adapterruntime.BulkheadPolicy
import com.dynokore.platform.adapterruntime.CircuitBreakerPolicy
import com.dynokore.platform.adapterruntime.DeadLetterSink
import com.dynokore.platform.adapterruntime.ResiliencePolicy
import com.dynokore.platform.adapterruntime.RetryPolicy
import com.dynokore.platform.adapterruntime.TimeoutPolicy
import com.dynokore.platform.adapterruntime.deadletter.InMemoryDeadLetterSink
import com.dynokore.platform.adapterruntime.resilience4j.Resilience4jResilientExecutor
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Clock
import java.time.Duration

/** `masterdata.registry.*`: the base URL of each registry connection and the edge's deadline and breaker wait. */
@ConfigurationProperties(prefix = "masterdata.registry")
data class RegistryProperties(
    val directory: Connection = Connection(),
    val register: Connection = Connection(),
    val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    val breakerWait: Duration = Duration.ofSeconds(DEFAULT_BREAKER_WAIT_SECONDS),
) {
    data class Connection(
        val baseUrl: String = "",
    )

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 2L
        const val DEFAULT_BREAKER_WAIT_SECONDS = 30L
    }
}

/**
 * TPL-18: the registry lookups of INV-T-4946. The two connections go out through adapter-runtime's
 * [Resilience4jResilientExecutor] (deadline, bounded retry, circuit breaker, bulkhead: INV-REQ-1353). Their base URLs
 * are `masterdata.registry.directory.base-url` and `masterdata.registry.register.base-url`; a connection with no URL
 * answers unreachable, so the lookup degrades (off-network, `REGISTER_UNAVAILABLE`) and never blocks (INV-REQ-801).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RegistryProperties::class)
@Suppress("TooManyFunctions") // one bean method per wired part, as UseCases does; INV-Q-4629 (CS-50)
class RegistryUseCases {
    @Bean
    fun registryDeadLetters(): DeadLetterSink = InMemoryDeadLetterSink()

    @Bean
    fun directoryClient(
        properties: RegistryProperties,
        deadLetters: DeadLetterSink,
        clock: Clock,
    ): DirectoryClient {
        val baseUrl = properties.directory.baseUrl
        if (baseUrl.isBlank()) {
            notConfigured("directory")
            return DirectoryClient { DirectoryReply.Unreachable }
        }
        val policy = edge(properties.timeout, properties.breakerWait)
        return HttpDirectoryClient(
            URI.create(baseUrl),
            Resilience4jResilientExecutor(policy, "peppol-directory", deadLetters, clock),
        )
    }

    @Bean
    fun registerClient(
        properties: RegistryProperties,
        deadLetters: DeadLetterSink,
        clock: Clock,
    ): RegisterClient {
        val baseUrl = properties.register.baseUrl
        if (baseUrl.isBlank()) {
            notConfigured("register")
            return RegisterClient { RegisterReply.Unavailable }
        }
        val policy = edge(properties.timeout, properties.breakerWait)
        return HttpRegisterClient(
            URI.create(baseUrl),
            Resilience4jResilientExecutor(policy, "gst-register", deadLetters, clock),
        )
    }

    @Bean
    fun registryPolicies(
        policy: PolicyReader,
        tenants: TenantContext,
        mapper: ObjectMapper,
    ): RegistryPolicies = PolicyRegistryPolicies(policy, tenants, mapper)

    @Bean
    fun directoryCache(
        dsl: DSLContext,
        clock: Clock,
    ): DirectoryCache = JooqDirectoryCache(dsl, clock)

    @Bean
    fun registerCache(
        dsl: DSLContext,
        clock: Clock,
    ): RegisterCache = JooqRegisterCache(dsl, clock)

    @Bean
    fun lookupEvidenceLog(
        dsl: DSLContext,
        recorder: EvidenceArtefactRecorder,
        tenants: TenantContext,
        clock: Clock,
    ): LookupEvidenceLog = JooqLookupEvidenceLog(dsl, recorder, tenants, clock)

    @Bean
    fun registerSuggestions(
        dsl: DSLContext,
        clock: Clock,
    ): RegisterSuggestions = JooqRegisterSuggestions(dsl, clock)

    @Bean
    fun networkRegistrations(
        dsl: DSLContext,
        clock: Clock,
    ): NetworkRegistrations = JooqNetworkRegistrations(dsl, clock)

    @Bean
    fun ownPeppolIds(dsl: DSLContext): OwnPeppolIds = JooqOwnPeppolIds(dsl)

    @Bean
    @Suppress("LongParameterList") // the ports of one use case
    fun resolveReachability(
        tx: TransactionRunner,
        cache: DirectoryCache,
        client: DirectoryClient,
        policies: RegistryPolicies,
        evidence: LookupEvidenceLog,
        clock: Clock,
    ) = ResolveReachability(tx, cache, client, policies, evidence, clock)

    @Bean
    @Suppress("LongParameterList") // the ports of one use case
    fun registerAccess(
        tx: TransactionRunner,
        cache: RegisterCache,
        client: RegisterClient,
        policies: RegistryPolicies,
        evidence: LookupEvidenceLog,
        clock: Clock,
    ) = RegisterAccess(tx, RegisterPorts(cache, client, policies, evidence), clock)

    @Bean
    @Suppress("LongParameterList") // the ports of one use case
    fun lookupGstRegister(
        tx: TransactionRunner,
        parties: CounterpartyRepository,
        access: RegisterAccess,
        suggestions: RegisterSuggestions,
        record: RecordCounterparty,
        clock: Clock,
    ) = LookupGstRegister(tx, parties, access, suggestions, record, clock)

    @Bean
    fun readNetworkRegistration(
        tx: TransactionRunner,
        items: NetworkRegistrations,
    ) = ReadNetworkRegistration(tx, items)

    @Bean
    @Suppress("LongParameterList") // the ports of one use case
    fun confirmNetworkRegistration(
        tx: TransactionRunner,
        items: NetworkRegistrations,
        ownIds: OwnPeppolIds,
        client: DirectoryClient,
        clock: Clock,
    ) = ConfirmNetworkRegistration(tx, items, ownIds, client, clock)

    @Bean
    fun registerSuggestionUseCases(
        tx: TransactionRunner,
        suggestions: RegisterSuggestions,
        parties: CounterpartyRepository,
        record: RecordCounterparty,
        clock: Clock,
    ) = RegisterSuggestionUseCases(tx, suggestions, parties, record, clock)

    @Bean
    fun suggestionCursors(
        secrets: SecretStore,
        clock: Clock,
    ) = SuggestionCursors(secrets, clock)

    private fun edge(
        timeout: Duration,
        breakerWait: Duration,
    ) = ResiliencePolicy(
        TimeoutPolicy(timeout),
        RetryPolicy(
            RETRY_ATTEMPTS,
            Duration.ofMillis(RETRY_BACKOFF_MS),
            Duration.ofMillis(RETRY_BACKOFF_MAX_MS),
            JITTER,
        ),
        CircuitBreakerPolicy(FAILURE_RATE, BREAKER_WINDOW, breakerWait, HALF_OPEN_CALLS),
        BulkheadPolicy(BULKHEAD_CALLS, Duration.ofMillis(BULKHEAD_WAIT_MS)),
    )

    private fun notConfigured(connection: String) =
        LoggerFactory
            .getLogger(RegistryUseCases::class.java)
            .warn("no base URL is configured for the {} connection: its lookups degrade", connection)

    private companion object {
        const val RETRY_ATTEMPTS = 2
        const val RETRY_BACKOFF_MS = 50L
        const val RETRY_BACKOFF_MAX_MS = 500L
        const val JITTER = 0.2
        const val FAILURE_RATE = 50.0F
        const val BREAKER_WINDOW = 20
        const val HALF_OPEN_CALLS = 3
        const val BULKHEAD_CALLS = 20
        const val BULKHEAD_WAIT_MS = 100L
    }
}
