package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditSchema
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.auditlog.ErrorCatalogue
import com.dynokore.platform.auditlog.JdbcOverrideService
import com.dynokore.platform.auditlog.LocalAuditWriter
import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.auditlog.OverridePolicy
import com.dynokore.platform.auditlog.OverrideService
import com.dynokore.platform.auditlog.TransactionalAuditedOperations
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * audit-log's ports (INV-T-2875, INV-Q-3710), when the service embeds its impl half. The [AuditWriter] is
 * [OutboxAuditWriter] in a service with an outbox, else [LocalAuditWriter] in the service's schema (a service
 * without an outbox, `control-plane-svc`, INV-Q-2958). [JdbcOverrideService] needs the service's [ErrorCatalogue]
 * (an [OverridePolicy] bean is used when declared). Every [AuditWriter] bean, a service's own included, is decorated
 * by [OperatorSessionAuditWriter], so an entry recorded under an operator session carries its case (INV-Q-4521).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.auditlog.OutboxAuditWriter"])
class AuditLogWiring {
    companion object {
        /** Static, so it is registered before any [AuditWriter] bean is created. */
        @JvmStatic
        @Bean
        fun operatorSessionAuditWriterPostProcessor(): OperatorSessionAuditWriterPostProcessor =
            OperatorSessionAuditWriterPostProcessor()
    }

    @Bean
    @ConditionalOnBean(OutboxWriter::class)
    @ConditionalOnMissingBean(AuditWriter::class)
    fun outboxAuditWriter(outbox: OutboxWriter): OutboxAuditWriter = OutboxAuditWriter(outbox)

    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnProperty(name = [Props.SERVICE, Props.SCHEMA])
    @ConditionalOnMissingBean(AuditWriter::class, OutboxWriter::class)
    fun localAuditWriter(
        transactions: TransactionRunner,
        tenants: TenantContext,
        gst: GstProperties,
        modules: ModuleProperties,
        clock: Clock,
    ): LocalAuditWriter {
        val schema = modules.requireSchema()
        return LocalAuditWriter(
            dslFor(schema, AuditSchema.AUDIT_ENTRY, tenants, transactions),
            schema,
            gst.requireService(),
            gst.requireInstanceId(),
            clock,
        )
    }

    @Bean
    @ConditionalOnBean(TransactionRunner::class, AuditWriter::class)
    @ConditionalOnMissingBean(AuditedOperations::class)
    fun auditedOperations(
        transactions: TransactionRunner,
        audit: AuditWriter,
    ): TransactionalAuditedOperations = TransactionalAuditedOperations(transactions, audit)

    @Bean
    @ConditionalOnBean(TransactionRunner::class, AuditWriter::class, ErrorCatalogue::class)
    @ConditionalOnProperty(name = [Props.SCHEMA])
    @ConditionalOnMissingBean(OverrideService::class)
    @Suppress("LongParameterList")
    fun overrideService(
        transactions: TransactionRunner,
        tenants: TenantContext,
        audit: AuditWriter,
        errors: ErrorCatalogue,
        policy: ObjectProvider<OverridePolicy>,
        modules: ModuleProperties,
        clock: Clock,
    ): JdbcOverrideService {
        val schema = modules.requireSchema()
        return JdbcOverrideService(
            dslFor(schema, AuditSchema.OVERRIDE_GRANT, tenants, transactions),
            transactions,
            audit,
            errors,
            schema,
            clock,
            policy
                .getIfAvailable {
                    OverridePolicy()
                },
        )
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
}
