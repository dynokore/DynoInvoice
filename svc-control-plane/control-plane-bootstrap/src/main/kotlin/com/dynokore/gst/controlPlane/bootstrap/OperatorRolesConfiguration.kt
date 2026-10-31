package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.access.AuthoriseOperatorRoles
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.release.AccreditationTasks
import com.dynokore.gst.controlPlane.application.release.ConformanceCorpus
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.release.TrainEvidenceSource
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.GoldenDocument
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.net.URI

/**
 * The operator roles (INV-Q-8687 A): with `controlplane.access.base-url` set they are decided by the live access-svc
 * `authorise` query with the platform scope; without it this binding fails closed: nobody holds a role, so every
 * operation answers 403.
 */
@Configuration(proxyBeanMethods = false)
class OperatorRolesConfiguration {
    @Bean
    @ConditionalOnProperty(name = ["controlplane.access.base-url"])
    fun operatorRolesLive(environment: Environment): OperatorRoles =
        AuthoriseOperatorRoles(URI.create(checkNotNull(environment.getProperty("controlplane.access.base-url"))))

    @Bean
    @ConditionalOnMissingBean
    fun operatorRoles(): OperatorRoles = OperatorRoles { _, _ -> false }

    /** Permission-level decisions are bound by INV-T-7322 (live `authorise`); until then nobody holds a key (403). */
    @Bean
    @ConditionalOnMissingBean
    fun operatorPermissions(): OperatorPermissions = OperatorPermissions { _, _ -> false }

    /** The release evidence source is a Wave 3 binding: unbound, no train has evidence and none is released. */
    @Bean
    @ConditionalOnMissingBean
    fun trainEvidenceSource(): TrainEvidenceSource = TrainEvidenceSource { null }

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
}
