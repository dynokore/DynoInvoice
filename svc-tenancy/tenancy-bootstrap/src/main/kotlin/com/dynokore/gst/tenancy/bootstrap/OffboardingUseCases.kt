package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.JooqOffboardingStore
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.offboarding.OffboardTenant
import com.dynokore.gst.tenancy.application.offboarding.OffboardingStore
import com.dynokore.gst.tenancy.application.offboarding.TenantExport
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.application.offboarding.UnboundTenantExport
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * TPL-18: offboardTenant (INV-T-16412 use cases) over the boundary adapters (INV-T-16413). The export port and the
 * documents-count port belong to later-wave services and are unbound here (coordinator ruling INV-Q-4900): in profile
 * `prod` and in every other profile that binds none, a call is refused 422 POL-014 naming the unavailable input.
 */
@Configuration(proxyBeanMethods = false)
class OffboardingUseCases {
    @Bean
    fun offboardingStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): OffboardingStore = JooqOffboardingStore(dsl, tenants)

    /** The export is produced by the owning services, registered in Wave 4; until then it refuses naming its input. */
    @Bean
    @ConditionalOnMissingBean(TenantExport::class)
    fun tenantExport(): TenantExport = UnboundTenantExport(EXPORT_INPUT)

    /** The documents service owns the count; until it is bound the count is unreadable. */
    @Bean
    @ConditionalOnMissingBean(UnsubmittedDocuments::class)
    fun unsubmittedDocuments(): UnsubmittedDocuments =
        UnsubmittedDocuments {
            _,
            _,
            ->
            throw UnavailableInput(COUNT_INPUT)
        }

    @Bean
    @Suppress("LongParameterList")
    fun offboardTenant(
        tx: TransactionRunner,
        structure: StructureStore,
        store: OffboardingStore,
        documents: UnsubmittedDocuments,
        export: TenantExport,
        authority: ActionAuthority,
        tenants: TenantContext,
    ): OffboardTenant = OffboardTenant(tx, structure, store, documents, export, authority, tenants)

    private companion object {
        const val EXPORT_INPUT = "tenant-export"
        const val COUNT_INPUT = "unsubmitted-documents-count"
    }
}
