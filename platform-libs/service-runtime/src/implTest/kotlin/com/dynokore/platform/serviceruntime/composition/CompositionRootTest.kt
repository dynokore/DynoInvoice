package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.adapterruntime.connection.InMemoryConnectionStatusReporter
import com.dynokore.platform.adapterruntime.deadletter.InMemoryDeadLetterSink
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.auditlog.ErrorCatalogue
import com.dynokore.platform.auditlog.LocalAuditWriter
import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.auditlog.OverrideService
import com.dynokore.platform.configresolver.ConfigPurge
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.ResolutionRecorder
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.crypto.BouncyCastleCms
import com.dynokore.platform.crypto.DetachedSignatureVerifier
import com.dynokore.platform.crypto.DetachedSigner
import com.dynokore.platform.evidencestore.EvidenceLedger
import com.dynokore.platform.evidencestore.LegalHoldRegistry
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.S3ObjectStore
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.DeadLetterTriage
import com.dynokore.platform.outboxandqueue.EventBinding
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogPurge
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.LaneLimits
import com.dynokore.platform.outboxandqueue.LaneRateLimiter
import com.dynokore.platform.outboxandqueue.OutboxRelay
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.PostgresEventBinding
import com.dynokore.platform.outboxandqueue.PostgresEventLogReader
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.Retention
import com.dynokore.platform.outboxandqueue.RetentionPurge
import com.dynokore.platform.outboxandqueue.StalenessBreachSink
import com.dynokore.platform.outboxandqueue.StalenessMonitor
import com.dynokore.platform.outboxandqueue.StalenessRule
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.rulepackkernel.InMemoryPackRegistry
import com.dynokore.platform.rulepackkernel.PackContentValidator
import com.dynokore.platform.rulepackkernel.PackRegistry
import com.dynokore.platform.rulepackkernel.PackVerifier
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.connection.ConnectionStatusReporter
import com.dynokore.platform.serviceruntime.connection.OutboxConnectionStatusReporter
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.http.IdempotencyKeyPurgeJob
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.jobs.DeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.LicenceCheck
import com.dynokore.platform.tenancykernel.LicenceStatus
import com.dynokore.platform.tenancykernel.NonBlockingLicenceCheck
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.ServletRegistrationBean
import software.amazon.awssdk.services.s3.S3Client
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.jar.JarFile
import javax.sql.DataSource
import kotlin.reflect.KClass
import com.dynokore.platform.adapterruntime.ConnectionStatusReporter as AdapterConnectionStatusReporter
import com.dynokore.platform.adapterruntime.DeadLetterSink as AdapterDeadLetterSink
import com.dynokore.platform.adapterruntime.SecretRef as AdapterSecretRef
import com.dynokore.platform.adapterruntime.SecretStore as AdapterSecretStore
import com.dynokore.platform.serviceruntime.binding.SecretRef as BoundSecretRef

/**
 * The L1 composition root (INV-T-2875 done-when 1; INV-Q-3710, INV-Q-3730, INV-Q-3732): started with every L1
 * module present, with and without the service-only inputs, and with service-runtime alone.
 */
class CompositionRootTest {
    private val base =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withConfiguration(AutoConfigurations.of(ServiceRuntimeConfiguration::class.java, L1CompositionAutoConfiguration::class.java))
            .withPropertyValues("service-runtime.workload-identity.audience=submission-svc")

    /**
     * The inputs only a service has: its role DataSources, catalogues, limits, sinks, S3 client and ports, each
     * under its key in [ServiceInput]; [without] withholds one of them.
     */
    private fun ApplicationContextRunner.withServiceInputs(
        mountRoot: Path,
        without: ServiceInput? = null,
    ): ApplicationContextRunner {
        var runner = withBean("dataSource", DataSource::class.java, { mockk<DataSource>(relaxed = true) }, { it.isPrimary = true })
            .withPropertyValues("gst.instance-id=submission-svc-0", "service-runtime.idempotency.schema=submission", "gst.bindings.secret-store.mount-root=$mountRoot")
        ServiceInput.entries.filter { it != without }.forEach { runner = it.declare(runner) }
        return runner
    }

