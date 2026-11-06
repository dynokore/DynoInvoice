package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.application.accreditation.AccreditationStepQueue
import com.dynokore.gst.controlPlane.application.accreditation.OpenAccreditationTask
import com.dynokore.gst.controlPlane.application.accreditation.StepRetryPolicy
import com.dynokore.platform.serviceruntime.jobs.JobBody
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Duration

/**
 * The relay of the durable retry (INV-Q-17708 A): a PLATFORM-scoped job of control-plane-svc, registered on the
 * service's [JobRuntime] like [PackStageJob]. Each run reads the rows of `accreditation_step_pending` that are PENDING
 * and due, oldest first, in a batch, and runs the step for each; the step claims its row with `for update skip locked`
 * and is idempotent, so an overlapping run (the first attempt or another replica) opens one task and raises one event.
 * The cron comes from `gst.jobs.accreditation-step-retry.cron` and is fired by [AccreditationStepRetrySchedule]; the
 * job runs no scheduler of its own. It runs under `platform.bindPlatform`.
 */
class AccreditationStepRetryJob(
    runtime: JobRuntime,
    service: String,
    cron: String,
    tolerance: Duration,
    private val with: Collaborators,
) {
    /** What the job runs with. */
    class Collaborators(
        val step: OpenAccreditationTask,
        val queue: AccreditationStepQueue,
        val policy: StepRetryPolicy,
        val platform: PlatformTenantBinding,
        val transactions: TransactionRunner,
        val clock: Clock,
    )

    private val actor = service

    val definition =
        JobDefinition(NAME, service, JobEntityScope.PLATFORM, JobSchedule(cron), JobCriticality.STANDARD, tolerance)

    init {
        runtime.register(definition, body = JobBody { runOnce() })
    }

    /** Runs the step for each due row of one batch and returns how many rows the batch held. */
    fun runOnce(): Int =
        with.platform.bindPlatform(actor) {
            val due = with.transactions.inTransaction { with.queue.due(with.clock.instant(), with.policy.batchSize) }
            due.forEach(with.step::retry)
            due.size
        }

    companion object {
        const val NAME = "accreditation-step-retry"
        const val CRON_PROPERTY = "gst.jobs.accreditation-step-retry.cron"
        const val TOLERANCE_PROPERTY = "gst.jobs.accreditation-step-retry.tolerance"
    }
}
