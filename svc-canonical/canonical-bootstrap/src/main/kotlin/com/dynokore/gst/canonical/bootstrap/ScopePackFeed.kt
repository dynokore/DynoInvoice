package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.ScopePackReader
import com.dynokore.gst.canonical.adapters.persistence.JooqScopePackReplica
import com.dynokore.gst.canonical.adapters.persistence.ScopePackActivation
import com.dynokore.gst.canonical.adapters.persistence.StagedScopePack
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The write side of the staged scope pack versions; true when the version was new and stored. */
fun interface ScopePackStagedSink {
    fun stage(pack: StagedScopePack): Boolean
}

/** The write side of the scope pack activations; true when the activation was new and stored. */
fun interface ScopePackActivationSink {
    fun activate(activation: ScopePackActivation): Boolean
}

/**
 * INV-REQ-772, ruling of INV-T-25433 (event-carried state transfer): the subscriptions of canonical-svc to
 * `RulePackStaged` and `RulePackActivated` as two inbox consumers. Each handler runs in the consumer's transaction and
 * stores the scope pack version or activation idempotently by (identifier, version). A malformed event, or one whose
 * tenant is not the platform tenant, is a contract violation (dead letter).
 */
@Configuration(proxyBeanMethods = false)
class ScopePackFeed {
    @Bean
    fun scopePackStagedHandler(
        dsl: DSLContext,
        clock: Clock,
    ): EventHandler {
        val replica = JooqScopePackReplica(dsl, clock)
        return ScopePackStagedHandler(ScopePackReader(), ScopePackStagedSink { replica.stage(it) })
    }

    @Bean
    fun scopePackActivatedHandler(
        dsl: DSLContext,
        clock: Clock,
    ): EventHandler {
        val replica = JooqScopePackReplica(dsl, clock)
        return ScopePackActivatedHandler(ScopePackReader(), ScopePackActivationSink { replica.activate(it) })
    }

    @Bean
    @Suppress("LongParameterList")
    fun scopePackStagedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        scopePackStagedHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(STAGED_CONSUMER, STAGED_TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            scopePackStagedHandler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun scopePackActivatedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        scopePackActivatedHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(ACTIVATED_CONSUMER, ACTIVATED_TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            scopePackActivatedHandler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val STAGED_CONSUMER = "canonical-svc-rule-pack-staged"
        const val STAGED_TOPIC = "gst.platform.rule-pack-staged.v1"
        const val STAGED_EVENT_TYPE = "com.dynokore.gst.platform.RulePackStaged.v1"
        const val ACTIVATED_CONSUMER = "canonical-svc-rule-pack-activated"
        const val ACTIVATED_TOPIC = "gst.platform.rule-pack-activated.v1"
        const val ACTIVATED_EVENT_TYPE = "com.dynokore.gst.platform.RulePackActivated.v1"
        const val SCHEMA = "canonical"
    }
}

/** A staged pack that is not a scope pack, and a version already held, are IGNORED. */
class ScopePackStagedHandler(
    private val reader: ScopePackReader,
    private val store: ScopePackStagedSink,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome = apply(event.dataJson, event.tenant)

    /** The handling by payload and envelope tenant (the part of the envelope the handler reads). */
    fun apply(
        dataJson: String,
        tenant: TenantId,
    ): HandleOutcome {
        val pack = reader.readStaged(dataJson, tenant) ?: return HandleOutcome.IGNORED
        return if (store.stage(pack)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}

/** An activation already held for the same (identifier, version) is IGNORED. */
class ScopePackActivatedHandler(
    private val reader: ScopePackReader,
    private val store: ScopePackActivationSink,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome = apply(event.dataJson, event.tenant)

    /** The handling by payload and envelope tenant (the part of the envelope the handler reads). */
    fun apply(
        dataJson: String,
        tenant: TenantId,
    ): HandleOutcome {
        val activation = reader.readActivation(dataJson, tenant)
        return if (store.activate(activation)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
