package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.jobs.DeadlineSource
import com.dynokore.platform.serviceruntime.jobs.FixedDeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The two ports service-runtime's JobRuntime leaves to the service (INV-Q-9637 option A). Who may command a job is not
 * ruled yet (INV-Q-8687 is about the OperatorRoles port): until it is, this binding fails closed, so no run-now, pause
 * or resume is permitted to anyone. The job bodies are bound by the tasks that own them.
 */
@Configuration(proxyBeanMethods = false)
class JobRuntimeConfiguration {
    @Bean
    fun jobCommandAuthorizer(): JobCommandAuthorizer =
        object : JobCommandAuthorizer {
            override fun mayCommandEntity(
                actor: String,
                tenant: TenantId,
                entity: EntityRef,
                command: JobCommand,
            ): Boolean = false

            override fun isPlatformOperator(
                actor: String,
                command: JobCommand,
            ): Boolean = false
        }

    /** No job of the service serves a due date yet, so every target answers null. */
    @Bean
    fun deadlineSource(): FixedDeadlineSource = FixedDeadlineSource()
}
