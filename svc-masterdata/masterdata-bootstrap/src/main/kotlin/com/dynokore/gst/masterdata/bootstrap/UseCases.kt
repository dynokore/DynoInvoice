package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.persistence.JooqTripleRepository
import com.dynokore.gst.masterdata.application.identity.ConfigureIdentityTriple
import com.dynokore.gst.masterdata.application.identity.ConfirmerPolicy
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
    fun configureIdentityTriple(
        tx: TransactionRunner,
        triples: TripleRepository,
        policy: ConfirmerPolicy,
    ) = ConfigureIdentityTriple(tx, triples, policy)
}
