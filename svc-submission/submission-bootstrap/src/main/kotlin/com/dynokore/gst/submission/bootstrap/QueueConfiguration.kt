package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionStore
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.platform.outboxandqueue.LaneLimits
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The partition-ordered queue of the `submission` schema (INV-REQ-1194). service-runtime creates `WorkQueue` and
 * `LaneRateLimiter` when the service declares its lane limits. The skeleton sets none: a lane without an entry is
 * unlimited, and the task that owns a lane's rate adds its entry here.
 */
@Configuration(proxyBeanMethods = false)
class QueueConfiguration {
    @Bean
    fun laneLimits(): LaneLimits = LaneLimits()

    @Bean
    fun submissionStore(dsl: DSLContext): SubmissionStore = JooqSubmissionStore(dsl)

    @Bean
    fun submissionQueue(
        tenants: TenantContext,
        transactions: TransactionRunner,
        store: SubmissionStore,
        queue: WorkQueue,
    ): SubmissionQueue = SubmissionQueue(tenants, transactions, store, queue)
}