    /** A service-only input, and the ports that exist only when the service declares it (the README port table). */
    enum class ServiceInput(
        val declare: (ApplicationContextRunner) -> ApplicationContextRunner,
        val dependents: Set<KClass<*>>,
    ) {
        WORKER_DATA_SOURCE(
            dataSource(ServiceDataSources.WORKER),
            setOf(
                OutboxRelay::class, LaneRateLimiter::class, WorkQueue::class, DeadLetterTriage::class, RetentionPurge::class,
                StalenessMonitor::class, InboxConsumer::class, ReplayDelivery::class, JobCommands::class, IdempotencyStore::class, IdempotencyKeyPurgeJob::class,
            ),
        ),

        // INV-Q-4268: optional, the application DataSource serves the event log without it
        EVENT_LOG_DATA_SOURCE(dataSource(ServiceDataSources.EVENT_LOG), emptySet()),
        EVENT_CATALOGUE(
            { it.withBean(EventCatalogue::class.java, { EventCatalogue(listOf(CatalogueEntry("SubmissionFailed", "submission", setOf("submission-svc"), contentTopic = false))) }) },
            // without an outbox the service gets the local audit writer instead
            setOf(
                OutboxWriter::class,
                EventLogPurge::class,
                StalenessMonitor::class,
                ConnectionStatusReporter::class,
                EvidenceArtefactRecorder::class,
                JobCommands::class,
                IdempotencyStore::class,
                IdempotencyKeyPurgeJob::class,
            ) + INBOX, // the inbox consumer takes its record topics from the catalogue (INV-Q-3809)
        ),
        RETENTION({ it.withBean(Retention::class.java, { Retention() }) }, setOf(RetentionPurge::class, EventLogPurge::class)),
        LANE_LIMITS({ it.withBean(LaneLimits::class.java, { LaneLimits() }) }, setOf(LaneRateLimiter::class, WorkQueue::class, ReplayDelivery::class)),
        STALENESS_RULE(
            { it.withBean(StalenessRule::class.java, { StalenessRule("submission-svc", "SubmissionFailed", Duration.ofMinutes(5)) }) },
            setOf(StalenessMonitor::class),
        ),

        // INV-T-3005: optional, the monitor falls back to a logging sink (the incident is the alert rule's, INV-Q-3030)
        STALENESS_BREACH_SINK({ it.withBean(StalenessBreachSink::class.java, { StalenessBreachSink { } }) }, emptySet()),
        CONSUMER_SUBSCRIPTION(
            { it.withBean(ConsumerSubscription::class.java, { ConsumerSubscription("submission-svc", "canonical", "submission") }) },
            INBOX,
        ),
        EVENT_HANDLER({ it.withBean(EventHandler::class.java, { mockk<EventHandler>(relaxed = true) }) }, INBOX),
        REBUILD_REQUIRED_SINK({ it.withBean(RebuildRequiredSink::class.java, { RebuildRequiredSink { } }) }, INBOX),
        ERROR_CATALOGUE({ it.withBean(ErrorCatalogue::class.java, { ErrorCatalogue { null } }) }, setOf(OverrideService::class)),
        VARIATION_POINT_CATALOGUE(
            { it.withBean(VariationPointCatalogue::class.java, { mockk<VariationPointCatalogue>(relaxed = true) }) },
            setOf(PolicyReader::class, PolicyHistory::class, ConfigPurge::class, ResolutionRecorder::class, PolicyWriter::class),
        ),
        S3_CLIENT({ it.withBean(S3Client::class.java, { mockk<S3Client>(relaxed = true) }) }, setOf(ObjectStore::class)),
        JOB_COMMAND_AUTHORIZER(
            { it.withBean(JobCommandAuthorizer::class.java, { mockk<JobCommandAuthorizer>(relaxed = true) }) },
            setOf(JobCommands::class, IdempotencyStore::class, IdempotencyKeyPurgeJob::class),
        ),
        DEADLINE_SOURCE(
            { it.withBean(DeadlineSource::class.java, { mockk<DeadlineSource>(relaxed = true) }) },
            setOf(JobCommands::class, IdempotencyStore::class, IdempotencyKeyPurgeJob::class),
        ),
        END_USER_PRINCIPAL_RESOLVER(
            { it.withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } }) },
            setOf(IdempotencyStore::class, IdempotencyKeyPurgeJob::class),
        ),
        BASE_LICENCE_CHECK(
            { it.withBean("baseLicenceCheck", LicenceCheck::class.java, { LicenceCheck { LicenceStatus.entries.first() } }) },
            setOf(LicenceCheck::class),
        ),
        SERVICE_NAME(
            { it.withPropertyValues("gst.service=submission-svc") },
            // no outbox writer and no local audit writer: nothing that audits; and no workload principal
            // (`svc:<gst.service>`, INV-Q-4268): no TenantScope, so nothing that binds a tenant in the background
            setOf(
                OutboxWriter::class, AuditWriter::class, AuditedOperations::class, OverrideService::class, PolicyWriter::class,
                OperationRunner::class, ConnectionStatusReporter::class, EvidenceArtefactRecorder::class, JobCommands::class,
                IdempotencyStore::class, IdempotencyKeyPurgeJob::class, TenantScope::class, EventBinding::class, OutboxRelay::class,
                EventLogReader::class, WorkQueue::class, DeadLetterTriage::class, StalenessMonitor::class,
            ) + INBOX,
        ),
        SCHEMA(
            { it.withPropertyValues("service-runtime.modules.schema=submission") },
            setOf(
                OutboxWriter::class, AuditWriter::class, AuditedOperations::class, OverrideService::class, OutboxRelay::class,
                LaneRateLimiter::class, WorkQueue::class, DeadLetterTriage::class, RetentionPurge::class, StalenessMonitor::class,
                ReplayDelivery::class, PolicyReader::class, PolicyHistory::class, ConfigPurge::class, ResolutionRecorder::class,
                PolicyWriter::class, EvidenceLedger::class, LegalHoldRegistry::class, OperationRunner::class,
                ConnectionStatusReporter::class, EvidenceArtefactRecorder::class, JobCommands::class, IdempotencyStore::class, IdempotencyKeyPurgeJob::class,
            ),
        ),
        LEDGER_TABLE({ it.withPropertyValues("service-runtime.modules.evidence.ledger-table=evidence_ledger") }, setOf(EvidenceLedger::class)),
        LEGAL_HOLD_TABLE(
            { it.withPropertyValues("service-runtime.modules.evidence.legal-hold-table=legal_hold") },
            setOf(LegalHoldRegistry::class),
        ),
        BUCKET({ it.withPropertyValues("service-runtime.modules.evidence.bucket=evidence") }, setOf(ObjectStore::class)),
    }

    @Test
    fun `every L1 module present with the service's inputs, every port with a platform-libs implementation is a bean`(
        @TempDir mountRoot: Path,
    ) {
        Files.writeString(mountRoot.resolve("ap-token"), "s3cr3t")
        base.withServiceInputs(mountRoot).run { context ->
            context.startupFailure shouldBe null
            // tenancy-kernel
            context.getBean(TenantContext::class.java).shouldBeInstanceOf<TenantContextImpl>()
            val tx = context.getBean(TransactionRunner::class.java).shouldBeInstanceOf<TransactionRunnerImpl>()
            context.getBean(CurrentConnection::class.java) shouldBeSameInstanceAs tx
            // the base LicenceCheck is tenancy-svc's; the composition root wraps it (INV-Q-3732)
            context.getBean(LicenceCheck::class.java).shouldBeInstanceOf<NonBlockingLicenceCheck>()
            // outbox-and-queue, with its EventBinding the one selected by configuration
            context.getBean(TenantScope::class.java)
            context.getBean(OutboxWriter::class.java).shouldBeInstanceOf<JdbcOutboxWriter>()
            context.getBean(EventBinding::class.java).shouldBeInstanceOf<PostgresEventBinding>()
            listOf(
                OutboxRelay::class, EventLogReader::class, LaneRateLimiter::class, WorkQueue::class, DeadLetterTriage::class,
                RetentionPurge::class, EventLogPurge::class, StalenessMonitor::class, InboxConsumer::class, ReplayDelivery::class,
            ).forEach { context.getBean(it.java) }
            // audit-log: the outbox writer in a service with an outbox
            context.getBean(AuditWriter::class.java).shouldBeInstanceOf<OutboxAuditWriter>()
            context.getBean(AuditedOperations::class.java)
            context.getBean(OverrideService::class.java)
            // config-resolver
            listOf(PolicyReader::class, PolicyHistory::class, ConfigPurge::class, ResolutionRecorder::class, PolicyWriter::class)
                .forEach { context.getBean(it.java) }
            // evidence-store, with the ObjectStore binding selected by configuration
            context.getBean(EvidenceLedger::class.java)
            context.getBean(LegalHoldRegistry::class.java)
            context.getBean(ObjectStore::class.java).shouldBeInstanceOf<S3ObjectStore>()
            // adapter-runtime: its SecretStore reads the bound MountedFileSecretStore
            context.getBean(SecretStore::class.java).shouldBeInstanceOf<MountedFileSecretStore>()
            context.getBean(AdapterSecretStore::class.java).read(AdapterSecretRef("ap-token")).reveal() shouldBe "s3cr3t"
            context.getBean(SecretStore::class.java).read(BoundSecretRef("ap-token")).reveal() shouldBe "s3cr3t"
            context.getBean(AdapterDeadLetterSink::class.java).shouldBeInstanceOf<InMemoryDeadLetterSink>()
            // INV-Q-3730: adapter-runtime's version-keyed reporter is a local bean, not bridged; the only
            // ConnectionStatusChanged publisher is service-runtime's
            context.getBean(AdapterConnectionStatusReporter::class.java).shouldBeInstanceOf<InMemoryConnectionStatusReporter>()
            context.getBean(ConnectionStatusReporter::class.java).shouldBeInstanceOf<OutboxConnectionStatusReporter>()
            // crypto and rulepack-kernel
            val cms = context.getBean(DetachedSigner::class.java).shouldBeInstanceOf<BouncyCastleCms>()
            context.getBean(DetachedSignatureVerifier::class.java) shouldBeSameInstanceAs cms
            listOf(PackVerifier::class, PackContentValidator::class, PackRegistry::class).forEach { context.getBean(it.java) }
            // service-runtime's own ports over them
            context.getBean(OperationRunner::class.java)
            context.getBean(EvidenceArtefactRecorder::class.java)
            context.getBean(JobCommands::class.java).shouldBeInstanceOf<JobRuntime>()
            context.getBean(IdempotencyStore::class.java)
            context.getBean(IdempotencyKeyPurgeJob::class.java).definition.name shouldBe "idempotency-key-purge"
            context.getBean(JobRuntime::class.java).registered().map { it.name } shouldBe listOf("idempotency-key-purge")
            context.getBeansOfType(ServletRegistrationBean::class.java).size shouldBe 1
        }
    }

    @Test
    fun `the API-15 key retention is per installation, 24 hours by default, and a shorter one is refused at start-up`(
        @TempDir mountRoot: Path,
    ) {
        base.withServiceInputs(mountRoot).run { context ->
            context.getBean(IdempotencyStore::class.java).retention shouldBe Duration.ofHours(24)
        }
        base.withServiceInputs(mountRoot).withPropertyValues("service-runtime.idempotency.retention=P3D").run { context ->
            context.getBean(IdempotencyStore::class.java).retention shouldBe Duration.ofDays(3)
        }
        base.withServiceInputs(mountRoot).withPropertyValues("service-runtime.idempotency.retention=PT23H").run { context ->
            val failure = context.startupFailure.shouldNotBeNull()
            generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("at least 24 hours") == true } shouldBe true
        }
    }

    @Test
    fun `a service overrides any port with its own bean`(
        @TempDir mountRoot: Path,
    ) {
        val own = InMemoryPackRegistry()
        base.withServiceInputs(mountRoot).withBean(PackRegistry::class.java, { own }).run { context ->
            context.getBean(PackRegistry::class.java) shouldBeSameInstanceAs own
        }
    }

    @Test
    fun `without the service-only inputs, only the implementations that need none are created`() {
        base.run { context ->
            context.startupFailure shouldBe null
            context.getBean(TenantContext::class.java)
            context.getBean(PackRegistry::class.java)
            context.getBean(DetachedSigner::class.java)
            context.getBean(AdapterConnectionStatusReporter::class.java)
            context.getBean(AdapterSecretStore::class.java)
            listOf(
                TransactionRunner::class, OutboxWriter::class, EventBinding::class, AuditWriter::class, PolicyReader::class,
                EvidenceLedger::class, ObjectStore::class, ConnectionStatusReporter::class, JobCommands::class,
                LicenceCheck::class, IdempotencyStore::class,
            ).forEach { context.getBeansOfType(it.java).size shouldBe 0 }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @EnumSource(ServiceInput::class)
    fun `withholding one service-only input leaves out exactly the ports that need it`(
        input: ServiceInput,
        @TempDir mountRoot: Path,
    ) {
        base.withServiceInputs(mountRoot, without = input).run { context ->
            context.startupFailure shouldBe null
            SERVICE_INPUT_PORTS.forEach { port ->
                withClue("${port.simpleName} without $input") {
                    context.getBeansOfType(port.java).isEmpty() shouldBe (port in input.dependents)
                }
            }
        }
    }

    @Test
    fun `every port that needs a service-only input is present when all are declared`(
        @TempDir mountRoot: Path,
    ) {
        base.withServiceInputs(mountRoot).run { context ->
            context.startupFailure shouldBe null
            SERVICE_INPUT_PORTS.forEach { port -> withClue(port.simpleName) { context.getBeansOfType(port.java).size shouldBe 1 } }
        }
    }

    @Test
    fun `the event log runs on the eventLogDataSource, or on the application DataSource when it is absent`(
        @TempDir mountRoot: Path,
    ) {
        val eventLog = mockk<DataSource>(relaxed = true)
        val declared = base.withServiceInputs(mountRoot, without = ServiceInput.EVENT_LOG_DATA_SOURCE)
        declared.withBean(ServiceDataSources.EVENT_LOG, DataSource::class.java, { eventLog }).run { context ->
            context.startupFailure shouldBe null
            context.getBean(EventLogReader::class.java).shouldBeInstanceOf<PostgresEventLogReader>().backlog("gst.canonical.canonical-version-created.v1", 0)
            verify { eventLog.connection }
            verify(exactly = 0) { context.getBean("dataSource", DataSource::class.java).connection }
        }
        base.withServiceInputs(mountRoot, without = ServiceInput.EVENT_LOG_DATA_SOURCE).run { context ->
            context.startupFailure shouldBe null
            context.getBean(EventLogReader::class.java).shouldBeInstanceOf<PostgresEventLogReader>().backlog("gst.canonical.canonical-version-created.v1", 0)
            verify { context.getBean("dataSource", DataSource::class.java).connection }
        }
    }

    @Test
    fun `a service without an outbox gets the local audit writer`() {
        base
            .withBean("dataSource", DataSource::class.java, { mockk<DataSource>(relaxed = true) })
            .withPropertyValues("gst.service=control-plane-svc", "gst.instance-id=cp-0", "service-runtime.modules.schema=control_plane")
            .run { context ->
                context.getBeansOfType(OutboxWriter::class.java).size shouldBe 0
                context.getBean(AuditWriter::class.java).shouldBeInstanceOf<LocalAuditWriter>()
            }
    }

    @Test
    fun `service-runtime alone starts with no other L1 module's ports`(
        @TempDir mountRoot: Path,
    ) {
        base
            .withServiceInputs(mountRoot)
            .withClassLoader(FilteredClassLoader(java.util.function.Predicate<String> { otherL1Modules(it) }))
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(SecretStore::class.java)
                listOf(
                    TenantContext::class, TransactionRunner::class, TenantScope::class, OutboxWriter::class, EventBinding::class,
                    AuditWriter::class, PolicyReader::class, EvidenceLedger::class, ObjectStore::class, AdapterSecretStore::class,
                    AdapterConnectionStatusReporter::class, DetachedSigner::class, PackRegistry::class, OperationRunner::class,
                    ConnectionStatusReporter::class, JobCommands::class, IdempotencyStore::class,
                ).forEach { context.getBeansOfType(it.java).size shouldBe 0 }
                // the service's own base LicenceCheck stays as it is: no tenancy-kernel impl half to wrap it
                context.getBean(LicenceCheck::class.java).shouldBeInstanceOf<LicenceCheck>()
                (context.getBean(LicenceCheck::class.java) is NonBlockingLicenceCheck) shouldBe false
            }
    }

    private companion object {
        val INBOX: Set<KClass<*>> = setOf(InboxConsumer::class, ReplayDelivery::class)

        /** Every port whose presence depends on a service-only input, and the ports that depend on none. */
        val SERVICE_INPUT_PORTS: Set<KClass<*>> by lazy {
            ServiceInput.entries.flatMap { it.dependents }.toSet() + setOf(TransactionRunner::class, SecretStore::class, PackRegistry::class)
        }

        fun dataSource(name: String): (ApplicationContextRunner) -> ApplicationContextRunner = { it.withBean(name, DataSource::class.java, { mockk<DataSource>(relaxed = true) }) }

        /** Modules service-runtime does not depend on at all. */
        val absentModules = listOf("evidencestore", "adapterruntime", "rulepackkernel", "crypto").map { "com.dynokore.platform.$it." }

        /** The impl halves of the modules whose api half service-runtime does depend on. */
        val implClasses: Set<String> =
            listOf(TransactionRunnerImpl::class.java, JdbcOutboxWriter::class.java, OutboxAuditWriter::class.java, com.dynokore.platform.configresolver.JdbcPolicyReader::class.java)
                .flatMap { type -> classNames(Path.of(type.protectionDomain.codeSource.location.toURI())) }
                .toSet()

        /** The classes in [location], a jar or a class directory. */
        fun classNames(location: Path): List<String> {
            val entries =
                if (Files.isDirectory(location)) {
                    Files.walk(location).use { paths -> paths.map { location.relativize(it).toString().replace('\\', '/') }.toList() }
                } else {
                    JarFile(location.toFile()).use { jar -> jar.entries().toList().map { it.name } }
                }
            return entries.filter { it.endsWith(".class") }.map { it.removeSuffix(".class").replace('/', '.') }
        }

        fun otherL1Modules(name: String): Boolean = absentModules.any { name.startsWith(it) } || name in implClasses
    }
}
