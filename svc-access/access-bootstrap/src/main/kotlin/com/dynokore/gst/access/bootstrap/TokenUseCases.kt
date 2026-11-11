package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.GatewayAuthentication
import com.dynokore.gst.access.adapters.http.GatewayTokenIssuance
import com.dynokore.gst.access.adapters.structure.ReplicaTenantEnvironments
import com.dynokore.gst.access.adapters.token.NimbusAccessTokenSigner
import com.dynokore.gst.access.application.operatorsession.OperatorKeyPublication
import com.dynokore.gst.access.application.token.AccessTokenSigner
import com.dynokore.gst.access.application.token.IssueAccessTokenUseCase
import com.dynokore.gst.access.application.token.PublishingVerificationKeyRegistry
import com.dynokore.gst.access.application.token.TenantEnvironments
import com.dynokore.gst.access.application.token.TokenSettings
import com.dynokore.gst.access.application.token.VerificationKeyRegistry
import com.dynokore.gst.access.domain.principal.MachinePermissionSets
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    fun verificationKeyRegistry(publication: OperatorKeyPublication): VerificationKeyRegistry =
        PublishingVerificationKeyRegistry(publication)

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
        environments: TenantEnvironments,
    ) = GatewayTokenIssuance(authentication, issuer, environments)

    /** The tenant environment reader; the `createPrincipal` slice injects this bean (INV-T-11816). */
    @Bean
    fun tenantEnvironments(structure: StructureReplica): TenantEnvironments = ReplicaTenantEnvironments(structure)
}
