package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.http.RotationWindow
import com.dynokore.gst.transport.adapters.persistence.JooqAdapterCredentialStates
import com.dynokore.gst.transport.adapters.persistence.JooqCredentialRotationLog
import com.dynokore.gst.transport.adapters.vault.SecretStoreCredentialVault
import com.dynokore.gst.transport.application.CredentialRef
import com.dynokore.gst.transport.application.CredentialVault
import com.dynokore.gst.transport.application.RotateCredential
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * `rotateCredential` (INV-REQ-837, pattern of INV-REQ-880; INV-T-18676): the credential state and rotation log over the
 * jOOQ tables, the vault port over the platform `SecretStore` (STK-29) and the use case. The overlap window is
 * `transport.ap.rotation-overlap`, with no default.
 */
@Configuration(proxyBeanMethods = false)
class CredentialConfiguration {
    /** The initial reference of a configured adapter is `ap/<adapterId>`, as its connection record carried before. */
    @Bean
    fun credentialStates(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
        properties: ApProperties,
        clock: Clock,
    ): JooqAdapterCredentialStates {
        val configured = properties.adapters.map { it.id }.toSet()
        return JooqAdapterCredentialStates(dsl, runner, tenants, clock) { adapter ->
            CredentialRef("ap/${adapter.value}").takeIf { adapter.value in configured }
        }
    }

    @Bean
    fun credentialRotationLog(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
    ): JooqCredentialRotationLog = JooqCredentialRotationLog(dsl, runner, tenants)

    @Bean
    fun credentialVault(secrets: SecretStore): CredentialVault = SecretStoreCredentialVault(secrets)

    @Bean
    fun rotateCredential(
        states: JooqAdapterCredentialStates,
        vault: CredentialVault,
        log: JooqCredentialRotationLog,
        clock: Clock,
    ): RotateCredential = RotateCredential(states, vault, log, clock)

    @Bean
    fun rotationWindow(properties: ApProperties): RotationWindow = RotationWindow(properties.rotationOverlap)
}
