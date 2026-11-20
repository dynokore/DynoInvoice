package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ConfigResolverPolicyValues
import com.dynokore.gst.evidence.adapters.persistence.JooqCaseStore
import com.dynokore.gst.evidence.adapters.persistence.JooqNoticeEvents
import com.dynokore.gst.evidence.application.cases.CaseAuthorizer
import com.dynokore.gst.evidence.application.cases.NoticePrompter
import com.dynokore.gst.evidence.application.cases.PolicyValues
import com.dynokore.gst.evidence.application.cases.PrivacyCaseService
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.OutboxWriter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The beans of `openPrivacyCase`, `closePrivacyCase` and the notice step (INV-T-6842; INV-REQ-1403): the use case, the
 * [NoticePrompter] and their adapters over the beans of [CaseConfiguration]. It adds no scheduler: `promptNotices` runs
 * for one tenant per call and no published text says which scheduler calls it for every tenant.
 */
@Configuration(proxyBeanMethods = false)
class PrivacyConfiguration {
    @Bean
    fun policyValues(reader: PolicyReader): PolicyValues = ConfigResolverPolicyValues(reader)

    @Bean
    fun noticeEvents(outbox: OutboxWriter): JooqNoticeEvents = JooqNoticeEvents(outbox)

    @Bean
    fun privacyCaseService(
        store: JooqCaseStore,
        authorizer: CaseAuthorizer,
        policy: PolicyValues,
        clock: Clock,
    ): PrivacyCaseService =
        PrivacyCaseService(store, authorizer, policy, { UUID.randomUUID() }, Clock.tick(clock, MICRO))

    @Bean
    fun noticePrompter(
        store: JooqCaseStore,
        policy: PolicyValues,
        events: JooqNoticeEvents,
        clock: Clock,
    ): NoticePrompter = NoticePrompter(store, policy, events, Clock.tick(clock, MICRO))

    private companion object {
        val MICRO: Duration = Duration.ofNanos(1_000L)
    }
}
