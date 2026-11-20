package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.http.ReplicaCaseAuthorizer
import com.dynokore.gst.evidence.adapters.persistence.JooqCaseStore
import com.dynokore.gst.evidence.application.cases.CaseAuthorizer
import com.dynokore.gst.evidence.application.cases.CaseService
import com.dynokore.gst.evidence.application.cases.CaseStore
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The beans of the case operations `getCase` and `recordSecurityCase` (INV-T-11694): the jOOQ case store over the
 * tenant-bound [DSLContext], the authorizer over the service's one `AuthorityReplica` and the use case. The clock that
 * stamps `opened_at` ticks in microseconds, the precision of the column, so the stamp the 201 answer carries equals the
 * stored value.
 */
@Configuration(proxyBeanMethods = false)
class CaseConfiguration {
    @Bean
    fun caseStore(dsl: DSLContext): JooqCaseStore = JooqCaseStore(dsl)

    @Bean
    fun caseAuthorizer(replica: AuthorityReplica): CaseAuthorizer = ReplicaCaseAuthorizer(replica)

    @Bean
    fun caseService(
        store: CaseStore,
        authorizer: CaseAuthorizer,
        clock: Clock,
    ): CaseService =
        CaseService(
            store,
            authorizer,
            READ_PERMISSION,
            { UUID.randomUUID() },
            Clock.tick(clock, Duration.ofNanos(MICRO_NANOS)),
        )

    companion object {
        /** The permission key `getCase` checks (owner ruling 2026-11-18, INV-Q-11701 A). */
        const val READ_PERMISSION = "privacy-case.read"
        private const val MICRO_NANOS = 1_000L
    }
}
