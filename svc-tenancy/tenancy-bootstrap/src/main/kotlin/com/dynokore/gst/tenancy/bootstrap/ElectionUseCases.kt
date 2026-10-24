package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.AccessPointRouteChangedHandler
import com.dynokore.gst.tenancy.adapters.election.ConfigElectionPolicyStore
import com.dynokore.gst.tenancy.adapters.election.ConsumedElectionEvents
import com.dynokore.gst.tenancy.adapters.election.ElectionPolicyWriterGuard
import com.dynokore.gst.tenancy.adapters.election.IssuanceProfileExtras
import com.dynokore.gst.tenancy.adapters.election.ReplicaActionAuthority
import com.dynokore.gst.tenancy.adapters.election.RequestCallerSource
import com.dynokore.gst.tenancy.adapters.election.RulePackActivatedHandler
import com.dynokore.gst.tenancy.adapters.election.RulePackStagedHandler
import com.dynokore.gst.tenancy.adapters.election.UnboundApprovers
import com.dynokore.gst.tenancy.adapters.election.UnrecordedEntityFacts
import com.dynokore.gst.tenancy.adapters.persistence.JooqAccessPointRouteStore
import com.dynokore.gst.tenancy.adapters.persistence.JooqScopePackStore
import com.dynokore.gst.tenancy.application.election.AccessPointRouteStore
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.ApproverSource
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.election.ElectionCommands
import com.dynokore.gst.tenancy.application.election.ElectionGate
import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.application.election.ElectionStatusQueries
import com.dynokore.gst.tenancy.application.election.EntityFactSource
import com.dynokore.gst.tenancy.application.election.ScopePackStore
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.ProfileExtras
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.configresolver.PolicyHistory
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18: the entity elections (INV-T-4927): the election store over config-resolver, the route and scope pack
 * replicas, the three operations, the status and the go-live gate, constructed here and nowhere else.
 */
@Configuration(proxyBeanMethods = false)
@Suppress("TooManyFunctions") // the composition root of one use-case area
class ElectionUseCases {
    companion object {
        /** Wraps the `PolicyWriter` bean in the [ElectionPolicyWriterGuard], so every holder of it gets the guard. */
        @JvmStatic
        @Bean
        fun electionPolicyWriterGuard(): BeanPostProcessor = ElectionPolicyWriterGuardPostProcessor()
    }

    @Bean
    fun electionPolicyStore(
        writer: PolicyWriter,
        history: PolicyHistory,
        tenants: TenantContext,
        mapper: ObjectMapper,
    ): ElectionPolicyStore = ConfigElectionPolicyStore(writer, history, tenants, mapper)

    @Bean
    fun profileExtras(elections: ElectionPolicyStore): ProfileExtras = IssuanceProfileExtras(elections)

    @Bean
    fun accessPointRouteStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): AccessPointRouteStore = JooqAccessPointRouteStore(dsl, tenants)

    @Bean
    fun scopePackStore(
        dsl: DSLContext,
        tenants: TenantContext,
        mapper: ObjectMapper,
    ): ScopePackStore = JooqScopePackStore(dsl, tenants, mapper)

    /** The entity facts answer NOT_RECORDED until INV-T-4933 binds the stored questionnaire answers (INV-Q-9696 B). */
    @Bean
    @ConditionalOnMissingBean(EntityFactSource::class)
    fun entityFactSource(): EntityFactSource = UnrecordedEntityFacts()

    @Bean
    @ConditionalOnMissingBean(CallerSource::class)
    fun callerSource(principals: EndUserPrincipalResolver): CallerSource = RequestCallerSource(principals)

    @Bean
    @ConditionalOnMissingBean(ActionAuthority::class)
    fun actionAuthority(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
    ): ActionAuthority = ReplicaActionAuthority(replica.getIfAvailable(), tenants)

    /** Nobody approves a change until the owner question INV-Q-10027 is answered; a first election is unaffected. */
    @Bean
    @ConditionalOnMissingBean(ApproverSource::class)
    fun approverSource(): ApproverSource = UnboundApprovers()

    @Bean
    @Suppress("LongParameterList")
    fun electionCommands(
        tx: TransactionRunner,
        structure: StructureStore,
        elections: ElectionPolicyStore,
        statuses: EntityStatusStore,
        publisher: EntityProfileChangePublisher,
        facts: EntityFactSource,
        routes: AccessPointRouteStore,
        scopePacks: ScopePackStore,
        authority: ActionAuthority,
        approvers: ApproverSource,
        caller: CallerSource,
    ): ElectionCommands =
        ElectionCommands(
            tx,
            structure,
            elections,
            statuses,
            publisher,
            facts,
            routes,
            scopePacks,
            authority,
            approvers,
            caller,
        )

    @Bean
    fun electionStatusQueries(
        tx: TransactionRunner,
        structure: StructureStore,
        elections: ElectionPolicyStore,
        facts: EntityFactSource,
    ): ElectionStatusQueries = ElectionStatusQueries(tx, structure, elections, facts)

    @Bean
    fun electionGate(queries: ElectionStatusQueries): ElectionGate = ElectionGate(queries)

    /**
     * Ruling INV-Q-10031: until the product binds an incident sink, a consumer that needs a rebuild is logged at ERROR
     * (topic and reason) and nothing else happens; the alert path is recorded in tooling/followups.md.
     */
    @Bean
    @ConditionalOnMissingBean(RebuildRequiredSink::class)
    fun rebuildRequiredSink(): RebuildRequiredSink = LoggingRebuildRequiredSink()

    /** One consumer per topic (service-runtime drives every InboxConsumer bean): the three election subscriptions. */
    @Bean
    fun accessPointRouteChangedConsumer(
        routes: AccessPointRouteStore,
        mapper: ObjectMapper,
        deps: ConsumerDependencies,
    ): InboxConsumer =
        deps.consumer(
            "tenancy-svc-access-point-route-changed",
            ConsumedElectionEvents.ACCESS_POINT_ROUTE_CHANGED_TOPIC,
            AccessPointRouteChangedHandler(routes, mapper),
        )

    @Bean
    fun rulePackStagedConsumer(
        packs: ScopePackStore,
        mapper: ObjectMapper,
        deps: ConsumerDependencies,
    ): InboxConsumer =
        deps.consumer(
            "tenancy-svc-rule-pack-staged",
            ConsumedElectionEvents.RULE_PACK_STAGED_TOPIC,
            RulePackStagedHandler(packs, mapper),
        )

    @Bean
    fun rulePackActivatedConsumer(
        packs: ScopePackStore,
        mapper: ObjectMapper,
        deps: ConsumerDependencies,
    ): InboxConsumer =
        deps.consumer(
            "tenancy-svc-rule-pack-activated",
            ConsumedElectionEvents.RULE_PACK_ACTIVATED_TOPIC,
            RulePackActivatedHandler(packs, mapper),
        )

    @Bean
    fun consumerDependencies(
        catalogue: ObjectProvider<EventCatalogue>,
        reader: ObjectProvider<EventLogReader>,
        scope: ObjectProvider<TenantScope>,
        rebuildRequired: ObjectProvider<RebuildRequiredSink>,
        tenants: ObjectProvider<TenantContext>,
        transactions: ObjectProvider<TransactionRunner>,
        clock: ObjectProvider<Clock>,
    ): ConsumerDependencies =
        ConsumerDependencies(catalogue, reader, scope, rebuildRequired, tenants, transactions, clock)
}

