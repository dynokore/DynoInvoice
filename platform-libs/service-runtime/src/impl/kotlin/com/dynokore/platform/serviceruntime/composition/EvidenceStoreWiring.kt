package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.evidencestore.JdbcLegalHoldRegistry
import com.dynokore.platform.evidencestore.LegalHoldRegistry
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.S3ObjectStore
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.s3.S3Client

/**
 * evidence-store's ports (INV-T-2875, INV-Q-3710), when the service embeds its impl half: the ledger and the
 * legal-hold register in the service's schema and the tables it names, and the §12.1 [ObjectStore] binding, the
 * implementation selected by `gst.bindings.object-store` (`s3-compatible`: [S3ObjectStore], STK-20) over the
 * service's `S3Client` and bucket.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.evidencestore.JdbcEvidenceLedger"])
class EvidenceStoreWiring {
    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnProperty(name = [Props.SCHEMA, Props.LEDGER_TABLE])
    @ConditionalOnMissingBean(EvidenceLedger::class)
    fun evidenceLedger(
        transactions: TransactionRunner,
        tenants: TenantContext,
        modules: ModuleProperties,
    ): JdbcEvidenceLedger {
        val schema = modules.requireSchema()
        val table = checkNotNull(modules.evidence.ledgerTable)
        return JdbcEvidenceLedger(dslFor(schema, table, tenants, transactions), schema, table)
    }

    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnProperty(name = [Props.SCHEMA, Props.LEGAL_HOLD_TABLE])
    @ConditionalOnMissingBean(LegalHoldRegistry::class)
    fun legalHoldRegistry(
        transactions: TransactionRunner,
        tenants: TenantContext,
        modules: ModuleProperties,
    ): JdbcLegalHoldRegistry {
        val schema = modules.requireSchema()
        val table = checkNotNull(modules.evidence.legalHoldTable)
        return JdbcLegalHoldRegistry(dslFor(schema, table, tenants, transactions), schema, table)
    }

    /**
     * The module's DSLContext (INV-T-4133, coordinator ruling INV-Q-5275): tenancy-jooq's factory over the
     * [TransactionRunner] and [TenantContext] beans, the module's table named with `DSL.name(schema, table)` and
     * declared `TENANT` (it carries `tenant_id` under forced row-level security). It is private to the class that
     * uses it and is no bean of the context.
     */
    private fun dslFor(
        schema: String,
        table: String,
        tenants: TenantContext,
        transactions: TransactionRunner,
    ): DSLContext = TenancyJooq.dslContext(mapOf(DSL.name(schema, table) to TableClass.TENANT), tenants, transactions)

    /** Its own class, so a service without the AWS SDK never loads a signature naming `S3Client`. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["software.amazon.awssdk.services.s3.S3Client"])
    @ConditionalOnProperty(name = [Props.OBJECT_STORE], havingValue = "s3-compatible", matchIfMissing = true)
    class S3ObjectStoreWiring {
        @Bean
        @ConditionalOnBean(S3Client::class)
        @ConditionalOnProperty(name = [Props.BUCKET])
        @ConditionalOnMissingBean(ObjectStore::class)
        fun objectStore(
            client: S3Client,
            modules: ModuleProperties,
        ): S3ObjectStore = S3ObjectStore(client, checkNotNull(modules.evidence.bucket))
    }
}
