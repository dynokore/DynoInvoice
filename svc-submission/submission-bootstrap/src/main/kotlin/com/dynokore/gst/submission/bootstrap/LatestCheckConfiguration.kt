package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.gate.AuthorityReplicaOverrideAuthority
import com.dynokore.gst.submission.adapters.persistence.JooqReadinessCheckStore
import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.gst.submission.application.readiness.GetLatestReadinessCheck
import com.dynokore.gst.submission.application.readiness.ReadinessCheckReader
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Each read of the port in the request's tenant transaction; the permission decision runs before it is opened. */
internal class TransactionalReadinessCheckReader(
    private val store: ReadinessCheckReader,
    private val transactions: TransactionRunner,
) : ReadinessCheckReader {
    override fun latest(
        entityRef: String,
        periodRef: String,
    ): StoredReadinessCheck? = transactions.inTransaction { store.latest(entityRef, periodRef) }

    override fun page(
        entityRef: String,
        periodRef: String,
        limit: Int,
        before: Long?,
    ): List<StoredReadinessCheck> = transactions.inTransaction { store.page(entityRef, periodRef, limit, before) }
}

/** TPL-18 wiring of `getLatestReadinessCheck` (INV-REQ-500, INV-AC-21137): reader, authority and use case. */
@Configuration(proxyBeanMethods = false)
class LatestCheckConfiguration {
    @Bean
    fun readinessCheckReader(
        dsl: DSLContext,
        tenants: TenantContext,
        runner: TransactionRunner,
    ): ReadinessCheckReader = TransactionalReadinessCheckReader(JooqReadinessCheckStore(dsl, tenants), runner)

    @Bean
    fun overrideAuthority(
        replica: AuthorityReplica,
        tenants: TenantContext,
    ): OverrideAuthority = AuthorityReplicaOverrideAuthority(replica, tenants)

    @Bean
    fun getLatestReadinessCheck(
        reader: ReadinessCheckReader,
        authority: OverrideAuthority,
    ): GetLatestReadinessCheck = GetLatestReadinessCheck(reader, authority)
}
