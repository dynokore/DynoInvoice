package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.access.AuthoriseClient
import com.dynokore.gst.controlPlane.adapters.access.AuthoriseOperatorPermissions
import com.dynokore.gst.controlPlane.adapters.access.AuthoriseOperatorRoles
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.release.AccreditationTasks
import com.dynokore.gst.controlPlane.application.release.ConformanceCorpus
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.GoldenDocument
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.net.URI

/**
 * The operator roles (INV-Q-8687 A): with `controlplane.access.base-url` set they are decided by the live access-svc
 * `authorise` query with the platform scope; without it this binding fails closed: nobody holds a role, so every
 * operation answers 403.
 */
@Configuration(proxyBeanMethods = false)
class OperatorRolesConfiguration {
    @Bean
    @ConditionalOnProperty(name = [ACCESS_BASE_URL])
    fun authoriseClient(
        environment: Environment,
        workloadTokenSource: WorkloadTokenSource,
    ): AuthoriseClient =
        AuthoriseClient(
            URI.create(checkNotNull(environment.getProperty(ACCESS_BASE_URL))),
            workloadTokenSource,
            ::currentAuthorization,
        )

    @Bean
    @ConditionalOnProperty(name = [ACCESS_BASE_URL])
    fun operatorRolesLive(authorise: AuthoriseClient): OperatorRoles = AuthoriseOperatorRoles(authorise)

    @Bean
    @ConditionalOnMissingBean
    fun operatorRoles(): OperatorRoles = OperatorRoles { _, _ -> false }

    /**
     * Permission-level decisions (INV-T-9784, INV-Q-13897 A): with `controlplane.access.base-url` set they are decided
     * by the live `authorise` query; without it nobody holds a key (403).
     */
    @Bean
    @ConditionalOnMissingBean
    fun operatorPermissions(
        environment: Environment,
        authorise: ObjectProvider<AuthoriseClient>,
    ): OperatorPermissions =
        authorise.ifAvailable?.takeIf { environment.containsProperty(ACCESS_BASE_URL) }?.let {
            AuthoriseOperatorPermissions(it)
        } ?: OperatorPermissions { _, _ -> false }

    /** The golden corpus is a Wave 3 binding: unbound it is empty and every signature is refused. */
    @Bean
    @ConditionalOnMissingBean
    fun conformanceCorpus(): ConformanceCorpus =
        object : ConformanceCorpus {
            override fun documents(): List<GoldenDocument> = emptyList()

            override fun classify(
                pack: RegulatoryPack,
                document: GoldenDocument,
            ): String? = null
        }

    /**
     * The accreditation tasks belong to INV-T-7323. Until they are bound, a train that needs an assessment is released
     * with the check recorded and no alert or task reference: accreditation never holds a release back.
     */
    @Bean
    @ConditionalOnMissingBean
    fun accreditationTasks(): AccreditationTasks = AccreditationTasks { AccreditationCheck(true, null, null) }

    private companion object {
        const val ACCESS_BASE_URL = "controlplane.access.base-url"

        /** The operator's `Authorization` of the current request (as OperatorCaller.of); null without one. */
        fun currentAuthorization(): String? =
            (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
                ?.request
                ?.getHeader("Authorization")
    }
}
