package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.persistence.JooqSignatoryActions
import com.dynokore.gst.access.application.approval.RecordsMappingAcknowledgements
import com.dynokore.gst.access.application.consent.ConsentEffects
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.consent.ConsentWithdrawal
import com.dynokore.gst.access.application.consent.HandoverPorts
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.link.LinkPermissions
import com.dynokore.gst.access.application.link.RecordSignatoryActionUseCase
import com.dynokore.gst.access.application.link.SignatoryActions
import com.dynokore.gst.access.application.link.SignedLinks
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** TPL-18. `recordSignatoryAction` (INV-REQ-900): the evidence store, the link's scoped key and the use case. */
@Configuration(proxyBeanMethods = false)
class SignatoryActionUseCases {
    @Bean
    fun signatoryActions(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): SignatoryActions = JooqSignatoryActions(dsl, tenants, clock)

    @Bean
    fun linkPermissions(
        matrix: PermissionMatrix,
        directory: EntityDirectory,
    ) = LinkPermissions(matrix, directory)

    @Bean
    fun consentWithdrawal(
        consents: ConsentRepository,
        handover: HandoverPorts,
        effects: ConsentEffects,
        clock: Clock,
    ) = ConsentWithdrawal(consents, handover, effects, clock)

    @Suppress("LongParameterList") // one collaborator per port of the use case
    @Bean
    fun recordSignatoryActionUseCase(
        tx: TransactionRunner,
        links: SignedLinks,
        permissions: LinkPermissions,
        actions: SignatoryActions,
        consents: ConsentRepository,
        withdrawal: ConsentWithdrawal,
        acknowledgements: RecordsMappingAcknowledgements,
        audit: GrantAudit,
        clock: Clock,
    ) = RecordSignatoryActionUseCase(
        tx,
        links,
        permissions,
        actions,
        consents,
        withdrawal,
        acknowledgements,
        audit,
        clock,
    )
}
