package com.dynokore.gst.transport.bootstrap

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.jobs.FixedDeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommand
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The two ports service-runtime leaves to the service before it builds its `JobRuntime` (INV-REQ-1136, INV-Q-9684
 * option A). Who may command a job is not decided yet, so nobody may; transport-svc serves no due dates.
 */
@Configuration(proxyBeanMethods = false)
class JobRuntimeConfiguration {
    /** No run-now, pause or resume is permitted to anyone: this reads no role (a later task may bind `authorise`). */
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

    /** No job of transport-svc is deadline-critical yet, so no due date is served. */
    @Bean
    fun deadlineSource(): FixedDeadlineSource = FixedDeadlineSource()
}
