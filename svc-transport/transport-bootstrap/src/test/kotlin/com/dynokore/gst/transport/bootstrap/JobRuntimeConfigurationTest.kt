package com.dynokore.gst.transport.bootstrap

import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.OutboxAuditWriter
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.composition.OperatorSessionAuditStager
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** The two ports service-runtime leaves to the service, and the `JobRuntime` they let it build (INV-Q-9684 A). */
class JobRuntimeConfigurationTest {
    private val actors = listOf("integration.engineer", "platform.engineer", "")

    @Test
    fun `the context starts with exactly one JobRuntime and the outbox audit writer`(
        @TempDir dir: Path,
    ) {
        TransportContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeansOfType(JobRuntime::class.java).size shouldBe 1
            // service-runtime decorates every AuditWriter bean (INV-Q-4521): the bean is the decorator, its delegate the writer
            context
                .getBean(AuditWriter::class.java)
                .shouldBeInstanceOf<OperatorSessionAuditStager>()
                .delegate
                .shouldBeInstanceOf<OutboxAuditWriter>()
        }
    }

    @Test
    fun `nobody may command a job - both methods are false for every command and actor`(
        @TempDir dir: Path,
    ) {
        TransportContext.offline(dir).run { context ->
            permissions(context.getBean(JobCommandAuthorizer::class.java)) shouldBe emptyList()
        }
    }

    @Test
    fun `probe - an authorizer with isPlatformOperator true for RUN_NOW is caught`() {
        permissions(
            Mutated(isOperator = { _, command -> command == JobCommand.RUN_NOW }),
        ).isNotEmpty() shouldBe true
    }

    @Test
    fun `probe - an authorizer with mayCommandEntity true for platform engineer is caught`() {
        permissions(
            Mutated(mayEntity = { actor -> actor == "platform.engineer" }),
        ).isNotEmpty() shouldBe true
    }

    /** Every (method, command, actor) the authorizer permits; empty when it denies all. */
    private fun permissions(authorizer: JobCommandAuthorizer): List<String> =
        JobCommand.entries.flatMap { command ->
            actors.flatMap { actor ->
                listOfNotNull(
                    "mayCommandEntity $command '$actor'".takeIf {
                        authorizer.mayCommandEntity(actor, TenantId(UUID.randomUUID()), EntityRef("E-001"), command)
                    },
                    "isPlatformOperator $command '$actor'".takeIf { authorizer.isPlatformOperator(actor, command) },
                )
            }
        }

    private class Mutated(
        private val mayEntity: (String) -> Boolean = { false },
        private val isOperator: (String, JobCommand) -> Boolean = { _, _ -> false },
    ) : JobCommandAuthorizer {
        override fun mayCommandEntity(
            actor: String,
            tenant: TenantId,
            entity: EntityRef,
            command: JobCommand,
        ): Boolean = mayEntity(actor)

        override fun isPlatformOperator(
            actor: String,
            command: JobCommand,
        ): Boolean = isOperator(actor, command)
    }

    @Test
    fun `the deadline source is a FixedDeadlineSource that serves no due date`(
        @TempDir dir: Path,
    ) {
        TransportContext.offline(dir).run { context ->
            val source = context.getBean("deadlineSource")
            source.shouldBeInstanceOf<FixedDeadlineSource>()
            val runtime = context.getBean(JobRuntime::class.java)
            runtime.register(job()) {}
            source
                .nearestDueDate(
                    runtime.registered().single { it.name == "wiring-probe" },
                    JobTarget.Whole,
                ).shouldBeNull()
        }
    }

    @Test
    fun `probe - with the deadlineSource bean removed the JobRuntime bean goes missing`(
        @TempDir dir: Path,
    ) {
        TransportContext
            .offline(dir)
            .withInitializer { context ->
                context.addBeanFactoryPostProcessor(
                    BeanDefinitionRegistryPostProcessor { registry -> registry.removeBeanDefinition("deadlineSource") },
                )
            }.run { context ->
                // the start fails on the missing port; no JobRuntime is built
                context.startupFailure.shouldBeInstanceOf<NoSuchBeanDefinitionException>()
            }
    }

    private fun job() =
        JobDefinition(
            "wiring-probe",
            "transport-svc",
            JobEntityScope.PLATFORM,
            JobSchedule("0 0 * * * *"),
            JobCriticality.STANDARD,
            Duration.ofMinutes(5),
        )
}
