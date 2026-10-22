package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.GatewayCaller
import com.dynokore.gst.access.adapters.http.WorkloadGatewayCaller
import com.dynokore.gst.access.adapters.persistence.JooqRevocationStore
import com.dynokore.gst.access.adapters.persistence.JooqVerificationKeyStore
import com.dynokore.gst.access.adapters.persistence.PlatformReads
import com.dynokore.gst.access.application.keys.CursorKey
import com.dynokore.gst.access.application.keys.RevocationList
import com.dynokore.gst.access.application.keys.RevocationStore
import com.dynokore.gst.access.application.keys.VerificationKeyStore
import com.dynokore.gst.access.application.keys.VerificationKeys
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock

/**
 * TPL-18: the wiring of the two read-side operations that serve the gateway (getVerificationKeys, getRevocationList)
 * and of the caller check that guards them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties::class)
class KeyServingUseCases {
    @Bean
    fun platformReads(
        tenants: PlatformTenantBinding,
        tx: TransactionRunner,
        environment: Environment,
    ) = PlatformReads(tenants, tx, WorkloadPrincipal(environment.getRequiredProperty("gst.service")).value)

    @Bean
    fun revocationStore(
        dsl: DSLContext,
        reads: PlatformReads,
    ): RevocationStore = JooqRevocationStore(dsl, reads)

    @Bean
    fun verificationKeyStore(
        dsl: DSLContext,
        reads: PlatformReads,
    ): VerificationKeyStore = JooqVerificationKeyStore(dsl, reads)

    /**
     * The cursor integrity key is read through SecretStore at every use (STK-29): a rotated secret needs no restart.
     */
    @Bean
    fun cursorKey(secrets: SecretStore): CursorKey =
        CursorKey { secrets.read(SecretRef(CURSOR_KEY_SECRET)).reveal().toByteArray(Charsets.UTF_8) }

    @Bean
    fun revocationList(
        store: RevocationStore,
        key: CursorKey,
        clock: Clock,
    ) = RevocationList(store, key, clock)

    @Bean
    fun verificationKeys(
        store: VerificationKeyStore,
        clock: Clock,
    ) = VerificationKeys(store, clock)

    /** The gateway's workload subject is configuration with no default: start-up fails when it is not set. */
    @Bean
    fun gatewayCaller(
        request: HttpServletRequest,
        verifier: WorkloadTokenVerifier,
        gateway: GatewayProperties,
    ): GatewayCaller = WorkloadGatewayCaller(request, verifier, gateway.workloadSubject)

    private companion object {
        const val CURSOR_KEY_SECRET = "access/revocation-cursor-key"
    }
}

/** The gateway's workload subject: configuration with no default, so start-up fails when it is not set. */
@ConfigurationProperties(prefix = "access.gateway")
class GatewayProperties(
    val workloadSubject: String,
)
