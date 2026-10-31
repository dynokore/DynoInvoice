package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.release.AccreditationTasks
import com.dynokore.gst.controlPlane.application.release.ConformanceCorpus
import com.dynokore.gst.controlPlane.application.release.TrainEvidenceSource
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.GoldenDocument
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The source of a platform operator's roles (regulatory.author, regulatory.approver) is an open question (the report of
 * INV-T-4984, gap G6). Until it is ruled this binding fails closed: nobody holds a role, so every operation
 * answers 403.
 */
@Configuration(proxyBeanMethods = false)
class OperatorRolesConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun operatorRoles(): OperatorRoles = OperatorRoles { _, _ -> false }

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
