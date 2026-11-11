package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.OperatorSessionKeysChangedOutbox
import com.dynokore.gst.access.adapters.persistence.JooqOperatorKeyPublication
import com.dynokore.gst.access.adapters.token.LinkSessionKeyPublication
import com.dynokore.gst.access.adapters.token.LinkSessionSigner
import com.dynokore.gst.access.application.link.LinkSessionIssuer
import com.dynokore.gst.access.application.link.LinkTenants
import com.dynokore.gst.access.application.link.VerifySignatoryLinkUseCase
import com.dynokore.gst.access.application.token.TokenSettings
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import com.dynokore.gst.access.application.link.LinkSessionSigner as LinkSessionSignerPort

/** TPL-18: the link-session token of `verifySignatoryLink` (INV-T-17805): its signer, its key publication, its issuer. */
@Configuration(proxyBeanMethods = false)
class LinkSessionUseCases {
    /** The key rows and the key-feed record are written in one platform-bound transaction (INV-Q-16473 A). */
    @Bean
    fun linkSessionKeyPublication(
        dsl: DSLContext,
        tenants: PlatformTenantBinding,
        tx: TransactionRunner,
        events: OperatorSessionKeysChangedOutbox,
        environment: Environment,
    ): LinkSessionKeyPublication {
        val publication =
            JooqOperatorKeyPublication(
                dsl,
                tenants,
                tx,
                events,
                WorkloadPrincipal(environment.getRequiredProperty("gst.service")).value,
            )
        return LinkSessionKeyPublication { publication.publishLinkSessionKeys(it) }
    }

    @Bean
    fun linkSessionSigner(
        store: SecretStore,
        publication: LinkSessionKeyPublication,
        settings: TokenSettings,
        clock: Clock,
    ): LinkSessionSignerPort = LinkSessionSigner(store, publication, settings, clock)

    /** The tenant of a link is read under the platform binding, as the controller reads it. */
    @Bean
    fun linkSessionIssuer(
        linkTenants: LinkTenants,
        platform: PlatformTenantBinding,
        signer: LinkSessionSignerPort,
    ): LinkSessionIssuer =
        LinkSessionIssuer(
            { ref -> platform.bindPlatform(VerifySignatoryLinkUseCase.actorOf(ref)) { linkTenants.tenantOf(ref) } },
            signer,
        )
}