/** What a [JdbcInboxConsumer] needs from service-runtime, resolved when the first poll runs (not at start-up). */
class ConsumerDependencies(
    private val catalogue: ObjectProvider<EventCatalogue>,
    private val reader: ObjectProvider<EventLogReader>,
    private val scope: ObjectProvider<TenantScope>,
    private val rebuildRequired: ObjectProvider<RebuildRequiredSink>,
    private val tenants: ObjectProvider<TenantContext>,
    private val transactions: ObjectProvider<TransactionRunner>,
    private val clock: ObjectProvider<Clock>,
) {
    fun consumer(
        name: String,
        topic: String,
        handler: EventHandler,
    ): InboxConsumer = LazyInboxConsumer(name, topic, handler, this)

    internal fun build(
        name: String,
        topic: String,
        handler: EventHandler,
    ): JdbcInboxConsumer? {
        val tenantScope = scope.getIfAvailable()
        val eventLog = reader.getIfAvailable()
        if (tenantScope == null || eventLog == null) return null
        val subscription = ConsumerSubscription(name, topic, SCHEMA)
        return JdbcInboxConsumer(
            subscription,
            catalogue.getObject(),
            eventLog,
            tenantScope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), tenants.getObject(), transactions.getObject()),
            handler,
            rebuildRequired.getObject(),
            clock.getIfAvailable { Clock.systemUTC() },
        )
    }

    private companion object {
        const val SCHEMA = "tenancy"
    }
}

/** Builds its [JdbcInboxConsumer] at the first poll; reads nothing while the event log is not bound. */
class LazyInboxConsumer(
    private val name: String,
    private val topic: String,
    private val handler: EventHandler,
    private val deps: ConsumerDependencies,
) : InboxConsumer {
    private val delegate: JdbcInboxConsumer? by lazy { deps.build(name, topic, handler) }

    override fun pollOnce(): PollResult = delegate?.pollOnce() ?: PollResult(0, 0, 0, 0, 0)
}

/** Applies the [ElectionPolicyWriterGuard] to every bean of type [PolicyWriter] once (ruling INV-Q-10023). */
class ElectionPolicyWriterGuardPostProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is PolicyWriter && bean !is ElectionPolicyWriterGuard) ElectionPolicyWriterGuard(bean) else bean
}

/** The log-only [RebuildRequiredSink] (ruling INV-Q-10031): ERROR with the topic and the reason, nothing else. */
class LoggingRebuildRequiredSink : RebuildRequiredSink {
    override fun raise(incident: RebuildRequiredIncident) {
        LoggerFactory
            .getLogger(LoggingRebuildRequiredSink::class.java)
            .error(
                "rebuild required: topic {}: consumer {} next offset {} is at or below purge watermark {}",
                incident.topic,
                incident.consumer,
                incident.nextOffset,
                incident.watermark,
            )
    }
}
