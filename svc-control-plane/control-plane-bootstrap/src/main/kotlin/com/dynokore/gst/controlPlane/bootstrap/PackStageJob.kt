package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.stage.PackStageRun
import com.dynokore.platform.serviceruntime.jobs.JobBody
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Duration

/**
 * The only caller of `PackStage.run` (coordinator ruling INV-Q-8725): a PLATFORM-scoped job of control-plane-svc,
 * registered on the service's [JobRuntime] (INV-Q-9637 A), that stages every SIGNED pack version with no staging record
 * for its current content hash and every version whose latest record is PENDING (`StageCandidates`). The cron comes
 * from `gst.jobs.pack-stage.cron` and is fired by [PackStageSchedule]; the job runs no scheduler of its own. No
 * operation starts the stage. With the pipeline unbound the stage writes no record, so the job changes nothing and
 * `publishPack` stays refused.
 */
class PackStageJob(
    runtime: JobRuntime,
    service: String,
    cron: String,
    private val run: PackStageRun,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
) {
    private val actor = service

    val definition =
        JobDefinition(NAME, service, JobEntityScope.PLATFORM, JobSchedule(cron), JobCriticality.STANDARD, TOLERANCE)

    init {
        runtime.register(definition, body = JobBody { runOnce() })
    }

    /**
     * Stages every candidate and returns what each run did. The job runs under the platform tenant, acting as the
     * service's workload (ARC-21): the pack tables are platform-scoped, so the tenant guard needs the binding.
     */
    fun runOnce() = platform.bindPlatform(actor) { transactions.inTransaction { run.runOnce() } }

    companion object {
        const val NAME = "pack-stage"
        const val CRON_PROPERTY = "gst.jobs.pack-stage.cron"
        val TOLERANCE: Duration = Duration.ofMinutes(10)
    }
}
