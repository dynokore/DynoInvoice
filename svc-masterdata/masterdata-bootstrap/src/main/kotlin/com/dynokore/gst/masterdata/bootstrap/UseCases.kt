package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.persistence.JooqPendingChangeRepository
import com.dynokore.gst.masterdata.adapters.persistence.JooqTripleRepository
import com.dynokore.gst.masterdata.application.identity.ConfirmIdentityTripleChange
import com.dynokore.gst.masterdata.application.identity.ConfirmerPolicy
import com.dynokore.gst.masterdata.application.identity.ConfirmerRoles
import com.dynokore.gst.masterdata.application.identity.PendingChangeRepository
import com.dynokore.gst.masterdata.application.identity.ProposeIdentityTripleChange
import com.dynokore.gst.masterdata.application.identity.ResolveIdentityTriple
import com.dynokore.gst.masterdata.application.identity.TripleRepository
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * TPL-18: the only place a use case is constructed. No DataSource bean here or anywhere in the service (owner ruling
 * INV-Q-4196): the one application pool is Spring Boot's `dataSource` from spring.datasource.* (TPL-16).
 */
@Configuration(proxyBeanMethods = false)
class UseCases {
    @Bean
    fun tripleRepository(
        dsl: DSLContext,
        clock: Clock,
    ): TripleRepository = JooqTripleRepository(dsl, clock)

    @Bean
    fun resolveIdentityTriple(
        tx: TransactionRunner,
        triples: TripleRepository,
    ) = ResolveIdentityTriple(tx, triples)

    @Bean
    fun pendingChangeRepository(
        dsl: DSLContext,
        clock: Clock,
    ): PendingChangeRepository = JooqPendingChangeRepository(dsl, clock)

    @Bean
    fun proposeIdentityTripleChange(
        tx: TransactionRunner,
        changes: PendingChangeRepository,
    ) = ProposeIdentityTripleChange(tx, changes)

    @Bean
    fun confirmIdentityTripleChange(
        tx: TransactionRunner,
        changes: PendingChangeRepository,
        triples: TripleRepository,
        policy: ConfirmerPolicy,
        roles: ConfirmerRoles,
    ) = ConfirmIdentityTripleChange(tx, changes, triples, policy, roles)
}
