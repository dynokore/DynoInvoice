package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqAcknowledgementLocatorStore
import com.dynokore.gst.evidence.adapters.persistence.JooqSubmissionIndexReader
import com.dynokore.gst.evidence.application.auditedread.AuditedReads
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.gst.evidence.application.lookup.AcknowledgementChainLookup
import com.dynokore.gst.evidence.application.lookup.ReverseLookup
import com.dynokore.platform.auditlog.AuditedOperations
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The beans of the lookup operations (INV-REQ-691): the audited-read wrapper over the platform [AuditedOperations]
 * bean and the use case of `getAcknowledgementChain` over the jOOQ locator store, the chain record reader that
 * [ChainWiring.chainRecordReader] builds (the version-column rule stays in one place) and the integrity status query
 * of [ChainVerificationConfiguration]. `reverseLookup` reuses these beans.
 */
@Configuration(proxyBeanMethods = false)
class LookupConfiguration {
    @Bean
    @ConditionalOnMissingBean(AuditedReads::class)
    fun auditedReads(operations: AuditedOperations): AuditedReads = AuditedReads(operations)

    @Bean
    fun acknowledgementChainLookup(
        replica: AuthorityReplica,
        dsl: DSLContext,
        integrityStatusQuery: IntegrityStatusQueryHandler,
        audited: AuditedReads,
    ): AcknowledgementChainLookup =
        AcknowledgementChainLookup(
            replica,
            JooqAcknowledgementLocatorStore(dsl),
            ChainWiring.chainRecordReader(dsl),
            integrityStatusQuery,
            audited,
        )

    @Bean
    fun reverseLookup(
        replica: AuthorityReplica,
        dsl: DSLContext,
        integrityStatusQuery: IntegrityStatusQueryHandler,
        audited: AuditedReads,
    ): ReverseLookup = ReverseLookup(replica, JooqSubmissionIndexReader(dsl), integrityStatusQuery, audited)
}
