package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.AccessPointRouteChangedHandler
import com.dynokore.gst.tenancy.adapters.election.AuditLogElectionAudit
import com.dynokore.gst.tenancy.adapters.election.ConfigElectionPolicyStore
import com.dynokore.gst.tenancy.adapters.election.ConsumedElectionEvents
import com.dynokore.gst.tenancy.adapters.election.ElectionPolicyWriterGuard
import com.dynokore.gst.tenancy.adapters.election.GstSchemeEventDecorator
import com.dynokore.gst.tenancy.adapters.election.GstSchemeProfileExtras
import com.dynokore.gst.tenancy.adapters.election.IssuanceProfileExtras
import com.dynokore.gst.tenancy.adapters.election.ReplicaActionAuthority
import com.dynokore.gst.tenancy.adapters.election.RequestCallerSource
import com.dynokore.gst.tenancy.adapters.election.RulePackActivatedHandler
import com.dynokore.gst.tenancy.adapters.election.RulePackStagedHandler
import com.dynokore.gst.tenancy.adapters.election.SchemeEventDependencies
import com.dynokore.gst.tenancy.adapters.election.UnrecordedEntityFacts
import com.dynokore.gst.tenancy.adapters.persistence.JooqAccessPointRouteStore
import com.dynokore.gst.tenancy.adapters.persistence.JooqElectionChangeStore
import com.dynokore.gst.tenancy.adapters.persistence.JooqExemptPurchaseElectionStore
import com.dynokore.gst.tenancy.adapters.persistence.JooqScopePackStore
import com.dynokore.gst.tenancy.application.election.AccessPointRouteStore
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.election.ElectionAudit
import com.dynokore.gst.tenancy.application.election.ElectionChangeStore
import com.dynokore.gst.tenancy.application.election.ElectionCommands
import com.dynokore.gst.tenancy.application.election.ElectionGate
import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.application.election.ElectionStatusQueries
import com.dynokore.gst.tenancy.application.election.EntityFactSource
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseElectionStore
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseElections
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseProfileExtras
import com.dynokore.gst.tenancy.application.election.ScopePackStore
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.ProfileExtras
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.platform.auditlog.AuditWriter
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
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
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
        fun electionPolicyWriterGuard(): ElectionPolicyWriterGuardPostProcessor =
            ElectionPolicyWriterGuardPostProcessor()

        /** Wraps the `PolicyWriter` bean in the [GstSchemeEventDecorator] (VP-53 events, ruling INV-Q-12262). */
        @JvmStatic
        @Bean
        fun gstSchemeEventDecorator(beans: ObjectProvider<SchemeEventBeans>): GstSchemeEventDecoratorPostProcessor =
            GstSchemeEventDecoratorPostProcessor(beans)
    }

    @Bean
    fun electionPolicyStore(
        writer: PolicyWriter,
        history: PolicyHistory,
        tenants: TenantContext,
        mapper: ObjectMapper,
    ): ElectionPolicyStore = ConfigElectionPolicyStore(writer, history, tenants, mapper)

    @Bean
    fun profileExtras(
        elections: ElectionPolicyStore,
        history: PolicyHistory,
        tenants: TenantContext,
        exemptPurchases: ExemptPurchaseElectionStore,
    ): ProfileExtras {
        val issuance = IssuanceProfileExtras(elections)
        val schemes = GstSchemeProfileExtras(history, tenants)
        val exempt = ExemptPurchaseProfileExtras(exemptPurchases)
        return ProfileExtras { entityRef ->
            issuance.entries(entityRef) + schemes.entries(entityRef) + exempt.entries(entityRef)
        }
    }

    @Bean
    fun exemptPurchaseElectionStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): ExemptPurchaseElectionStore = JooqExemptPurchaseElectionStore(dsl, tenants)

    @Bean
    @Suppress("LongParameterList") // the ports of the one operation
    fun exemptPurchaseElections(
        tx: TransactionRunner,
        structure: StructureStore,
        store: ExemptPurchaseElectionStore,
        authority: ActionAuthority,
        caller: CallerSource,
        audit: ElectionAudit,
        tenants: TenantContext,
        clock: ObjectProvider<Clock>,
    ): ExemptPurchaseElections =
        ExemptPurchaseElections(
            tx,
            structure,
            store,
            authority,
            caller,
            audit,
            tenants,
            clock.getIfAvailable(Clock::systemUTC),
        )

    /** The collaborators of the scheme decorator, resolved at the first VP-53 write. */
    @Bean
    @Suppress("LongParameterList") // one provider per collaborator
    fun schemeEventBeans(
        history: ObjectProvider<PolicyHistory>,
        publisher: ObjectProvider<EntityProfileChangePublisher>,
        structure: ObjectProvider<StructureStore>,
        statuses: ObjectProvider<EntityStatusStore>,
        extras: ObjectProvider<ProfileExtras>,
        tenants: ObjectProvider<TenantContext>,
    ): SchemeEventBeans = SchemeEventBeans(history, publisher, structure, statuses, extras, tenants)

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

    @Bean
    fun electionChangeStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): ElectionChangeStore = JooqElectionChangeStore(dsl, tenants)

    @Bean
    fun electionAudit(
        writer: AuditWriter,
        tenants: TenantContext,
    ): ElectionAudit = AuditLogElectionAudit(writer, tenants)

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
        caller: CallerSource,
        changes: ElectionChangeStore,
        audit: ElectionAudit,
        tenants: TenantContext,
        exemptPurchase: ExemptPurchaseElections,
        exemptPurchases: ExemptPurchaseElectionStore,
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
            caller,
            changes,
            audit,
            tenants,
            exemptPurchase,
            exemptPurchases,
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
    @Suppress("LongParameterList") // one provider per service-runtime collaborator
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
@Suppress("LongParameterList") // one provider per service-runtime collaborator
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
class ElectionPolicyWriterGuardPostProcessor :
    BeanPostProcessor,
    Ordered {
    /** Runs after the scheme decorator, so the guard is the outermost writer and refuses before anything else. */
    override fun getOrder(): Int = 2

    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is PolicyWriter && bean !is ElectionPolicyWriterGuard) ElectionPolicyWriterGuard(bean) else bean
}

/** Providers of what [GstSchemeEventDecorator] needs; each is resolved when the first VP-53 write runs. */
class SchemeEventBeans(
    private val history: ObjectProvider<PolicyHistory>,
    private val publisher: ObjectProvider<EntityProfileChangePublisher>,
    private val structure: ObjectProvider<StructureStore>,
    private val statuses: ObjectProvider<EntityStatusStore>,
    private val extras: ObjectProvider<ProfileExtras>,
    private val tenants: ObjectProvider<TenantContext>,
) {
    fun resolve(): SchemeEventDependencies =
        SchemeEventDependencies(
            history.getObject(),
            publisher.getObject(),
            structure.getObject(),
            statuses.getObject(),
            extras.getObject(),
            tenants.getObject(),
        )
}

/** Applies the [GstSchemeEventDecorator] to every bean of type [PolicyWriter] once; the guard stays in the chain. */
class GstSchemeEventDecoratorPostProcessor(
    private val beans: ObjectProvider<SchemeEventBeans>,
) : BeanPostProcessor,
    Ordered {
    override fun getOrder(): Int = 1

    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any =
        if (bean is PolicyWriter && bean !is GstSchemeEventDecorator) {
            GstSchemeEventDecorator(bean) { beans.getObject().resolve() }
        } else {
            bean
        }
}
