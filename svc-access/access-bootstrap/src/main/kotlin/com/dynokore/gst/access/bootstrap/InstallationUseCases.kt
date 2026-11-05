package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.approval.ApprovalPolicyWriter
import com.dynokore.gst.access.adapters.approval.VariationPointApprovalSettings
import com.dynokore.gst.access.adapters.http.ApprovalOperations
import com.dynokore.gst.access.adapters.http.InstallationFactOperations
import com.dynokore.gst.access.adapters.http.InstallationJournal
import com.dynokore.gst.access.adapters.http.InstallerCaller
import com.dynokore.gst.access.adapters.http.JournalOutcome
import com.dynokore.gst.access.adapters.http.WorkloadInstallerCaller
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
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
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

/** TPL-18, continued: the installation approver fact (INV-REQ-992), constructed apart from [ApprovalUseCases]. */
@Configuration(proxyBeanMethods = false)
class InstallationUseCases {
    @Bean
    fun installationFacts(
        dsl: DSLContext,
        tenants: TenantContext,
    ): InstallationFacts = JooqInstallationFacts(dsl, tenants)

    @Bean
    fun recordInstallationFactUseCase(
        tx: TransactionRunner,
        facts: InstallationFacts,
        audit: GrantAudit,
        clock: Clock,
    ) = RecordInstallationFactUseCase(tx, facts, audit, clock)

    @Bean
    fun readInstallationFactUseCase(
        tx: TransactionRunner,
        facts: InstallationFacts,
    ) = ReadInstallationFactUseCase(tx, facts)

    /** The installer's workload is a subject configured as `deployment.operator` at INSTALLATION (INV-Q-8789 A). */
    @Bean
    fun installerCaller(
        request: HttpServletRequest,
        verifier: WorkloadTokenVerifier,
        installation: InstallationRoleProperties,
    ): InstallerCaller =
        WorkloadInstallerCaller(
            request,
            verifier,
            installation
                .entries()
                .filter { it.role == Role.DEPLOYMENT_OPERATOR }
                .map { it.subject }
                .toSet(),
        )

    /** The API-15 Idempotency-Key store of service-runtime, in the transaction of the record. */
    @Bean
    fun installationJournal(
        store: IdempotencyStore,
        tx: TransactionRunner,
        mapper: ObjectMapper,
    ): InstallationJournal = StoreBackedJournal(store, tx, mapper)

    @Suppress("LongParameterList") // one collaborator per port of the use case
    @Bean
    fun installationFactOperations(
        recordFact: RecordInstallationFactUseCase,
        readFact: ReadInstallationFactUseCase,
        installer: InstallerCaller,
        journal: InstallationJournal,
        platform: PlatformTenantBinding,
        tenants: TenantContext,
    ) = InstallationFactOperations(recordFact, readFact, installer, journal, platform, tenants)
}

/**
 * Runs the record once per Idempotency-Key (API-15) in one transaction under the platform binding the caller opened:
 * the same key and body return the stored fact and write nothing, the same key with another body is refused, a request
 * still running is in flight.
 */
class StoreBackedJournal(
    private val store: IdempotencyStore,
    private val tx: TransactionRunner,
    private val mapper: ObjectMapper,
) : InstallationJournal {
    override fun once(
        actor: String,
        key: UUID,
        fingerprint: String,
        record: () -> InstallationApproverFact,
    ): JournalOutcome =
        tx.inTransaction {
            when (
                val outcome =
                    store.once(null, actor, key, fingerprint) { _ ->
                        StoredResponse(OK, encode(record()))
                    }
            ) {
                is IdempotencyOutcome.Fresh -> JournalOutcome.Done(decode(outcome.response.body))
                is IdempotencyOutcome.Replay -> JournalOutcome.Done(decode(outcome.response.body))
                IdempotencyOutcome.InFlight -> JournalOutcome.InFlight
                IdempotencyOutcome.DifferentRequest -> JournalOutcome.DifferentRequest
            }
        }

    private fun encode(fact: InstallationApproverFact): String =
        mapper
            .createObjectNode()
            .put("distinctTaxApprover", fact.distinctTaxApprover)
            .put("recordedAt", fact.recordedAt.toString())
            .toString()

    private fun decode(body: String): InstallationApproverFact {
        val node = mapper.readTree(body)
        return InstallationApproverFact(
            node.get("distinctTaxApprover").asBoolean(),
            Instant.parse(node.get("recordedAt").asString()),
        )
    }

    private companion object {
        const val OK = 200
    }
}
