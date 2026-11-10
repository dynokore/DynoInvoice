package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.http.TenantScheduleEndpoint
import com.dynokore.gst.controlPlane.adapters.persistence.TenantScheduleRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.schedule.ScheduleJobAuditWriter
import com.dynokore.gst.controlPlane.adapters.tenancy.HttpTenantOpener
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.schedule.ApproveTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.CancelTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.ChangeTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.GetTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.OpenDueTenants
import com.dynokore.gst.controlPlane.application.schedule.ScheduleJobAudit
import com.dynokore.gst.controlPlane.application.schedule.SetTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.TenantOpener
import com.dynokore.gst.controlPlane.application.schedule.TenantScheduleRepository
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenSource
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** The tenant schedule (INV-REQ-8100, INV-T-8143): repository and operations. */
@Configuration(proxyBeanMethods = false)
@Suppress("LongParameterList") // one bean method per collaborator of a use case (TPL-18)
class TenantScheduleConfiguration {
    @Bean
    fun tenantScheduleRepository(dsl: DSLContext): TenantScheduleRepository = TenantScheduleRepositoryJooq(dsl)

    @Bean
    fun setTenantSchedule(
        schedules: TenantScheduleRepository,
        permissions: OperatorPermissions,
        clock: Clock,
    ) = SetTenantSchedule(schedules, permissions, clock) {
        "SCH-" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(REF_DIGITS)
                .uppercase()
    }

    @Bean
    fun approveTenantSchedule(
        schedules: TenantScheduleRepository,
        permissions: OperatorPermissions,
        clock: Clock,
    ) = ApproveTenantSchedule(schedules, permissions, clock)

    @Bean
    fun changeTenantSchedule(
        schedules: TenantScheduleRepository,
        permissions: OperatorPermissions,
        clock: Clock,
    ) = ChangeTenantSchedule(schedules, permissions, clock)

    @Bean
    fun cancelTenantSchedule(
        schedules: TenantScheduleRepository,
        permissions: OperatorPermissions,
    ) = CancelTenantSchedule(schedules, permissions)

    @Bean
    fun getTenantSchedule(
        schedules: TenantScheduleRepository,
        permissions: OperatorPermissions,
    ) = GetTenantSchedule(schedules, permissions)

    @Bean
    fun tenantScheduleEndpoint(
        principals: EndUserPrincipalResolver,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
        runner: OperationRunner,
        set: SetTenantSchedule,
        approve: ApproveTenantSchedule,
        change: ChangeTenantSchedule,
        cancel: CancelTenantSchedule,
        get: GetTenantSchedule,
    ) = TenantScheduleEndpoint(principals, platform, transactions, runner, set, approve, change, cancel, get)

    private companion object {
        const val REF_DIGITS = 12
    }
}

/** The tenancy-svc client and the opening job (INV-REQ-8100 behaviors 3 and 6, INV-T-8143). */
@Configuration(proxyBeanMethods = false)
class TenantScheduleOpeningConfiguration {
    @Bean
    fun tenantOpener(
        settings: TenancySettings,
        workloadTokenSource: WorkloadTokenSource,
    ): TenantOpener = HttpTenantOpener(java.net.URI.create(checkNotNull(settings.baseUrl)), workloadTokenSource)

    @Bean
    fun scheduleJobAudit(audit: AuditWriter): ScheduleJobAudit = ScheduleJobAuditWriter(audit)

    @Bean
    fun openDueTenants(
        schedules: TenantScheduleRepository,
        opener: TenantOpener,
        audit: ScheduleJobAudit,
        transactions: TransactionRunner,
        clock: Clock,
    ) = OpenDueTenants(schedules, opener, audit, transactions, clock)

    @Bean
    fun tenantScheduleOpeningJob(
        runtime: JobRuntime,
        environment: Environment,
        with: TenantScheduleOpeningJob.Collaborators,
    ) = TenantScheduleOpeningJob(
        runtime,
        required(environment, "gst.service"),
        required(environment, TenantScheduleOpeningJob.CRON_PROPERTY),
        Duration.parse(required(environment, TenantScheduleOpeningJob.TOLERANCE_PROPERTY)),
        with,
    )

    @Bean
    fun tenantScheduleOpeningCollaborators(
        step: OpenDueTenants,
        platform: PlatformTenantBinding,
        environment: Environment,
    ) = TenantScheduleOpeningJob.Collaborators(
        step,
        platform,
        required(environment, TenantScheduleOpeningJob.BATCH_PROPERTY).toInt(),
    )

    @Bean
    fun tenantScheduleOpeningSchedule(
        runtime: JobRuntime,
        environment: Environment,
    ) = TenantScheduleOpeningSchedule(runtime, required(environment, TenantScheduleOpeningJob.CRON_PROPERTY))

    private companion object {
        fun required(
            environment: Environment,
            name: String,
        ) = checkNotNull(environment.getProperty(name)) { "$name is not set" }
    }
}
