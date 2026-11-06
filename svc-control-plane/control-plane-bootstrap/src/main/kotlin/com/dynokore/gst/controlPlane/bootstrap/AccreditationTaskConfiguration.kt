package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.events.AccreditationAlertsOutbox
import com.dynokore.gst.controlPlane.adapters.http.TaskCursor
import com.dynokore.gst.controlPlane.adapters.persistence.AccreditationStepQueueJooq
import com.dynokore.gst.controlPlane.adapters.persistence.AccreditationTaskRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.ReleaseAccreditationLinksJooq
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationAlerts
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationRecords
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationStepPorts
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationStepQueue
import com.dynokore.gst.controlPlane.application.accreditation.ReleaseTarget
import com.dynokore.gst.controlPlane.application.accreditation.StepReporting
import com.dynokore.gst.controlPlane.application.accreditation.StepRetryPolicy
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationTaskQueries
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationTaskRepository
import com.dynokore.gst.controlPlane.application.accreditation.ConcludeAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.OpenAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.ReleaseAccreditationLinks
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/** The re-accreditation tasks and the alert of the release pipeline (INV-REQ-1365, INV-T-7323). */
@Configuration(proxyBeanMethods = false)
class AccreditationTaskConfiguration {
    @Bean
    fun accreditationTaskRepository(dsl: DSLContext): AccreditationTaskRepository = AccreditationTaskRepositoryJooq(dsl)

    @Bean
    fun releaseAccreditationLinks(dsl: DSLContext): ReleaseAccreditationLinks = ReleaseAccreditationLinksJooq(dsl)

    @Bean
    fun accreditationAlerts(outbox: OutboxWriter): AccreditationAlerts = AccreditationAlertsOutbox(outbox)

    @Bean
    fun accreditationStepPorts(
        tasks: AccreditationTaskRepository,
        links: ReleaseAccreditationLinks,
        alerts: AccreditationAlerts,
        records: AccreditationRecords,
        queue: AccreditationStepQueue,
    ) = AccreditationStepPorts(tasks, links, alerts, records, queue)

    /** The retry settings: every value is a property and a missing one fails the start-up (fail closed). */
    @Bean
    fun stepRetryPolicy(environment: Environment) =
        StepRetryPolicy(
            firstDelay = Duration.parse(required(environment, FIRST_DELAY)),
            firstBackoff = Duration.parse(required(environment, FIRST_BACKOFF)),
            backoffCap = Duration.parse(required(environment, BACKOFF_CAP)),
            maxAttempts = required(environment, MAX_ATTEMPTS).toInt(),
            batchSize = required(environment, BATCH_SIZE).toInt(),
        )

    @Bean
    fun accreditationStepQueue(
        dsl: DSLContext,
        policy: StepRetryPolicy,
    ): AccreditationStepQueue = AccreditationStepQueueJooq(dsl, policy)

    /** The accreditation step of the release pipeline: its own transaction; a failure is logged, never thrown. */
    @Bean
    fun openAccreditationTask(
        ports: AccreditationStepPorts,
        transactions: TransactionRunner,
        clock: Clock,
    ) = OpenAccreditationTask(ports, transactions, clock, LogReporting)

    /** The relay of the durable retry: the job `accreditation-step-retry` (INV-Q-17708 A). */
    @Bean
    fun accreditationStepRetryJob(
        runtime: JobRuntime,
        environment: Environment,
        step: OpenAccreditationTask,
        queue: AccreditationStepQueue,
        policy: StepRetryPolicy,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
        clock: Clock,
    ) = AccreditationStepRetryJob(
        runtime,
        required(environment, "gst.service"),
        required(environment, AccreditationStepRetryJob.CRON_PROPERTY),
        Duration.parse(required(environment, AccreditationStepRetryJob.TOLERANCE_PROPERTY)),
        AccreditationStepRetryJob.Collaborators(step, queue, policy, platform, transactions, clock),
    )

    @Bean
    fun accreditationStepRetrySchedule(
        runtime: JobRuntime,
        environment: Environment,
    ) = AccreditationStepRetrySchedule(runtime, required(environment, AccreditationStepRetryJob.CRON_PROPERTY))

    @Bean
    fun accreditationTaskQueries(
        tasks: AccreditationTaskRepository,
        permissions: OperatorPermissions,
        transactions: TransactionRunner,
    ) = AccreditationTaskQueries(tasks, permissions, transactions)

    @Bean
    fun concludeAccreditationTask(
        tasks: AccreditationTaskRepository,
        permissions: OperatorPermissions,
        clock: Clock,
    ) = ConcludeAccreditationTask(tasks, permissions, clock)

    /**
     * The cursor key is read through the SecretStore at every list call and never cached (INV-Q-15432 A, STK-29), so
     * a cursor survives a restart and is the same on every replica; the deployment provisions the secret.
     */
    @Bean
    fun taskCursor(secrets: SecretStore) =
        TaskCursor { secrets.read(SecretRef(CURSOR_KEY_REF)).reveal().toByteArray(Charsets.UTF_8) }

    private object LogReporting : StepReporting {
        private val log = LoggerFactory.getLogger(OpenAccreditationTask::class.java)

        override fun failed(
            target: ReleaseTarget,
            failure: Exception,
        ) = log.warn("the accreditation task step of {} {} failed; the release stands", target.kind, target.ref, failure)

        override fun bookkeepingFailed(
            target: ReleaseTarget,
            failure: Exception,
        ) = log.warn("the retry bookkeeping of {} {} failed; the row stays due", target.kind, target.ref, failure)

        override fun exhausted(
            target: ReleaseTarget,
            error: String,
        ) = log.error("the accreditation step of {} {} is EXHAUSTED after its last error {}", target.kind, target.ref, error)
    }

    companion object {
        const val FIRST_DELAY = "gst.accreditation-step.first-delay"
        const val FIRST_BACKOFF = "gst.accreditation-step.first-backoff"
        const val BACKOFF_CAP = "gst.accreditation-step.backoff-cap"
        const val MAX_ATTEMPTS = "gst.accreditation-step.max-attempts"
        const val BATCH_SIZE = "gst.accreditation-step.batch-size"

        private fun required(
            environment: Environment,
            name: String,
        ) = checkNotNull(environment.getProperty(name)) { "$name is not set" }

        /** The SecretStore ref of the HMAC-SHA256 key of the listAccreditationTasks cursor. */
        const val CURSOR_KEY_REF = "controlplane/accreditation-task-cursor-key"
    }
}
