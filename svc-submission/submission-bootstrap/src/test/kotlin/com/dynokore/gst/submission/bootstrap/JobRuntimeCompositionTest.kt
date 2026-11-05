package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.SubmissionDb
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.application.queue.WaitingSweeper
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.jobs.FireResult
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchema
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.objenesis.ObjenesisStd
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.config.CronTask
import org.springframework.scheduling.support.CronExpression
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/** TPL-18 (P109, INV-Q-15328 A): the job runtime of service-runtime is built by the service's own composition. */
class JobRuntimeCompositionTest {
    private val actor = "svc:submission-svc"
    private val migration = "V202611031000__submission_job_tables.sql"

    private fun run(
        dir: Path,
        block: (ApplicationContext) -> Unit,
    ) {
        SubmissionContext.online(dir).run { context ->
            context.startupFailure shouldBe null
            block(context)
        }
    }

    private fun <T> ApplicationContext.asTenant(
        tenant: UUID,
        block: () -> T,
    ): T =
        getBean(TenantContext::class.java).bind(TenantId(tenant), actor) {
            getBean(TransactionRunner::class.java).inTransaction(block)
        }

    private fun ApplicationContext.seed(
        tenant: UUID,
        doc: String,
        version: Int,
        state: String,
    ) = asTenant(tenant) {
        getBean(DSLContext::class.java)
            .insertInto(Submission.SUBMISSION_)
            .set(Submission.SUBMISSION_.TENANT_ID, tenant)
            .set(Submission.SUBMISSION_.DOC_UUID, doc)
            .set(Submission.SUBMISSION_.VERSION, version)
            .set(Submission.SUBMISSION_.STATE, state)
            .set(Submission.SUBMISSION_.ENTITY_REF, "E-001")
            .execute()
    }

    private fun ApplicationContext.states(
        tenant: UUID,
        doc: String,
    ): Map<Int, String> =
        asTenant(tenant) {
            getBean(DSLContext::class.java)
                .select(Submission.SUBMISSION_.VERSION, Submission.SUBMISSION_.STATE)
                .from(Submission.SUBMISSION_)
                .where(Submission.SUBMISSION_.DOC_UUID.eq(doc))
                .fetch()
                .associate { checkNotNull(it.value1()) to checkNotNull(it.value2()) }
        }

    /** The raw payloads of the `work_queue` items of the Doc UUID. */
    private fun ApplicationContext.payloads(
        tenant: UUID,
        doc: String,
    ): List<String> =
        asTenant(tenant) {
            getBean(DSLContext::class.java)
                .select(WorkQueue.WORK_QUEUE.PAYLOAD)
                .from(WorkQueue.WORK_QUEUE)
                .where(WorkQueue.WORK_QUEUE.PARTITION_KEY.eq(doc))
                .fetch()
                .map { it.value1().toString() }
        }

    @Test
    fun `INV-AC-15339 firing waiting-sweep queues the waiting version once and a second firing adds nothing`(
        @TempDir dir: Path,
    ) = run(dir) { context ->
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID().toString()
        context.seed(tenant, doc, 1, "ACKNOWLEDGED")
        context.seed(tenant, doc, 2, "READY")
        context.payloads(tenant, doc) shouldBe emptyList()
        val runtime = context.getBean(JobRuntime::class.java)
        val first = Instant.parse("2026-11-03T10:00:00Z")

        runtime.fire("waiting-sweep", first) shouldBe mapOf(JobTarget.Whole to FireResult.RAN)

        context.states(tenant, doc) shouldBe mapOf(1 to "ACKNOWLEDGED", 2 to "QUEUED")
        val items = context.payloads(tenant, doc)
        items.size shouldBe 1
        Regex("\"version\"\\s*:\\s*2\\b").containsMatchIn(items.single()) shouldBe true
        // the lock of the first run is held for lockAtLeastFor (30 s, real clock): wait it out so the body runs again
        Thread.sleep(LOCK_LEASE_WAIT.toMillis())
        runtime.fire("waiting-sweep", first.plusSeconds(60)) shouldBe mapOf(JobTarget.Whole to FireResult.RAN)
        context.states(tenant, doc) shouldBe mapOf(1 to "ACKNOWLEDGED", 2 to "QUEUED")
        context.payloads(tenant, doc) shouldBe items
    }

