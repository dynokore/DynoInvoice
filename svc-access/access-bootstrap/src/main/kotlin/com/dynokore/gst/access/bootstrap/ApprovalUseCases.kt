package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.approval.ApprovalPolicyWriter
import com.dynokore.gst.access.adapters.approval.VariationPointApprovalSettings
import com.dynokore.gst.access.adapters.http.ApprovalOperations
import com.dynokore.gst.access.adapters.http.InstallationJournal
import com.dynokore.gst.access.adapters.http.JournalOutcome
import com.dynokore.gst.access.adapters.persistence.JooqIdentities
import com.dynokore.gst.access.adapters.persistence.JooqInstallationFacts
import com.dynokore.gst.access.adapters.persistence.JooqMappingAcknowledgements
import com.dynokore.gst.access.application.approval.ApprovalSettings
import com.dynokore.gst.access.application.approval.ApproverStandings
import com.dynokore.gst.access.application.approval.DecideOverrideReleaseUseCase
import com.dynokore.gst.access.application.approval.DecidePackApprovalUseCase
import com.dynokore.gst.access.application.approval.ErrorDispositions
import com.dynokore.gst.access.application.approval.Identities
import com.dynokore.gst.access.application.approval.InstallationApproverFact
import com.dynokore.gst.access.application.approval.InstallationFacts
import com.dynokore.gst.access.application.approval.MappingAcknowledgements
import com.dynokore.gst.access.application.approval.ReadInstallationFactUseCase
import com.dynokore.gst.access.application.approval.ReadMappingAcknowledgementUseCase
import com.dynokore.gst.access.application.approval.RecordInstallationFactUseCase
import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.catalogue.Disposition
import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.serviceruntime.http.IdempotencyOutcome
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServletRequest
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * TPL-18, continued: the separation-of-duty decisions on approval (INV-REQ-860, 992, 995, 996, 1264, 1265, 1293), the
 * mapping acknowledgement read and the installation approver fact, constructed here so that [UseCases] stays within
 * the function limit.
 */
@Configuration(proxyBeanMethods = false)
class ApprovalUseCases {
    companion object {
        /**
         * VP-03 and VP-32 are owned by access-svc: every `PolicyWriter` of the context is decorated so that a change by
         * a person who also holds `validation.override` needs the sole-operator acknowledgement and is audited with it
         * (INV-AC-2042). The collaborators are fetched when a change is made, so no cycle forms at start-up.
         */
        @Bean
        @JvmStatic
        fun soleOperatorOnPolicyChange(
            authorise: ObjectProvider<Authorise>,
            acks: ObjectProvider<SoleOperatorAcks>,
            audit: ObjectProvider<GrantAudit>,
        ): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(
                    bean: Any,
                    beanName: String,
                ): Any =
                    if (bean is PolicyWriter && bean !is ApprovalPolicyWriter) {
                        ApprovalPolicyWriter(
                            bean,
                            { authorise.getObject() },
                            { acks.getObject() },
                            { audit.getObject() },
                        )
                    } else {
                        bean
                    }
            }
    }

    /** VP-03, VP-04 and VP-32 as `setPolicy` of service-runtime stores them and the config-resolver resolves them. */
    @Bean
    fun approvalSettings(
        reader: PolicyReader,
        tenants: TenantContext,
        clock: Clock,
        mapper: ObjectMapper,
    ): ApprovalSettings = VariationPointApprovalSettings(reader, tenants, clock, mapper)

    @Bean
    fun identities(dsl: DSLContext): Identities = JooqIdentities(dsl)

    @Bean
    fun mappingAcknowledgements(dsl: DSLContext): MappingAcknowledgements = JooqMappingAcknowledgements(dsl)

    /** A code is overridable only when the error catalogue holds it, by exact equality, as OVERRIDABLE. */
    @Bean
    fun errorDispositions(): ErrorDispositions =
        ErrorDispositions { code ->
            ErrorCode.entries.any { it.code == code && it.disposition == Disposition.OVERRIDABLE }
        }

    @Bean
    fun approverStandings(
        authorise: Authorise,
        identities: Identities,
    ) = ApproverStandings(authorise, identities)

    @Suppress("LongParameterList") // one collaborator per port of the use case
    @Bean
    fun decidePackApprovalUseCase(
        tx: TransactionRunner,
        settings: ApprovalSettings,
        standings: ApproverStandings,
        consents: ConsentRepository,
        acks: SoleOperatorAcks,
        clock: Clock,
    ) = DecidePackApprovalUseCase(tx, settings, standings, consents, acks, clock)

    @Bean
    fun decideOverrideReleaseUseCase(
        tx: TransactionRunner,
        settings: ApprovalSettings,
        dispositions: ErrorDispositions,
        authorise: Authorise,
        acks: SoleOperatorAcks,
    ) = DecideOverrideReleaseUseCase(tx, settings, dispositions, authorise, acks)

    @Bean
    fun readMappingAcknowledgementUseCase(
        tx: TransactionRunner,
        store: MappingAcknowledgements,
    ) = ReadMappingAcknowledgementUseCase(tx, store)

    @Bean
    fun approvalOperations(
        packApproval: DecidePackApprovalUseCase,
        overrideRelease: DecideOverrideReleaseUseCase,
        acknowledgements: ReadMappingAcknowledgementUseCase,
    ) = ApprovalOperations(packApproval, overrideRelease, acknowledgements)
}
