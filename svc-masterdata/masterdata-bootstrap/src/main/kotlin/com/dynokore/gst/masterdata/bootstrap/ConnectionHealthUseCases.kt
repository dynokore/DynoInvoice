package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.ConnectionCredentialExpiryReminderOutbox
import com.dynokore.gst.masterdata.adapters.events.ConnectionStatusChangedOutbox
import com.dynokore.gst.masterdata.adapters.http.HealthExtras
import com.dynokore.gst.masterdata.adapters.persistence.ExpiryTenantSource
import com.dynokore.gst.masterdata.adapters.persistence.JooqCallRecords
import com.dynokore.gst.masterdata.adapters.persistence.JooqConnectionStateMemory
import com.dynokore.gst.masterdata.adapters.persistence.JooqCredentialExpiries
import com.dynokore.gst.masterdata.adapters.persistence.JooqCredentialStateStore
import com.dynokore.gst.masterdata.adapters.persistence.JooqExpiryTenantSource
import com.dynokore.gst.masterdata.adapters.persistence.JooqReminderLog
import com.dynokore.gst.masterdata.adapters.registry.PolicyReminderOffsets
import com.dynokore.gst.masterdata.application.connection.CheckCredentialExpiry
import com.dynokore.gst.masterdata.application.connection.ConnectionCredentialRotatePermission
import com.dynokore.gst.masterdata.application.connection.ConnectionHealthViewPermission
import com.dynokore.gst.masterdata.application.connection.ConnectionStateMemory
import com.dynokore.gst.masterdata.application.connection.ConnectionStatusPublisher
import com.dynokore.gst.masterdata.application.connection.ConnectionStore
import com.dynokore.gst.masterdata.application.connection.CredentialExpiryReminderPublisher
import com.dynokore.gst.masterdata.application.connection.CredentialExpiryStore
import com.dynokore.gst.masterdata.application.connection.CredentialGate
import com.dynokore.gst.masterdata.application.connection.CredentialStateStore
import com.dynokore.gst.masterdata.application.connection.EvaluateConnectionStatus
import com.dynokore.gst.masterdata.application.connection.GetConnectionHealth
import com.dynokore.gst.masterdata.application.connection.HealthPorts
import com.dynokore.gst.masterdata.application.connection.ReminderOffsets
import com.dynokore.gst.masterdata.application.connection.RotateCredential
import com.dynokore.gst.masterdata.application.connection.RotationPorts
import com.dynokore.gst.masterdata.application.connection.RotationSettings
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.domain.connection.HealthSettings
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import com.dynokore.gst.masterdata.application.connection.SecretStore as RotationSecretStore
import com.dynokore.platform.serviceruntime.binding.SecretStore as RuntimeSecretStore

/**
 * TPL-18: the health read, the state-change event, the credential rotation and the expiry reminder (INV-T-18776;
 * INV-REQ-836, INV-REQ-837). The two permissions are the scoped keys of the operations, decided per request through
 * [TenantPermission] (the replica); no role name appears here (INV-Q-10027). The four health settings have no default
 * in code: a start without one, or with a value `HealthSettings` refuses, fails.
 */
@Suppress("TooManyFunctions") // one bean method per wired part, as ConnectionUseCases does (INV-Q-4629)
@Configuration(proxyBeanMethods = false)
class ConnectionHealthUseCases {
    @Bean
    fun healthSettings(environment: Environment): HealthSettings =
        HealthSettings(
            window = Duration.parse(environment.getRequiredProperty(WINDOW)),
            failureThreshold = environment.getRequiredProperty(FAILURE_THRESHOLD, Int::class.java),
            openFor = Duration.parse(environment.getRequiredProperty(OPEN_FOR)),
            percentile = environment.getRequiredProperty(PERCENTILE, Int::class.java),
        )

    /** The overlap length: `masterdata.connections.overlap` (ISO-8601). Unset leaves rotation refused (fail closed). */
    @Bean
    fun rotationSettings(environment: Environment): RotationSettings =
        RotationSettings(environment.getProperty(OVERLAP)?.takeIf { it.isNotBlank() }?.let(Duration::parse))

    @Bean
    fun callRecords(
        dsl: DSLContext,
        clock: Clock,
    ) = JooqCallRecords(dsl, clock)

    @Bean
    fun connectionStateMemory(
        dsl: DSLContext,
        clock: Clock,
    ): ConnectionStateMemory = JooqConnectionStateMemory(dsl, clock)

    @Bean
    fun credentialStateStore(dsl: DSLContext): CredentialStateStore = JooqCredentialStateStore(dsl)

    @Bean
    fun credentialExpiries(dsl: DSLContext) = JooqCredentialExpiries(dsl)