    @Test
    fun `INV-AC-15339 the runtime holds exactly the waiting-sweep definition with a 60 second cron`(
        @TempDir dir: Path,
    ) = run(dir) { context ->
        context.getBeansOfType(JobRuntime::class.java).size shouldBe 1
        val definitions = context.getBean(JobRuntime::class.java).registered()
        definitions.map { it.name }.toSet() shouldBe setOf("compliance-evaluation", "waiting-sweep")
        definitions.size shouldBe 2
        val definition = definitions.single { it.name == "waiting-sweep" }
        definition.ownerService shouldBe "submission-svc"
        definition.entityScope shouldBe JobEntityScope.PLATFORM
        definition.schedule.cron shouldBe "0 * * * * *"
        definition.schedule.zone shouldBe ZoneOffset.UTC
        val cron = CronExpression.parse(definition.schedule.cron)
        val from = ZonedDateTime.parse("2026-11-03T10:00:30Z")
        val one = checkNotNull(cron.next(from))
        val two = checkNotNull(cron.next(one))
        Duration.between(one, two) shouldBe WaitingSweeper.INTERVAL
        Duration.between(one, two).seconds shouldBe 60
    }

    private fun ApplicationContext.cronTasks(): List<CronTask> =
        getBean(ScheduledAnnotationBeanPostProcessor::class.java)
            .scheduledTasks
            .map { it.task }
            .filterIsInstance<CronTask>()

    @Test
    fun `the shared test context holds no jobScheduling bean and schedules no task`(
        @TempDir dir: Path,
    ) = run(dir) { context ->
        context.containsBean("jobScheduling") shouldBe false
        context.getBean(ScheduledAnnotationBeanPostProcessor::class.java).scheduledTasks.isEmpty() shouldBe true
    }

    @Test
    fun `the production setting schedules one cron task of the waiting-sweep cron`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.online(dir).withPropertyValues("inv.submission.job-scheduling.enabled=true").run { context ->
            context.startupFailure shouldBe null
            context.containsBean("jobScheduling") shouldBe true
            context.cronTasks().map { it.expression }.toSet() shouldBe
                setOf(
                    JobRuntimeConfiguration.WAITING_SWEEP.schedule.cron,
                    context.environment.getProperty("inv.compliance-evaluation.cron"),
                )
            context.cronTasks().size shouldBe 2
        }
    }

    @Test
    fun `the scheduling bean is present when the switch is missing`() {
        val sweeper = ObjenesisStd().newInstance(WaitingSweeper::class.java)
        ApplicationContextRunner()
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withBean(WaitingSweeper::class.java, { sweeper })
            .withUserConfiguration(JobRuntimeConfiguration::class.java)
            .run { context ->
                context.startupFailure shouldBe null
                context.containsBean("jobScheduling") shouldBe true
            }
    }

    @Test
    fun `application yaml switches job scheduling on`() {
        val yaml = YamlPropertiesFactoryBean()
        yaml.setResources(ClassPathResource("application.yaml"))
        yaml.getObject()?.getProperty("inv.submission.job-scheduling.enabled") shouldBe "true"
    }

    @Test
    fun `the authorizer permits no actor to command a job`(
        @TempDir dir: Path,
    ) = run(dir) { context ->
        val authorizer = context.getBean(JobCommandAuthorizer::class.java)
        listOf("release.manager", "platform.engineer", "svc:submission-svc", "").forEach { who ->
            JobCommand.entries.forEach { command ->
                authorizer.isPlatformOperator(who, command) shouldBe false
                authorizer.mayCommandEntity(who, TenantId(UUID.randomUUID()), EntityRef.PLATFORM, command) shouldBe
                    false
                authorizer.mayCommandEntity(who, TenantId.PLATFORM, EntityRef.PLATFORM, command) shouldBe false
            }
        }
    }

    @Test
    fun `the job tables migration is the DDL service-runtime generates for the submission schema`() {
        val text =
            checkNotNull(javaClass.getResource("/db/migration/$migration")) { "no migration $migration" }.readText()
        val statements =
            text
                .lines()
                .filterNot { it.startsWith("--") }
                .joinToString("\n")
                .trim()
        statements shouldBe JobSchema.ddl(SubmissionDb.SCHEMA, SubmissionDb.RUNTIME, SubmissionDb.SYS).trim()
    }

    private companion object {
        val LOCK_LEASE_WAIT: Duration = Duration.ofSeconds(31)
    }
}
