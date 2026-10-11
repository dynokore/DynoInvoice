package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.connection.ConnectionStatusReporter
import com.dynokore.platform.serviceruntime.connection.OutboxConnectionStatusReporter
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.events.OutboxEvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.jobs.DeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.serviceruntime.operation.TransactionalOperationRunner
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * service-runtime's own ports over the embedded modules (INV-T-2875): the operation contract's
 * [OperationRunner] over the [TransactionRunner] and [AuditWriter]; and, with an outbox, the platform-event
 * writers — [ConnectionStatusReporter], the only publisher of `ConnectionStatusChanged` (INV-REQ-836 b4,
 * INV-Q-3730), and [EvidenceArtefactRecorder]. The [JobCommands] ([JobRuntime]) are created when the service
 * declares its [JobCommandAuthorizer] and [DeadlineSource] (no platform-libs implementation; the service's,
 * against its authority replica, CS-23) and its worker DataSource; run-now bodies run on the service's
 * `jobRunNowExecutor` when it declares one, else on virtual threads.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.outboxandqueue.TenantScope", "com.dynokore.platform.tenancykernel.TransactionRunnerImpl"])
class ServiceRuntimeWiring {
    @Bean
    @ConditionalOnBean(TransactionRunner::class, CurrentConnection::class, AuditWriter::class)
    @ConditionalOnMissingBean(OperationRunner::class)
    fun operationRunner(
        transactions: TransactionRunner,
        connections: CurrentConnection,
        audit: AuditWriter,
    ): TransactionalOperationRunner = TransactionalOperationRunner(transactions, connections, audit)

    @Bean
    @ConditionalOnBean(TenantScope::class, OutboxWriter::class)
    @ConditionalOnMissingBean(ConnectionStatusReporter::class)
    fun connectionStatusReporter(
        scope: TenantScope,
        outbox: OutboxWriter,
    ): OutboxConnectionStatusReporter = OutboxConnectionStatusReporter(scope, outbox)

    @Bean
    @ConditionalOnBean(OutboxWriter::class)
    @ConditionalOnMissingBean(EvidenceArtefactRecorder::class)
    fun evidenceArtefactRecorder(outbox: OutboxWriter): OutboxEvidenceArtefactRecorder = OutboxEvidenceArtefactRecorder(outbox)

    @Bean
    @ConditionalOnBean(
        value = [TenantScope::class, OutboxWriter::class, AuditWriter::class, JobCommandAuthorizer::class, DeadlineSource::class],
        name = [ServiceDataSources.WORKER],
    )
    @ConditionalOnProperty(name = [Props.SERVICE, Props.SCHEMA])
    @ConditionalOnMissingBean(JobCommands::class)
    @Suppress("LongParameterList")
    fun jobRuntime(
        gst: GstProperties,
        modules: ModuleProperties,
        scope: TenantScope,
        outbox: OutboxWriter,
        audit: AuditWriter,
        @Qualifier(ServiceDataSources.WORKER) worker: DataSource,
        authorizer: JobCommandAuthorizer,
        deadlines: DeadlineSource,
        clock: Clock,
        @Qualifier(RUN_NOW_EXECUTOR) executor: ObjectProvider<Executor>,
    ): JobRuntime = JobRuntime(
        gst.requireService(),
        modules.requireSchema(),
        scope,
        outbox,
        audit,
        worker,
        authorizer,
        deadlines,
        clock,
        executor.getIfAvailable { Executors.newVirtualThreadPerTaskExecutor() },
    )

    companion object {
        /** The bean name of the service's executor for run-now bodies (optional). */
        const val RUN_NOW_EXECUTOR = "jobRunNowExecutor"
    }
}
