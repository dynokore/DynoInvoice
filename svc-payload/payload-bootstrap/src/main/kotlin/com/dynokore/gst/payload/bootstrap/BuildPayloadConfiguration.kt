package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.build.BuildPayloadJobHandler
import com.dynokore.gst.payload.adapters.build.FamilyLookup
import com.dynokore.gst.payload.adapters.build.JooqBuildInputsReader
import com.dynokore.gst.payload.adapters.build.JooqBuildOutcomes
import com.dynokore.gst.payload.adapters.build.JooqTypeDecisionReader
import com.dynokore.gst.payload.adapters.build.OutboxPayloadRejectedPublisher
import com.dynokore.gst.payload.adapters.build.PintSgPayloadProjector
import com.dynokore.gst.payload.adapters.build.PolicySpecificationFamilySource
import com.dynokore.gst.payload.application.build.BuildInputsReader
import com.dynokore.gst.payload.application.build.BuildOutcomes
import com.dynokore.gst.payload.application.build.BuildPayload
import com.dynokore.gst.payload.application.build.PayloadProjector
import com.dynokore.gst.payload.application.build.PayloadRejectedPublisher
import com.dynokore.gst.payload.application.build.SpecificationFamilySource
import com.dynokore.gst.payload.application.build.TypeDecisionReader
import com.dynokore.gst.payload.application.payload.PublishPayloadReady
import com.dynokore.gst.payload.domain.OffNetworkPrefixSource
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
 * use case and the handler. `SpecificationFamilySource` and the active-pack source of the off-network prefix are
 * not ruled yet (see the MR gaps): the family bean fails closed and, while no [ActivePackOffNetworkPrefix] bean exists,
 * a build is `BuildNotReady`.
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
     * The family of a request (entity, connection, document date) through the policy path (VP-71). The scope level and the date of that path are not
     * ruled (see the MR gaps), so the production lookup names no family and fails closed with an
     * [IllegalStateException]; the build then ends in a retry and publishes nothing. One bean replaces it.
     */
    @Bean
    @ConditionalOnMissingBean(SpecificationFamilySource::class)
    fun specificationFamilySource(): SpecificationFamilySource =
        PolicySpecificationFamilySource(
            FamilyLookup { request ->
                error("the policy scope of the specification family (VP-71) is not ruled; entity ${request.entityRef}")
            },
        )

    @Bean
    fun payloadProjector(
        families: SpecificationFamilySource,
        activePackPrefix: ObjectProvider<ActivePackOffNetworkPrefix>,
    ): PayloadProjector =
        PintSgPayloadProjector(families, offNetworkPrefix = {
            activePackPrefix.ifAvailable?.prefixSource()
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

/** The off-network prefix source of the active MDE pack version; absent while no pack is active. */
fun interface ActivePackOffNetworkPrefix {
    fun prefixSource(): OffNetworkPrefixSource?
}
