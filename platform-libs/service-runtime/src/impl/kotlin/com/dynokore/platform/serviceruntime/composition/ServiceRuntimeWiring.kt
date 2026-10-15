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
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * service-runtime's own ports over the embedded modules (INV-T-2875): the operation contract's
 * [OperationRunner] over the [TransactionRunner] and [AuditWriter]; and, with an outbox, the platform-event
 * writers — [ConnectionStatusReporter], the only publisher of `ConnectionStatusChanged` (INV-REQ-836 b4,
 * INV-Q-3730), and [EvidenceArtefactRecorder]. The [JobCommands] ([JobRuntime]) are created when the service
 * declares its [JobCommandAuthorizer] and [DeadlineSource] (no platform-libs implementation; the service's,
 * against its authority replica, CS-23), on its one application DataSource (INV-REQ-1230 b1) with the job retention
 * of `service-runtime.jobs.retention` ([JobProperties]); run-now bodies run on the service's `jobRunNowExecutor` when
 * it declares one, else on virtual threads. The job runtime refuses to start (`JobRuntime.announce`) when that
 * retention differs from the migration-written job-retention row (coordinator ruling INV-Q-4346 (A)).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(
    name = [
        "com.dynokore.platform.outboxandqueue.TenantScope",
        "com.dynokore.platform.tenancykernel.TransactionRunnerImpl",
    ],
)
@EnableConfigurationProperties(JobProperties::class)
class ServiceRuntimeWiring {
    @Bean
    @ConditionalOnBean(TransactionRunner::class, AuditWriter::class)
    @ConditionalOnMissingBean(OperationRunner::class)
    fun operationRunner(
        transactions: TransactionRunner,
        dsl: ObjectProvider<DSLContext>,
        audit: AuditWriter,
    ): TransactionalOperationRunner = TransactionalOperationRunner(transactions, dsl.orConnectionless(), audit)

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
    fun evidenceArtefactRecorder(outbox: OutboxWriter): OutboxEvidenceArtefactRecorder =
        OutboxEvidenceArtefactRecorder(outbox)

    @Bean
    @ConditionalOnBean(
        TenantScope::class,
        OutboxWriter::class,
        AuditWriter::class,
        JobCommandAuthorizer::class,
        DeadlineSource::class,
    )
    @ConditionalOnProperty(name = [Props.SERVICE, Props.SCHEMA])
    @ConditionalOnMissingBean(JobCommands::class)
    @Suppress("LongParameterList")
    fun jobRuntime(
        gst: GstProperties,
        modules: ModuleProperties,
        scope: TenantScope,
        dsl: ObjectProvider<DSLContext>,
        outbox: OutboxWriter,
        audit: AuditWriter,
        authorizer: JobCommandAuthorizer,
        deadlines: DeadlineSource,
        clock: Clock,
        @Qualifier(RUN_NOW_EXECUTOR) executor: ObjectProvider<Executor>,
        jobs: JobProperties,
    ): JobRuntime =
        JobRuntime(
            gst.requireService(),
            modules.requireSchema(),
            scope,
            dsl.orConnectionless(),
            outbox,
            audit,
            authorizer,
            deadlines,
            clock,
            executor.getIfAvailable { Executors.newVirtualThreadPerTaskExecutor() },
            jobs.retention,
        )

    companion object {
        /** The bean name of the service's executor for run-now bodies (optional). */
        const val RUN_NOW_EXECUTOR = "jobRunNowExecutor"
    }
}

/**
 * The DSLContext of tenancy-jooq's factory, which TenancyJooqWiring defines whenever the context has a TransactionRunner
 * (start-up fails without a generated schema, INV-Q-4815). A context composed without it (a wiring test on no database)
 * gets a connectionless DSLContext, which runs no statement.
 */
internal fun ObjectProvider<DSLContext>.orConnectionless(): DSLContext =
    getIfAvailable { DSL.using(SQLDialect.POSTGRES) }
