package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.GatewayAuthentication
import com.dynokore.gst.access.adapters.http.GatewayTokenIssuance
import com.dynokore.gst.access.adapters.persistence.JooqVerificationKeyRegistry
import com.dynokore.gst.access.adapters.token.NimbusAccessTokenSigner
import com.dynokore.gst.access.application.token.AccessTokenSigner
import com.dynokore.gst.access.application.token.IssueAccessTokenUseCase
import com.dynokore.gst.access.application.token.TokenSettings
import com.dynokore.gst.access.application.token.VerificationKeyRegistry
import com.dynokore.gst.access.domain.principal.MachinePermissionSets
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.Duration

/**
 * The machine access token settings (INV-T-8850). The issuer, the audience and the key rotation interval have no
 * default, so start-up fails when one is not set; the lifetime defaults to 900 seconds, the ceiling.
 */
@ConfigurationProperties(prefix = "access.token")
class TokenProperties(
    val issuer: String,
    val audience: String,
    val lifetimeSeconds: Long = TokenSettings.DEFAULT_LIFETIME_SECONDS,
    val keyRotationInterval: Duration,
)

/** TPL-18: the wiring of `issueAccessToken`; the signing key is read through the SecretStore binding at every use. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TokenProperties::class)
class TokenUseCases {
    @Bean
    fun tokenSettings(properties: TokenProperties) =
        TokenSettings(
            issuer = properties.issuer,
            audience = properties.audience,
            lifetime = Duration.ofSeconds(properties.lifetimeSeconds),
            keyRotationInterval = properties.keyRotationInterval,
        )

    @Bean
    fun accessTokenSigner(secrets: SecretStore): AccessTokenSigner = NimbusAccessTokenSigner(secrets)

    @Bean
    fun verificationKeyRegistry(
        dsl: DSLContext,
        tenants: PlatformTenantBinding,
        tx: TransactionRunner,
        environment: Environment,
    ): VerificationKeyRegistry =
        JooqVerificationKeyRegistry(
            dsl,
            tenants,
            tx,
            WorkloadPrincipal(environment.getRequiredProperty("gst.service")).value,
        )

    @Bean
    fun issueAccessTokenUseCase(
        signer: AccessTokenSigner,
        registry: VerificationKeyRegistry,
        sets: MachinePermissionSets,
        settings: TokenSettings,
        clock: Clock,
    ) = IssueAccessTokenUseCase(signer, registry, sets, settings, clock)

    @Bean
    fun gatewayTokenIssuance(
        authentication: GatewayAuthentication,
        issuer: IssueAccessTokenUseCase,
    ) = GatewayTokenIssuance(authentication, issuer)
}
