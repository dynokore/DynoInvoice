package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqAppliedStore
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.JobRun.Companion.JOB_RUN
import com.dynokore.gst.evidence.application.chain.AppliedRecord
import com.dynokore.gst.evidence.application.integrity.ChainVerificationJob
import com.dynokore.gst.evidence.domain.chain.ChainKey
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceType
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.serviceruntime.jobs.DeadlineSource
import com.dynokore.platform.serviceruntime.jobs.FixedDeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.support.GenericApplicationContext
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.config.CronTask
import org.springframework.scheduling.config.FixedRateTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.scheduling.support.SimpleTriggerContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** TPL-18 (P109, INV-Q-12992 A): the job runtime of service-runtime is built by the service's own composition. */
class JobRuntimeCompositionTest {
    private val actors = listOf("release.manager", "platform.engineer", "svc:evidence-svc", "")

    @Test
    fun `the context holds one JobRuntime with exactly the verification and archive-volume jobs registered`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(JobRuntime::class.java).size shouldBe 1
            context
                .getBean(JobRuntime::class.java)
                .registered()
                .map { it.name }
                .sorted() shouldBe
                listOf("archive-volume-measurement", "evidence-chain-verification")
        }
    }

    @Test
    fun `no actor is permitted to command a job`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.offline(dir).run { context ->
            val authorizer = context.getBean(JobCommandAuthorizer::class.java)
            JobCommand.entries.forEach { command ->
                actors.forEach { actor ->
                    authorizer.isPlatformOperator(actor, command) shouldBe false
                    authorizer.mayCommandEntity(actor, TenantId.PLATFORM, EventEntityRef.PLATFORM, command) shouldBe
                        false
                }
            }
        }
    }

    @Test
    fun `the deadline source is the fixed one and serves no due date`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.offline(dir).run { context ->
            val deadlines = context.getBean(DeadlineSource::class.java)
            deadlines.shouldBeInstanceOf<FixedDeadlineSource>()
            deadlines.nearestDueDate(ChainVerificationJob.DEFINITION, JobTarget.Whole).shouldBeNull()
        }
    }

    @Test
    fun `the event catalogue holds the platform events the job runtime writes`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.offline(dir).run { context ->
            val catalogue = context.getBean(EventCatalogue::class.java)
            catalogue.find(PlatformEventCatalogue.JOB_RUN_RECORDED.eventType).shouldNotBeNull()
            catalogue.find(PlatformEventCatalogue.JOB_REGISTERED.eventType).shouldNotBeNull()
            catalogue.find(PlatformEventCatalogue.AUDIT_ENTRY_RECORDED.eventType).shouldNotBeNull()
            catalogue.find("com.dynokore.gst.evidence.EvidenceIntegrityFailed.v1").shouldNotBeNull()
            catalogue.find("com.dynokore.gst.evidence.NotCatalogued.v1").shouldBeNull()
        }
    }

    @Test
    fun `the platform part of the catalogue is the platform events with a topic and no replay event`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.offline(dir).run { context ->
            val catalogue = context.getBean(EventCatalogue::class.java)
            val expected =
                PlatformEventCatalogue.ALL
                    .filter { it.topic != null }
                    .map { it.eventType }
                    .toSet()
            expected.filter { catalogue.find(it) != null }.toSet() shouldBe expected
            catalogue.find("com.dynokore.gst.platform.ReplayRequested.v1").shouldBeNull()
            catalogue.find("com.dynokore.gst.platform.ReplayCompleted.v1").shouldBeNull()
            catalogue.find("com.dynokore.gst.platform.JobRunRecorded.v2").shouldBeNull()
        }
    }

    @Test
    fun `probe the context does not start without the catalogue bean`(
        @TempDir dir: Path,
    ) {
        EvidenceContext
            .offline(dir)
            .withInitializer { context ->
                (context as GenericApplicationContext).addBeanFactoryPostProcessor {
                    (it as BeanDefinitionRegistry).removeBeanDefinition("eventCatalogue")
                }
            }.run { context -> (context.startupFailure != null) shouldBe true }
    }

    @Test
    fun `probe a second JobCommandAuthorizer bean is refused`(
        @TempDir dir: Path,
    ) {
        EvidenceContext
            .offline(dir)
            .withBean(
                "permissiveAuthorizer",
                JobCommandAuthorizer::class.java,
                {
                    object : JobCommandAuthorizer {
                        override fun mayCommandEntity(
                            actor: String,
                            tenant: TenantId,
                            entity: EventEntityRef,
                            command: JobCommand,
                        ) = true

                        override fun isPlatformOperator(
                            actor: String,
                            command: JobCommand,
                        ) = true
                    }
                },
            ).run { context -> (context.startupFailure != null) shouldBe true }
    }

    @Test
    fun `one cron task of the registered definition is scheduled and scheduling is enabled once`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.offline(dir).run { context ->
            val tasks = context.getBean(ScheduledAnnotationBeanPostProcessor::class.java).scheduledTasks
            tasks.size shouldBe 2
            tasks.none { it.task is FixedRateTask } shouldBe true
            val cron =
                tasks
                    .map { it.task.shouldBeInstanceOf<CronTask>() }
                    .single { it.expression == "0 0 1 * * *" }
            val registered =
                context.getBean(JobRuntime::class.java).registered().single { it.name == "evidence-chain-verification" }
            registered.schedule.cron shouldBe "0 0 1 * * *"
            registered.schedule.zone shouldBe ZoneOffset.UTC
            cron.expression shouldBe registered.schedule.cron
            context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java).size shouldBe 1
            val next = cron.trigger.nextExecution(contextAt(Instant.parse("2026-01-01T00:59:59Z")))
            next shouldBe Instant.parse("2026-01-01T01:00:00Z")
        }
    }

    @Test
    fun `a context without a JobRuntime registers no task`() {
        val registrar = ScheduledTaskRegistrar()
        JobRuntimeConfiguration()
            .jobScheduling(StaticListableBeanFactory().getBeanProvider(JobRuntime::class.java), Clock.systemUTC())
            .configureTasks(registrar)
        registrar.cronTaskList shouldBe emptyList()
    }

    @Test
    fun `running the task at 01 00 UTC makes one run row per listed pair`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.online(dir).run { context ->
            val scope = context.getBean(TenantScope::class.java)
            val runner = context.getBean(TransactionRunner::class.java)
            val dsl = context.getBean(DSLContext::class.java)
            val runtime = context.getBean(JobRuntime::class.java)
            val pairs = listOf(TenantId(UUID.randomUUID()) to "ENT-AAA111", TenantId(UUID.randomUUID()) to "ENT-BBB222")
            pairs.forEach { (tenant, entity) -> seed(scope, runner, dsl, tenant, entity) }

            fun runRows(tenant: TenantId) =
                scope.bind(tenant, "svc:evidence-svc") {
                    runner.inTransaction {
                        dsl
                            .select(JOB_RUN.ENTITY_REF)
                            .from(JOB_RUN)
                            .where(JOB_RUN.JOB.eq(ChainVerificationJob.NAME))
                            .fetch(JOB_RUN.ENTITY_REF)
                    }
                }

            val registrar = ScheduledTaskRegistrar()
            val utc = Instant.parse("2026-11-01T01:00:00Z")
            JobRuntimeConfiguration()
                .jobScheduling(providerOf(runtime), Clock.fixed(utc, ZoneOffset.UTC))
                .configureTasks(registrar)
            registrar.cronTaskList
                .single { it.expression == "0 0 1 * * *" }
                .runnable
                .run()
            pairs.forEach { (tenant, entity) -> runRows(tenant) shouldBe listOf(entity) }
        }
    }

    private fun contextAt(instant: Instant) =
        SimpleTriggerContext(Clock.fixed(instant, ZoneOffset.UTC)).also { it.update(instant, instant, instant) }

    private fun providerOf(runtime: JobRuntime) =
        StaticListableBeanFactory(mapOf("runtime" to runtime)).getBeanProvider(JobRuntime::class.java)

    /** One applied chain record puts the (tenant, entity) pair into `verification_entities`. */
    private fun seed(
        scope: TenantScope,
        runner: TransactionRunner,
        dsl: DSLContext,
        tenant: TenantId,
        entity: String,
    ) = scope.bind(tenant, "svc:evidence-svc") {
        runner.inTransaction {
            val ledger = JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, RecordKind.EVIDENCE_PAYLOAD.table)
            val record =
                ledger.append(
                    tenant,
                    EntityRef(entity),
                    PeriodRef("2026-Q1"),
                    EvidenceType.PAYLOAD,
                    "{}".toByteArray(),
                )
            JooqAppliedStore(dsl).insert(
                tenant,
                AppliedRecord(
                    "ev-$entity",
                    RecordKind.EVIDENCE_PAYLOAD,
                    ChainKey(entity, "2026-Q1"),
                    record.sequence,
                    Instant.now(),
                ),
            )
        }
    }
}
