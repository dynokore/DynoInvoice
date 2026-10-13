package com.dynokore.platform.serviceruntime.composition

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
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TransactionRunner
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
 * (an [OverridePolicy] bean is used when declared).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.auditlog.OutboxAuditWriter"])
class AuditLogWiring {
    @Bean
    @ConditionalOnBean(OutboxWriter::class)
    @ConditionalOnMissingBean(AuditWriter::class)
    fun outboxAuditWriter(outbox: OutboxWriter): OutboxAuditWriter = OutboxAuditWriter(outbox)

    @Bean
    @ConditionalOnBean(CurrentConnection::class)
    @ConditionalOnProperty(name = [Props.SERVICE, Props.SCHEMA])
    @ConditionalOnMissingBean(AuditWriter::class, OutboxWriter::class)
    fun localAuditWriter(
        connections: CurrentConnection,
        gst: GstProperties,
        modules: ModuleProperties,
        clock: Clock,
    ): LocalAuditWriter = LocalAuditWriter(connections, modules.requireSchema(), gst.requireService(), gst.requireInstanceId(), clock)

    @Bean
    @ConditionalOnBean(TransactionRunner::class, AuditWriter::class)
    @ConditionalOnMissingBean(AuditedOperations::class)
    fun auditedOperations(
        transactions: TransactionRunner,
        audit: AuditWriter,
    ): TransactionalAuditedOperations = TransactionalAuditedOperations(transactions, audit)

    @Bean
    @ConditionalOnBean(CurrentConnection::class, TransactionRunner::class, AuditWriter::class, ErrorCatalogue::class)
    @ConditionalOnProperty(name = [Props.SCHEMA])
    @ConditionalOnMissingBean(OverrideService::class)
    @Suppress("LongParameterList")
    fun overrideService(
        connections: CurrentConnection,
        transactions: TransactionRunner,
        audit: AuditWriter,
        errors: ErrorCatalogue,
        policy: ObjectProvider<OverridePolicy>,
        modules: ModuleProperties,
        clock: Clock,
    ): JdbcOverrideService = JdbcOverrideService(connections, transactions, audit, errors, modules.requireSchema(), clock, policy.getIfAvailable { OverridePolicy() })
}
