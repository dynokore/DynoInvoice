package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.jobs.ChainVerificationJobBody
import com.dynokore.gst.evidence.adapters.jobs.ChainVerificationRecorder
import com.dynokore.gst.evidence.adapters.jobs.VerificationEntitySource
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqIntegrityStore
import com.dynokore.gst.evidence.adapters.persistence.ObjectStoreDigestCopies
import com.dynokore.gst.evidence.application.integrity.ChainVerification
import com.dynokore.gst.evidence.application.integrity.ChainVerificationJob
import com.dynokore.gst.evidence.application.integrity.DigestCopies
import com.dynokore.gst.evidence.application.integrity.IntegrityStatusQueryHandler
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The routine verification of the evidence chains (INV-Q-7251 A (4), INV-Q-7253 A): the verification, the status query
 * and, when the service has a [JobRuntime], the registration of the job `evidence-chain-verification`.
 */
@Configuration(proxyBeanMethods = false)
class ChainVerificationConfiguration {
    @Bean
    @ConditionalOnMissingBean(DigestCopies::class)
    fun digestCopies(store: ObjectStore): DigestCopies = ObjectStoreDigestCopies(store)

    @Bean
    fun chainVerification(
        dsl: DSLContext,
        copies: DigestCopies,
    ): ChainVerification = ChainWiring.chainVerification(dsl, copies)

    @Bean
    fun chainVerificationRecorder(
        dsl: DSLContext,
        verification: ChainVerification,
        outbox: OutboxWriter,
        clock: Clock,
    ): ChainVerificationRecorder = ChainWiring.chainVerificationRecorder(dsl, verification, outbox, clock)

    @Bean
    fun integrityStatusQuery(dsl: DSLContext): IntegrityStatusQueryHandler =
        IntegrityStatusQueryHandler(JooqIntegrityStore(dsl))

    /**
     * Registers the job in the service's [JobRuntime]. The runtime exists only in a service that supplies the job
     * command authorizer and the deadline source it needs; without one nothing is registered.
     */
    @Bean
    fun chainVerificationJobRegistration(
        runtime: ObjectProvider<JobRuntime>,
        scope: TenantScope,
        dsl: DSLContext,
        recorder: ChainVerificationRecorder,
    ) = SmartInitializingSingleton {
        runtime.ifAvailable { registerChainVerificationJob(it, scope, dsl, recorder) }
    }

    companion object {
        /** Registers the job with exactly [ChainVerificationJob.DEFINITION]. */
        fun registerChainVerificationJob(
            runtime: JobRuntime,
            scope: TenantScope,
            dsl: DSLContext,
            recorder: ChainVerificationRecorder,
        ) = runtime.register(
            ChainVerificationJob.DEFINITION,
            VerificationEntitySource(scope, dsl),
            ChainVerificationJobBody(ChainVerificationJob.DEFINITION.ownerService, scope, recorder),
        )
    }
}
