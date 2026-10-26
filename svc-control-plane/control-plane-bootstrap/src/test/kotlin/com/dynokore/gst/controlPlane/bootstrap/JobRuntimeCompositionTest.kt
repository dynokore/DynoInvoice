package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.serviceruntime.composition.OperatorSessionAuditWriter
import com.dynokore.platform.serviceruntime.jobs.DeadlineSource
import com.dynokore.platform.serviceruntime.jobs.FixedDeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/** TPL-18 (P109): the job runtime of service-runtime is built by the service's own composition, over no database. */
class JobRuntimeCompositionTest {
    private val actors = listOf("release.manager", "deployment.operator", "platform.engineer", "")

    @Test
    fun `the composition builds the one JobRuntime over the outbox audit writer`(
        @TempDir mount: Path,
    ) {
        controlPlaneRunner(mount).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(JobRuntime::class.java).size shouldBe 1
            context.getBean(OutboxWriter::class.java).shouldNotBeNull()
            // service-runtime wraps every AuditWriter bean (OperatorSessionAuditWriter): the wrapped writer is the outbox one
            val audit = context.getBean(AuditWriter::class.java)
            audit.shouldBeInstanceOf<OperatorSessionAuditWriter>()
            audit.delegate.shouldBeInstanceOf<OutboxAuditWriter>()
            context.getBeanNamesForType(AuditWriter::class.java).toList() shouldBe listOf("outboxAuditWriter")
        }
    }

    @Test
    fun `the event catalogue holds the platform events that have a topic and nothing else`(
        @TempDir mount: Path,
    ) {
        controlPlaneRunner(mount).run { context ->
            context.getBeansOfType(EventCatalogue::class.java).size shouldBe 1
            val catalogue = context.getBean(EventCatalogue::class.java)
            catalogue.entries().map { it.eventType to it.topic }.toSet() shouldBe
                PlatformEventCatalogue.ALL.mapNotNull { e -> e.topic?.let { e.eventType to it } }.toSet()
            catalogue.find(PlatformEventCatalogue.JOB_RUN_RECORDED.eventType).shouldNotBeNull()
            catalogue.find("com.dynokore.gst.controlplane.NotCatalogued.v1").shouldBeNull()
        }
    }

    @Test
    fun `no actor is permitted to command a job`(
        @TempDir mount: Path,
    ) {
        controlPlaneRunner(mount).run { context ->
            val authorizer = context.getBean(JobCommandAuthorizer::class.java)
            JobCommand.entries.forEach { command ->
                actors.forEach { actor ->
                    authorizer.isPlatformOperator(actor, command) shouldBe false
                    authorizer.mayCommandEntity(actor, TENANT, EntityRef.PLATFORM, command) shouldBe false
                }
            }
        }
    }

    @Test
    fun `the deadline source serves no due date`(
        @TempDir mount: Path,
    ) {
        controlPlaneRunner(mount).run { context ->
            val deadlines = context.getBean(DeadlineSource::class.java)
            deadlines.shouldBeInstanceOf<FixedDeadlineSource>()
            deadlines.nearestDueDate(JOB, JobTarget.Whole).shouldBeNull()
        }
    }

    private companion object {
        val TENANT = TenantId.PLATFORM
        val JOB =
            JobDefinition(
                name = "composition-probe",
                ownerService = "control-plane-svc",
                entityScope = JobEntityScope.PLATFORM,
                schedule = JobSchedule("0 0 * * * *"),
                criticality = JobCriticality.STANDARD,
                tolerance = Duration.ofMinutes(5),
            )
    }
}
