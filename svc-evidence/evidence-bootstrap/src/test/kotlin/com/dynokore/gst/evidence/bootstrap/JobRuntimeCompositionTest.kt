package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqAppliedStore
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
import org.springframework.context.support.GenericApplicationContext
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.config.CronTask
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/** TPL-18 (P109, INV-Q-12992 A): the job runtime of service-runtime is built by the service's own composition. */
class JobRuntimeCompositionTest {
    private val actors = listOf("release.manager", "platform.engineer", "svc:evidence-svc", "")

    @Test
    fun `the context holds one JobRuntime with exactly the verification job registered`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(JobRuntime::class.java).size shouldBe 1
            context.getBean(JobRuntime::class.java).registered().map { it.name } shouldBe
                listOf("evidence-chain-verification")
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
    fun `probe the context does not start without the catalogue bean`(
        @TempDir dir: Path,
    ) {
        EvidenceContext
            .offline(dir)
            .withInitializer { context ->
                (context as GenericApplicationContext).beanFactory.addBeanFactoryPostProcessor {
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
    fun `the scheduled task reads cron and zone from the registered definition and scheduling is enabled once`(
        @TempDir dir: Path,
    ) {
        EvidenceContext.offline(dir).run { context ->
            val tasks = context.getBean(ScheduledAnnotationBeanPostProcessor::class.java).scheduledTasks
            val cron = tasks.mapNotNull { it.task as? CronTask }.single()
            val registered = context.getBean(JobRuntime::class.java).registered().single()
            cron.expression shouldBe registered.schedule.cron
            cron.expression shouldBe "0 0 1 * * *"
            context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java).size shouldBe 1
        }
    }

    @Test
    fun `a firing at 01 00 UTC runs once per listed pair and a clock at 01 00 Singapore runs nothing`(
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
                            .fetch(
                                "select entity_ref from evidence.job_run where job = ?",
                                ChainVerificationJob.NAME,
                            ).map { it.get(0, String::class.java) }
                    }
                }

            val singapore = Instant.parse("2026-11-01T01:00:00+08:00")
            ChainVerificationSchedule(providerOf(runtime), Clock.fixed(singapore, ZoneOffset.UTC)).fire()
            pairs.forEach { (tenant, _) -> runRows(tenant) shouldBe emptyList() }

            val utc = Instant.parse("2026-11-01T01:00:00Z")
            ChainVerificationSchedule(providerOf(runtime), Clock.fixed(utc, ZoneOffset.UTC)).fire()
            pairs.forEach { (tenant, entity) -> runRows(tenant) shouldBe listOf(entity) }
        }
    }

    private fun providerOf(runtime: JobRuntime) =
        org.springframework.beans.factory.support
            .StaticListableBeanFactory(mapOf("runtime" to runtime))
            .getBeanProvider(JobRuntime::class.java)

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
