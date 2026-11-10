package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.connection.HttpConnectionTester
import com.dynokore.gst.masterdata.adapters.connection.InstallationEnvironment
import com.dynokore.gst.masterdata.adapters.connection.RecordingConnectionTester
import com.dynokore.gst.masterdata.adapters.http.ManualRateEntry
import com.dynokore.gst.masterdata.adapters.permission.OperationPermissionKeys
import com.dynokore.gst.masterdata.adapters.persistence.JooqCallRecords
import com.dynokore.gst.masterdata.adapters.persistence.JooqConnectionStore
import com.dynokore.gst.masterdata.adapters.persistence.JooqCredentialExpiryStore
import com.dynokore.gst.masterdata.adapters.persistence.JooqRateStore
import com.dynokore.gst.masterdata.application.connection.ConnectionConfigurePermission
import com.dynokore.gst.masterdata.application.connection.ConnectionStore
import com.dynokore.gst.masterdata.application.connection.ConnectionTestPermission
import com.dynokore.gst.masterdata.application.connection.ConnectionTester
import com.dynokore.gst.masterdata.application.connection.ConnectionViewPermission
import com.dynokore.gst.masterdata.application.connection.CreateConnection
import com.dynokore.gst.masterdata.application.connection.CredentialExpiryStore
import com.dynokore.gst.masterdata.application.connection.CredentialGate
import com.dynokore.gst.masterdata.application.connection.EvaluateConnectionStatus
import com.dynokore.gst.masterdata.application.connection.GetConnection
import com.dynokore.gst.masterdata.application.connection.TestConnection
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.application.rate.EnterRate
import com.dynokore.gst.masterdata.application.rate.ExchangeRateEnterPermission
import com.dynokore.gst.masterdata.application.rate.RateStore
import com.dynokore.platform.adapterruntime.BulkheadPolicy
import com.dynokore.platform.adapterruntime.CircuitBreakerPolicy
import com.dynokore.platform.adapterruntime.DeadLetterSink
import com.dynokore.platform.adapterruntime.ResiliencePolicy
import com.dynokore.platform.adapterruntime.RetryPolicy
import com.dynokore.platform.adapterruntime.TimeoutPolicy
import com.dynokore.platform.adapterruntime.deadletter.InMemoryDeadLetterSink
import com.dynokore.platform.adapterruntime.resilience4j.Resilience4jResilientExecutor
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.Duration

/**
 * TPL-18: the connection records, the connection test and the manual exchange rate (INV-T-18774). The four
 * permissions are the scoped keys of the operations, decided per request through [TenantPermission] (the replica);
 * no role name appears here (INV-Q-10027).
 */
@Suppress("TooManyFunctions") // one bean method per wired part, as UseCases does (INV-Q-4629)
@Configuration(proxyBeanMethods = false)
class ConnectionUseCases {
    @Bean
    fun connectionStore(
        dsl: DSLContext,
        clock: Clock,
    ): ConnectionStore = JooqConnectionStore(dsl, clock)

    @Bean
    fun credentialExpiryStore(
        dsl: DSLContext,
        clock: Clock,
    ): CredentialExpiryStore = JooqCredentialExpiryStore(dsl, clock)

    @Bean
    fun rateStore(
        dsl: DSLContext,
        clock: Clock,
    ): RateStore = JooqRateStore(dsl, clock)

    /**
     * The environment of this installation: the property `masterdata.installation-environment` (environment variable
     * `MASTERDATA_INSTALLATION_ENVIRONMENT`), read at every request and passed on unchanged; unset reads as the empty
     * text, which the use case refuses as UnknownEnvironment (fail closed per request; the service still starts).
     */
    @Bean
    fun installationEnvironment(environment: Environment): InstallationEnvironment =
        InstallationEnvironment { environment.getProperty(INSTALLATION_ENVIRONMENT).orEmpty() }

    @Bean
    @Suppress("LongParameterList") // the http tester and the recording path it is wrapped in
    fun connectionTester(
        secrets: SecretStore,
        clock: Clock,
        gate: CredentialGate,
        calls: JooqCallRecords,
        evaluate: EvaluateConnectionStatus,
        tx: TransactionRunner,
    ): ConnectionTester {
        val policy =
            ResiliencePolicy(
                TimeoutPolicy(Duration.ofSeconds(TEST_TIMEOUT_SECONDS)),
                RetryPolicy(1, Duration.ofMillis(RETRY_BACKOFF_MS), Duration.ofMillis(RETRY_BACKOFF_MS), 0.0),
                CircuitBreakerPolicy(FAILURE_RATE, BREAKER_WINDOW, Duration.ofSeconds(BREAKER_WAIT_SECONDS), HALF_OPEN),
                BulkheadPolicy(BULKHEAD_CALLS, Duration.ofMillis(BULKHEAD_WAIT_MS)),
            )
        val deadLetters: DeadLetterSink = InMemoryDeadLetterSink()
        val executor = Resilience4jResilientExecutor(policy, "connection-test", deadLetters, clock)
        return RecordingConnectionTester(HttpConnectionTester(executor, secrets), gate, calls, evaluate, tx, clock)
    }

    @Bean
    fun createConnection(
        tx: TransactionRunner,
        store: ConnectionStore,
        expiries: CredentialExpiryStore,
        permission: TenantPermission,
    ) = CreateConnection(
        tx,
        store,
        ConnectionConfigurePermission { permission.holds(it.value, OperationPermissionKeys.CONNECTION_CONFIGURE) },
        expiries,
    )

    @Bean
    fun getConnection(
        tx: TransactionRunner,
        store: ConnectionStore,
        permission: TenantPermission,
    ) = GetConnection(
        tx,
        store,
        ConnectionViewPermission { permission.holds(it.value, OperationPermissionKeys.CONNECTION_VIEW) },
    )

    @Bean
    fun testConnection(
        tx: TransactionRunner,
        store: ConnectionStore,
        tester: ConnectionTester,
        permission: TenantPermission,
    ) = TestConnection(
        tx,
        store,
        tester,
        ConnectionTestPermission { permission.holds(it.value, OperationPermissionKeys.CONNECTION_TEST) },
    )

    @Bean
    fun enterRate(
        tx: TransactionRunner,
        store: RateStore,
        permission: TenantPermission,
    ) = EnterRate(
        tx,
        store,
        ExchangeRateEnterPermission { permission.holds(it.value, OperationPermissionKeys.EXCHANGE_RATE_ENTER) },
    )

    @Bean
    fun manualRateEntry(
        enter: EnterRate,
        tenants: TenantContext,
        clock: Clock,
    ) = ManualRateEntry(enter, tenants, clock)

    private companion object {
        const val INSTALLATION_ENVIRONMENT = "masterdata.installation-environment"
        const val TEST_TIMEOUT_SECONDS = 2L
        const val RETRY_BACKOFF_MS = 50L
        const val FAILURE_RATE = 100.0F
        const val BREAKER_WINDOW = 100
        const val BREAKER_WAIT_SECONDS = 30L
        const val HALF_OPEN = 3
        const val BULKHEAD_CALLS = 20
        const val BULKHEAD_WAIT_MS = 100L
    }
}
