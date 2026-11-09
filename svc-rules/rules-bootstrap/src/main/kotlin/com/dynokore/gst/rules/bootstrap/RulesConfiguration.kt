package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.events.OutboxPackEventEmitter
import com.dynokore.gst.rules.adapters.events.RulesEventCatalogue
import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.adapters.persistence.ArmedPackLookup
import com.dynokore.gst.rules.adapters.persistence.JooqActivePackReader
import com.dynokore.gst.rules.adapters.persistence.PersistedPackRegistry
import com.dynokore.gst.rules.adapters.persistence.jooq.Rules
import com.dynokore.gst.rules.adapters.scope.PackScopeVerdictPort
import com.dynokore.gst.rules.adapters.scope.ProfileScopeFacts
import com.dynokore.gst.rules.adapters.scope.VoluntaryOverSubmission
import com.dynokore.gst.rules.application.ActivePackReader
import com.dynokore.gst.rules.application.DocumentCoder
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.application.ListActiveRegulatoryPacks
import com.dynokore.gst.rules.application.ListCursorKey
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.scope.ScopeVerdictPort
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.rulepackkernel.evaluation.Evaluator
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Schema
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The only place a use case is constructed; `application` stays annotation-free (ARC-03, TPL-18). */
@Configuration(proxyBeanMethods = false)
@Suppress("TooManyFunctions") // one bean method per wired port; the composition root is the single place (TPL-18)
class RulesConfiguration {
    /** The generated schema, checked at start-up against the table classes (INV-Q-4815, INV-ADR-3968 item 4a). */
    @Bean
    fun rulesSchema(): Schema = Rules.RULES

    /** The event types catalogued for rules; the emission guard refuses any other (INV-AC-1869). */
    @Bean
    fun eventCatalogue(): EventCatalogue = RulesEventCatalogue.catalogue()

    @Bean
    fun packEventEmitter(writer: OutboxWriter): PackEventEmitter = OutboxPackEventEmitter(writer)

    /** The holder copy of the verified packs; the only reader of `rules.pack_artefact` (INV-Q-7438). */
    @Bean
    fun persistedPackRegistry(
        dsl: DSLContext,
        tenants: TenantContext,
    ): PersistedPackRegistry = PersistedPackRegistry(dsl, tenants)

    /** The ARMED version in force on the caller's date, its artefact decoded into domain types (INV-Q-11614). */
    @Bean
    fun packContentsReader(
        dsl: DSLContext,
        registry: PersistedPackRegistry,
    ): PackContentsReader = PackContentsReader(ArmedPackLookup(dsl), registry::artefactOf)

    /**
     * The one production scope verdict port (INV-Q-13820): the SCOPE pack in force on the transaction date decides,
     * the catalogue supplies the standard-rated purchase codes.
     */
    @Bean
    fun scopeVerdictPort(
        dsl: DSLContext,
        registry: PersistedPackRegistry,
        reader: PackContentsReader,
    ): ScopeVerdictPort = PackScopeVerdictPort(ArmedPackLookup(dsl), registry::artefactOf, reader)

    /** The obligation start and registration end of the entity, from the structure replica (INV-Q-16175). */
    @Bean
    fun profileScopeFacts(
        structure: StructureReplica,
        clock: Clock,
    ): ProfileScopeFacts = ProfileScopeFacts(structure, clock)

    /** The VP-21 election of the entity, from the policy replica only (INV-REQ-1282). */
    @Bean
    fun voluntaryOverSubmission(policy: PolicyReplica): VoluntaryOverSubmission = VoluntaryOverSubmission(policy)

    /**
     * The evaluation core over the armed packs of rules-svc's own schema, with the per-line coding over the decoded
     * packs. The rules and the pack types an evaluation binds are registered by the tasks that specialise it.
     */
    @Bean
    fun evaluateDocument(
        dsl: DSLContext,
        reader: PackContentsReader,
        scopeVerdicts: ObjectProvider<ScopeVerdictPort>,
    ): EvaluateDocument =
        EvaluateDocument(
            PackResolver(ArmedPackLookup(dsl)),
            Evaluator(emptyList()),
            emptyList(),
            DocumentCoder(reader, reader, reader),
            // the one ScopeVerdictPort bean; a second bean fails the start of the context (NoUniqueBeanDefinition)
            scopeVerdicts.getIfAvailable(),
        )

    /**
     * The runtime offers no [RebuildRequiredSink] bean, and the entity-profile consumer below is created only when one
     * exists. rules-svc records the signal in the log (the probe service's choice); a service that routes it further
     * declares its own bean.
     */
    @Bean
    @ConditionalOnMissingBean(RebuildRequiredSink::class)
    fun rebuildRequiredSink(): RebuildRequiredSink {
        val log = LoggerFactory.getLogger(RulesConfiguration::class.java)
        return RebuildRequiredSink { log.warn("rebuild required: {} on {}", it.consumer, it.topic) }
    }

    /**
     * rules-svc is a subscriber of `gst.tenancy.entity-profile-changed.v1` (platform-contracts event-log/topics.yaml):
     * the consumer applies each `EntityProfileChanged` to the structure replica, which holds `obligationStartDate` and
     * `registrationEndDate` for the submission window (INV-Q-16175). The catalogue below is the consumer's own and not
     * a bean: rules-svc publishes only its three pack events. The bean is unconditional: a condition on beans of the
     * runtime's auto-configuration is evaluated before they exist and silently left the replica without a feed.
     */
    @Bean
    @Suppress("LongParameterList") // the consumer's five collaborators are all injected platform beans
    fun entityProfileConsumer(
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        @Qualifier("structureFeedHandler") handler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(RulesEventCatalogue.SERVICE, ProductEventTypes.ENTITY_PROFILE_CHANGED_TOPIC, SCHEMA),
            EventCatalogue(
                listOf(
                    CatalogueEntry(
                        ProductEventTypes.ENTITY_PROFILE_CHANGED,
                        ProductEventTypes.ENTITY_PROFILE_CHANGED_TOPIC,
                        setOf(CatalogueEntry.EVERY_SERVICE),
                        contentTopic = false,
                    ),
                ),
            ),
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), scope.tenants, scope.transactions),
            handler,
            rebuildRequired,
            clock,
        )

    /** The cursor integrity key is read through SecretStore at every use (STK-29): no restart on rotation. */
    @Bean
    fun listCursorKey(secrets: SecretStore): ListCursorKey =
        ListCursorKey { secrets.read(SecretRef(CURSOR_KEY_SECRET)).reveal().toByteArray(Charsets.UTF_8) }

    /** The installer's read of the armed platform packs: no tenant binding, through the definer function. */
    @Bean
    fun activePackReader(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
    ): ActivePackReader = JooqActivePackReader(dsl, platform, transactions)

    @Bean
    fun listActiveRegulatoryPacks(
        reader: ActivePackReader,
        key: ListCursorKey,
    ): ListActiveRegulatoryPacks = ListActiveRegulatoryPacks(reader, key)

    private companion object {
        const val CURSOR_KEY_SECRET = "rules/active-packs-cursor-key"
        const val SCHEMA = "rules"
    }
}