    @Bean
    fun reminderLog(
        dsl: DSLContext,
        clock: Clock,
    ) = JooqReminderLog(dsl, clock)

    @Bean
    fun connectionStatusPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
    ): ConnectionStatusPublisher = ConnectionStatusChangedOutbox(outbox, mapper, tenants)

    @Bean
    fun credentialExpiryReminderPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        tenants: TenantContext,
    ): CredentialExpiryReminderPublisher = ConnectionCredentialExpiryReminderOutbox(outbox, mapper, tenants)

    @Bean
    fun healthPorts(
        store: ConnectionStore,
        calls: JooqCallRecords,
    ) = HealthPorts(store, calls)

    @Bean
    fun getConnectionHealth(
        tx: TransactionRunner,
        ports: HealthPorts,
        permission: TenantPermission,
        settings: HealthSettings,
        clock: Clock,
    ) = GetConnectionHealth(
        tx,
        ports,
        ConnectionHealthViewPermission { caller, key -> permission.holds(caller.value, key) },
        settings,
        clock,
    )

    /** The only caller of the status publisher: the recording path (INV-REQ-836 behavior 3). */
    @Bean
    @Suppress("LongParameterList") // the stores and ports of one evaluation
    fun evaluateConnectionStatus(
        tx: TransactionRunner,
        ports: HealthPorts,
        memory: ConnectionStateMemory,
        publisher: ConnectionStatusPublisher,
        settings: HealthSettings,
        clock: Clock,
    ) = EvaluateConnectionStatus(tx, ports, memory, publisher, settings, clock)

    /** Adapts the runtime secret store to the application's read port (the value proves the reference resolves). */
    @Bean
    fun rotationSecretStore(secrets: RuntimeSecretStore): RotationSecretStore =
        RotationSecretStore { ref ->
            try {
                secrets.read(SecretRef(ref)).reveal().toCharArray()
            } catch (_: SecretNotFound) {
                null
            }
        }

    @Bean
    fun rotationPorts(
        store: ConnectionStore,
        states: CredentialStateStore,
        secrets: RotationSecretStore,
        expiries: CredentialExpiryStore,
    ) = RotationPorts(store, states, secrets, expiries)

    @Bean
    fun rotateCredential(
        tx: TransactionRunner,
        ports: RotationPorts,
        permission: TenantPermission,
        settings: RotationSettings,
        clock: Clock,
    ) = RotateCredential(
        tx,
        ports,
        ConnectionCredentialRotatePermission { caller, key -> permission.holds(caller.value, key) },
        settings,
        clock,
    )

    @Bean
    fun credentialGate(
        tx: TransactionRunner,
        store: ConnectionStore,
        states: CredentialStateStore,
        clock: Clock,
    ) = CredentialGate(tx, store, states, clock)

    @Bean
    fun healthExtras(
        tx: TransactionRunner,
        expiries: JooqCredentialExpiries,
        states: CredentialStateStore,
        store: ConnectionStore,
    ) = HealthExtras(tx, expiries, states, store)

    @Bean
    fun reminderOffsets(
        policy: PolicyReader,
        tenants: TenantContext,
        mapper: ObjectMapper,
        clock: Clock,
    ): ReminderOffsets = PolicyReminderOffsets(policy, tenants, mapper, clock)

    @Bean
    @Suppress("LongParameterList") // the stores and ports of one check
    fun checkCredentialExpiry(
        tx: TransactionRunner,
        expiries: JooqCredentialExpiries,
        offsets: ReminderOffsets,
        log: JooqReminderLog,
        publisher: CredentialExpiryReminderPublisher,
        clock: Clock,
    ) = CheckCredentialExpiry(tx, expiries, offsets, log, publisher, clock)

    @Bean
    fun expiryTenantSource(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        principal: WorkloadPrincipal,
        transactions: TransactionRunner,
    ): ExpiryTenantSource = JooqExpiryTenantSource(dsl, platform, principal, transactions)

    @Bean
    fun checkCredentialExpiryForTenants(
        source: ExpiryTenantSource,
        tenants: TenantContext,
        check: CheckCredentialExpiry,
        principal: WorkloadPrincipal,
    ) = CheckCredentialExpiryForTenants(source, tenants, check, principal.value)

    companion object {
        const val WINDOW = "masterdata.connections.health.window"
        const val PERCENTILE = "masterdata.connections.health.percentile"
        const val FAILURE_THRESHOLD = "masterdata.connections.health.failure-threshold"
        const val OPEN_FOR = "masterdata.connections.health.open-for"
        const val OVERLAP = "masterdata.connections.overlap"
    }
}
