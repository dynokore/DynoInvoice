package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.persistence.AccreditationStepQueueJooq
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationStepQueue
import com.dynokore.gst.controlPlane.application.accreditation.OpenAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.ReleaseTarget
import com.dynokore.gst.controlPlane.application.accreditation.StepReporting
import com.dynokore.gst.controlPlane.application.accreditation.StepRetryPolicy
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.Duration

/** The durable retry of the accreditation step: settings, queue, reporting, relay job and its cron (INV-Q-17708 A). */
@Configuration(proxyBeanMethods = false)
class AccreditationStepRetryConfiguration {
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

    @Bean
    fun stepReporting(): StepReporting = LogReporting

    /** The relay of the durable retry: the job `accreditation-step-retry` (INV-Q-17708 A). */
    @Bean
    fun accreditationStepRetryJob(
        runtime: JobRuntime,
        environment: Environment,
        with: AccreditationStepRetryJob.Collaborators,
    ) = AccreditationStepRetryJob(
        runtime,
        required(environment, "gst.service"),
        required(environment, AccreditationStepRetryJob.CRON_PROPERTY),
        Duration.parse(required(environment, AccreditationStepRetryJob.TOLERANCE_PROPERTY)),
        with,
    )

    @Bean
    fun accreditationStepRetryCollaborators(
        step: OpenAccreditationTask,
        policy: StepRetryPolicy,
        platform: PlatformTenantBinding,
    ) = AccreditationStepRetryJob.Collaborators(step, policy, platform)

    @Bean
    fun accreditationStepRetrySchedule(
        runtime: JobRuntime,
        environment: Environment,
    ) = AccreditationStepRetrySchedule(runtime, required(environment, AccreditationStepRetryJob.CRON_PROPERTY))

    private object LogReporting : StepReporting {
        private val log = LoggerFactory.getLogger(OpenAccreditationTask::class.java)

        override fun failed(
            target: ReleaseTarget,
            failure: Exception,
        ) = log.warn(
            "the accreditation task step of {} {} failed; the release stands",
            target.kind,
            target.ref,
            failure,
        )

        override fun bookkeepingFailed(
            target: ReleaseTarget,
            failure: Exception,
        ) = log.warn("the retry bookkeeping of {} {} failed; the row stays due", target.kind, target.ref, failure)

        override fun exhausted(
            target: ReleaseTarget,
            error: String,
        ) = log.error(
            "the accreditation step of {} {} is EXHAUSTED after its last error {}",
            target.kind,
            target.ref,
            error,
        )
    }

    private companion object {
        const val FIRST_DELAY = "gst.accreditation-step.first-delay"
        const val FIRST_BACKOFF = "gst.accreditation-step.first-backoff"
        const val BACKOFF_CAP = "gst.accreditation-step.backoff-cap"
        const val MAX_ATTEMPTS = "gst.accreditation-step.max-attempts"
        const val BATCH_SIZE = "gst.accreditation-step.batch-size"

        private fun required(
            environment: Environment,
            name: String,
        ) = checkNotNull(environment.getProperty(name)) { "$name is not set" }
    }
}
