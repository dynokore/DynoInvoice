package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.gapreport.SweepStaleRecords
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.objenesis.ObjenesisStd
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.config.CronTask
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** TPL-18 (P109, INV-Q-18028 A): the job runtime of service-runtime is built by the service's own composition. */
class JobRuntimeCompositionTest {
    private fun cronTasks(context: org.springframework.context.ApplicationContext): List<CronTask> =
        context
            .getBean(ScheduledAnnotationBeanPostProcessor::class.java)
            .scheduledTasks
            .map { it.task }
            .filterIsInstance<CronTask>()

    @Test
    fun `INV-AC-9601 the runtime holds exactly the two job definitions with the configured crons`() {
        BoundaryFixture.runner().run { context ->
            assertThat(context).hasNotFailed()
            val definitions = context.getBean(JobRuntime::class.java).registered()
            assertThat(definitions.map { it.name }.sorted())
                .isEqualTo(listOf("credential-expiry-check", "long-command-sweep"))
            val definition = definitions.single { it.name == "long-command-sweep" }
            assertThat(definition.ownerService).isEqualTo("masterdata-svc")
            assertThat(definition.entityScope).isEqualTo(JobEntityScope.PLATFORM)
            assertThat(definition.schedule.zone).isEqualTo(ZoneOffset.UTC)
            assertThat(definition.schedule.cron)
                .isEqualTo(context.environment.getProperty("masterdata.long-command.sweep-cron"))
            val expiry = definitions.single { it.name == "credential-expiry-check" }
            assertThat(expiry.ownerService).isEqualTo("masterdata-svc")
            assertThat(expiry.entityScope).isEqualTo(JobEntityScope.PLATFORM)
            assertThat(expiry.schedule.cron)
                .isEqualTo(context.environment.getProperty("masterdata.connections.expiry-check-cron"))
        }
        BoundaryFixture
            .runner()
            .withPropertyValues("masterdata.long-command.sweep-cron=0 0 3 * * *")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(
                    context
                        .getBean(JobRuntime::class.java)
                        .registered()
                        .single { it.name == "long-command-sweep" }
                        .schedule.cron,
                ).isEqualTo("0 0 3 * * *")
            }
    }

    @Test
    fun `INV-AC-9601 a start without the sweep cron key or with a blank value fails`() {
        BoundaryFixture
            .runner()
            .withPropertyValues("masterdata.long-command.sweep-cron=\${masterdata.absent.value}")
            .run { context -> assertThat(context).hasFailed() }
        BoundaryFixture
            .runner()
            .withPropertyValues("masterdata.long-command.sweep-cron=   ")
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `INV-AC-9601 with the switch false no cron task is registered and with it true or missing exactly one`() {
        BoundaryFixture
            .runner()
            .withPropertyValues("inv.masterdata.job-scheduling.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.containsBean("jobScheduling")).isFalse()
                assertThat(cronTasks(context)).isEmpty()
            }
        BoundaryFixture
            .runner()
            .withPropertyValues("inv.masterdata.job-scheduling.enabled=true")
            .run { context ->
                assertThat(cronTasks(context).map { it.expression })
                    .containsExactlyInAnyOrder(
                        context.environment.getProperty("masterdata.long-command.sweep-cron"),
                        context.environment.getProperty("masterdata.connections.expiry-check-cron"),
                    )
            }
    }

    @Test
    fun `INV-AC-9601 the scheduling bean is present when the switch is missing even if a look-alike key is false`() {
        val sweep = ObjenesisStd().newInstance(SweepStaleRecords::class.java)
        ApplicationContextRunner()
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withBean(SweepStaleRecords::class.java, { sweep })
            .withBean(
                CheckCredentialExpiryForTenants::class.java,
                { ObjenesisStd().newInstance(CheckCredentialExpiryForTenants::class.java) },
            ).withPropertyValues(
                "masterdata.long-command.sweep-cron=0 */5 * * * *",
                "masterdata.connections.expiry-check-cron=0 0 * * * *",
                "inv.submission.job-scheduling.enabled=false",
            ).withUserConfiguration(JobRuntimeConfiguration::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.containsBean("jobScheduling")).isTrue()
            }
    }

    @Test
    fun `INV-AC-9601 every job command for every actor is refused by the authorizer`() {
        BoundaryFixture.runner().run { context ->
            val authorizer = context.getBean(JobCommandAuthorizer::class.java)
            listOf("release.manager", "platform.engineer", "svc:masterdata-svc", "").forEach { who ->
                JobCommand.entries.forEach { command ->
                    assertThat(authorizer.isPlatformOperator(who, command)).isFalse()
                    assertThat(
                        authorizer.mayCommandEntity(who, TenantId(UUID.randomUUID()), EntityRef.PLATFORM, command),
                    ).isFalse()
                    assertThat(
                        authorizer.mayCommandEntity(who, TenantId.PLATFORM, EntityRef.PLATFORM, command),
                    ).isFalse()
                }
            }
        }
    }
}
