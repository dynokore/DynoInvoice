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
import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * TPL-18: offboardTenant (INV-T-16412 use cases) over the boundary adapters (INV-T-16413). The export port
 * belongs to a later-wave service and is unbound here (coordinator ruling INV-Q-4900). The documents-count
 * port is bound to the read model by [OffboardingCountConfiguration]; a context without it refuses 422 POL-014.
 */
@Configuration(proxyBeanMethods = false)
@Import(OffboardingCountConfiguration::class)
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

    /** The default when no read-model binding is loaded: the count is unreadable. */
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
        tenantStore: TenantStore,
    ): OffboardTenant = OffboardTenant(tx, structure, store, documents, export, authority, tenants, tenantStore)

    private companion object {
        const val EXPORT_INPUT = "tenant-export"
        const val COUNT_INPUT = "unsubmitted-documents-count"
    }
}
