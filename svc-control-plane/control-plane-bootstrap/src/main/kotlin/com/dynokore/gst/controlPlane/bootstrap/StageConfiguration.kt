package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.stage.PackPipeline
import com.dynokore.gst.controlPlane.adapters.stage.PackStage
import com.dynokore.gst.controlPlane.adapters.stage.PackStageRun
import com.dynokore.gst.controlPlane.adapters.stage.SandboxSubmitter
import com.dynokore.gst.controlPlane.adapters.stage.SandboxVerdict
import com.dynokore.gst.controlPlane.adapters.stage.StageCandidates
import com.dynokore.gst.controlPlane.adapters.stage.StagedSubmission
import com.dynokore.gst.controlPlane.adapters.stage.UnboundPackPipeline
import com.dynokore.gst.controlPlane.application.pack.PackRepository
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling

/** The stage of a regulatory pack (INV-REQ-2662) and its only caller, the stage job (coordinator ruling INV-Q-8725). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class StageConfiguration {
    /**
     * The pipeline of the stage is bound by the deployment (Wave 3 exit). Until then it is unbound and fails closed:
     * nothing is staged, so `publishPack` is refused (coordinator ruling INV-Q-4878).
     */
    @Bean
    @ConditionalOnMissingBean
    fun packPipeline(): PackPipeline = UnboundPackPipeline

    /** Unbound sandbox: it accepts nothing, so even a bound pipeline cannot produce a PASSED record through it. */
    @Bean
    @ConditionalOnMissingBean
    fun sandboxSubmitter(): SandboxSubmitter =
        object : SandboxSubmitter {
            override val target = "SIMULATOR"

            override fun submit(submission: StagedSubmission): SandboxVerdict =
                SandboxVerdict.Rejected("sandbox unbound")
        }

    @Bean
    fun packStage(
        dsl: DSLContext,
        packs: PackRepository,
        ports: PublishPorts,
        pipeline: PackPipeline,
        sandbox: SandboxSubmitter,
    ) = PackStage(dsl, packs, ports.hasher, pipeline, sandbox)

    @Bean
    fun stageCandidates(
        dsl: DSLContext,
        packs: PackRepository,
        ports: PublishPorts,
    ) = StageCandidates(dsl, packs, ports.hasher, ports.staging)

    @Bean
    fun packStageRun(
        candidates: StageCandidates,
        stage: PackStage,
    ) = PackStageRun(candidates, stage)

    /** The only caller of the stage (coordinator ruling INV-Q-8725), registered on the job runtime (INV-Q-9637 A). */
    @Bean
    fun packStageJob(
        runtime: JobRuntime,
        environment: Environment,
        stageRun: PackStageRun,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
    ) = PackStageJob(
        runtime,
        checkNotNull(environment.getProperty("gst.service")),
        cron(environment),
        stageRun,
        platform,
        transactions,
    )

    @Bean
    fun packStageSchedule(
        runtime: JobRuntime,
        environment: Environment,
    ) = PackStageSchedule(runtime, cron(environment))

    private fun cron(environment: Environment) =
        checkNotNull(environment.getProperty(PackStageJob.CRON_PROPERTY)) { "${PackStageJob.CRON_PROPERTY} is not set" }
}
