package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.build.ActivePackRead
import com.dynokore.gst.payload.adapters.build.BuildPayloadJobHandler
import com.dynokore.gst.payload.adapters.build.ConnectionFamilyLookup
import com.dynokore.gst.payload.adapters.build.JooqBuildInputsReader
import com.dynokore.gst.payload.adapters.build.JooqBuildOutcomes
import com.dynokore.gst.payload.adapters.build.JooqTypeDecisionReader
import com.dynokore.gst.payload.adapters.build.OutboxPayloadRejectedPublisher
import com.dynokore.gst.payload.adapters.build.PintSgPayloadProjector
import com.dynokore.gst.payload.adapters.build.PolicySpecificationFamilySource
import com.dynokore.gst.payload.adapters.projection.MdePackOffNetworkPrefix
import com.dynokore.gst.payload.application.MdePackSource
import com.dynokore.gst.payload.application.build.BuildInputsReader
import com.dynokore.gst.payload.application.build.BuildOutcomes
import com.dynokore.gst.payload.application.build.BuildPayload
import com.dynokore.gst.payload.application.build.PayloadProjector
import com.dynokore.gst.payload.application.build.PayloadRejectedPublisher
import com.dynokore.gst.payload.application.build.SpecificationFamilySource
import com.dynokore.gst.payload.application.build.TypeDecisionReader
import com.dynokore.gst.payload.application.payload.PublishPayloadReady
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The beans of the claimed build job: the readers, the outcomes, the `PayloadRejected` publisher, the projector, the
 * use case and the handler. `SpecificationFamilySource` reads the family of the connection on the document date
 * (VP-71). The active pack is read from the replica once per call of [ActivePackOffNetworkPrefix]; with no active
 * pack a build is `BuildNotReady`.
 */
@Configuration(proxyBeanMethods = false)
class BuildPayloadConfiguration {
    @Bean
    fun buildInputsReader(
        dsl: DSLContext,
        tenants: TenantContext,
    ): BuildInputsReader = JooqBuildInputsReader(dsl) { tenants.current().value }

    @Bean
    fun typeDecisionReader(
        dsl: DSLContext,
        tenants: TenantContext,
    ): TypeDecisionReader = JooqTypeDecisionReader(dsl) { tenants.current().value }

    @Bean
    fun buildOutcomes(
        dsl: DSLContext,
        tenants: TenantContext,
    ): BuildOutcomes = JooqBuildOutcomes(dsl) { tenants.current().value }

    @Bean
    fun payloadRejectedPublisher(
        outbox: OutboxWriter,
        tenants: TenantContext,
    ): PayloadRejectedPublisher = OutboxPayloadRejectedPublisher(outbox, tenants)

    /**
     * The family of a request (entity, connection, document date) through the policy path (VP-71): the option the
     * service's [PolicyReader] resolves for the connection on the document date (INV-REQ-2467). The platform's
     * `JdbcPolicyReader` is the one `PolicyReader` bean of the service (service-runtime `ConfigResolverWiring`). A
     * request without a connection or a date fails closed, and the build then ends in a retry and publishes nothing.
     */
    @Bean
    @ConditionalOnMissingBean(SpecificationFamilySource::class)
    fun specificationFamilySource(
        policy: PolicyReader,
        tenants: TenantContext,
    ): SpecificationFamilySource = PolicySpecificationFamilySource(ConnectionFamilyLookup(policy) { tenants.current() })

    /**
     * One read of the active MDE pack per call (INV-AC-23595): no cache, no static state, so the next build reads a
     * newly activated version. `contents` is the stored text of that same read; no active pack gives null.
     */
    @Bean
    @ConditionalOnMissingBean(ActivePackOffNetworkPrefix::class)
    fun activePackOffNetworkPrefix(packs: MdePackSource): ActivePackOffNetworkPrefix =
        ActivePackOffNetworkPrefix {
            packs.active()?.let { ActivePackRead(it.version, MdePackOffNetworkPrefix(it.contents), it.contents) }
        }

    @Bean
    fun payloadProjector(
        families: SpecificationFamilySource,
        activePackPrefix: ObjectProvider<ActivePackOffNetworkPrefix>,
    ): PayloadProjector =
        PintSgPayloadProjector(families, activePack = {
            activePackPrefix.ifAvailable?.active()
        })

    @Bean
    @Suppress("LongParameterList")
    fun buildPayload(
        inputs: BuildInputsReader,
        decisions: TypeDecisionReader,
        projector: PayloadProjector,
        families: SpecificationFamilySource,
        ready: PublishPayloadReady,
        rejected: PayloadRejectedPublisher,
        outcomes: BuildOutcomes,
    ): BuildPayload = BuildPayload(inputs, decisions, projector, families, ready, rejected, outcomes)

    @Bean
    fun buildPayloadJobHandler(
        queue: WorkQueue,
        scope: TenantScope,
        build: BuildPayload,
    ): BuildPayloadJobHandler = BuildPayloadJobHandler(queue, scope, build)
}

/** The one read of the active MDE pack (version, prefix source, contents); absent while no pack is active. */
fun interface ActivePackOffNetworkPrefix {
    fun active(): ActivePackRead?
}
