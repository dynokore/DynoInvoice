package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.clients.AccessSvcClient
import com.dynokore.gst.rules.adapters.clients.HttpPackFeedProbe
import com.dynokore.gst.rules.adapters.events.DryRunSampleEvents
import com.dynokore.gst.rules.adapters.events.DryRunSampleRecordedHandler
import com.dynokore.gst.rules.adapters.events.EventHistoricalSamplePort
import com.dynokore.gst.rules.adapters.events.OutboxPackEventEmitter
import com.dynokore.gst.rules.adapters.events.PackReadinessAcknowledgedHandler
import com.dynokore.gst.rules.adapters.events.PackReadinessEvents
import com.dynokore.gst.rules.adapters.events.PeriodRecordsChangedEvents
import com.dynokore.gst.rules.adapters.events.PeriodRecordsChangedHandler
import com.dynokore.gst.rules.adapters.events.RulesEventCatalogue
import com.dynokore.gst.rules.adapters.events.StoredLastCompletePeriodPort
import com.dynokore.gst.rules.adapters.packs.ClasspathTrustAnchors
import com.dynokore.gst.rules.adapters.packs.DirectoryPackSource
import com.dynokore.gst.rules.adapters.packs.EngineVersion
import com.dynokore.gst.rules.adapters.packs.HolderCopySigningKeys
import com.dynokore.gst.rules.adapters.packs.PackContentsChecks
import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.adapters.packs.RefusingHolderCopyPort
import com.dynokore.gst.rules.adapters.packs.RefusingPackActionAuthority
import com.dynokore.gst.rules.adapters.packs.RefusingRateSourcePort
import com.dynokore.gst.rules.adapters.packs.ReplicaRateSourcePort
import com.dynokore.gst.rules.adapters.packs.ServiceKeyHolderCopyWriter
import com.dynokore.gst.rules.adapters.persistence.ArmedPackLookup
import com.dynokore.gst.rules.adapters.persistence.JooqActivePackReader
import com.dynokore.gst.rules.adapters.persistence.JooqDryRunDeltaPort
import com.dynokore.gst.rules.adapters.persistence.JooqDryRunDeltaStore
import com.dynokore.gst.rules.adapters.persistence.JooqMappingImportRecordStore
import com.dynokore.gst.rules.adapters.persistence.JooqMappingPackStore
import com.dynokore.gst.rules.adapters.persistence.JooqPackArmingStore
import com.dynokore.gst.rules.adapters.persistence.JooqPackCurrencyStore
import com.dynokore.gst.rules.adapters.persistence.JooqPackImportStore
import com.dynokore.gst.rules.adapters.persistence.JooqPeriodRecordsStore
import com.dynokore.gst.rules.adapters.persistence.JooqSamplePageStore
import com.dynokore.gst.rules.adapters.persistence.PersistedPackRegistry
import com.dynokore.gst.rules.adapters.persistence.PersistedRegulatoryPackStore
import com.dynokore.gst.rules.adapters.persistence.jooq.Rules
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.adapters.scope.PackScopeVerdictPort
import com.dynokore.gst.rules.adapters.scope.PolicySilenceLimit
import com.dynokore.gst.rules.adapters.scope.ProfileScopeFacts
import com.dynokore.gst.rules.adapters.scope.VoluntaryOverSubmission
import com.dynokore.gst.rules.adapters.web.BackgroundDryRunStore
import com.dynokore.gst.rules.adapters.web.BackgroundEvaluation
import com.dynokore.gst.rules.adapters.web.BackgroundSamplePort
import com.dynokore.gst.rules.adapters.web.CompileObserver
import com.dynokore.gst.rules.adapters.web.CompiledPackHold
import com.dynokore.gst.rules.adapters.web.CompilingArmingStore
import com.dynokore.gst.rules.adapters.web.DryRunCalls
import com.dynokore.gst.rules.adapters.web.MappingSpreadsheetImporter
import com.dynokore.gst.rules.adapters.web.MappingSpreadsheetImports
import com.dynokore.gst.rules.adapters.web.RegistryNamedPackVersions
import com.dynokore.gst.rules.adapters.web.ReviewPolicyValues
import com.dynokore.gst.rules.adapters.web.unavailable
import com.dynokore.gst.rules.application.AcknowledgePackDelta
import com.dynokore.gst.rules.application.AcknowledgementPermissionPort
import com.dynokore.gst.rules.application.ActivePackReader
import com.dynokore.gst.rules.application.ApprovalPermissionPort
import com.dynokore.gst.rules.application.ApproveRegulatoryPack
import com.dynokore.gst.rules.application.ArmRegulatoryPack
import com.dynokore.gst.rules.application.ArmingPermissionPort
import com.dynokore.gst.rules.application.ClassifyDocument
import com.dynokore.gst.rules.application.CodeCataloguePort
import com.dynokore.gst.rules.application.CompleteDeltaReader
import com.dynokore.gst.rules.application.DeltaAcknowledgement
import com.dynokore.gst.rules.application.DeltaAcknowledgementStore
import com.dynokore.gst.rules.application.DocumentCoder
import com.dynokore.gst.rules.application.DryRunDeltaPort
import com.dynokore.gst.rules.application.DryRunDeltaStore
import com.dynokore.gst.rules.application.DryRunPermissionPort
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.application.ExpectedInstances
import com.dynokore.gst.rules.application.HistoricalSamplePort
import com.dynokore.gst.rules.application.HolderCopyPort
import com.dynokore.gst.rules.application.ImportPermissionPort
import com.dynokore.gst.rules.application.ImportRegulatoryPack
import com.dynokore.gst.rules.application.InstallationApproverFactPort
import com.dynokore.gst.rules.application.LastCompletePeriodPort
import com.dynokore.gst.rules.application.ListActiveRegulatoryPacks
import com.dynokore.gst.rules.application.ListCursorKey
import com.dynokore.gst.rules.application.MappingAcknowledgementPort
import com.dynokore.gst.rules.application.MappingPackAuthoring
import com.dynokore.gst.rules.application.MappingPackCollaborators
import com.dynokore.gst.rules.application.NamedPackVersions
import com.dynokore.gst.rules.application.PackActionAuthority
import com.dynokore.gst.rules.application.PackApprovalDecisionPort
import com.dynokore.gst.rules.application.PackBundleNotFoundException
import com.dynokore.gst.rules.application.PackContentsCheck
import com.dynokore.gst.rules.application.PackCurrencyMonitor
import com.dynokore.gst.rules.application.PackCurrencyStore
import com.dynokore.gst.rules.application.PackDocumentEvaluation
import com.dynokore.gst.rules.application.PackEvaluationPort
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.application.PackFeedPoller
import com.dynokore.gst.rules.application.PackImportStore
import com.dynokore.gst.rules.application.RateSourcePort
import com.dynokore.gst.rules.application.RegulatoryPackStore
import com.dynokore.gst.rules.application.RunDryRun
import com.dynokore.gst.rules.application.RunningEngine
import com.dynokore.gst.rules.application.SampleRequestPort
import com.dynokore.gst.rules.application.SilenceLimitPort
import com.dynokore.gst.rules.application.TenantReviewPolicy
import com.dynokore.gst.rules.application.TenantReviewPolicyReader
import com.dynokore.gst.rules.application.TrustAnchorSource
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.scope.ScopeVerdictPort
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.crypto.DetachedSigner
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
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.productruntime.TrainFence
import com.dynokore.platform.rulepackkernel.PackContentValidator
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVerifier
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.evaluation.Evaluator
import com.dynokore.platform.serviceruntime.binding.DeploymentForm
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.binding.PackSource
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Schema
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.dynokore.gst.rules.domain.pack.DeploymentForm as DomainDeploymentForm

/** The only place a use case is constructed; `application` stays annotation-free (ARC-03, TPL-18). */
@Configuration(proxyBeanMethods = false)
@org.springframework.scheduling.annotation.EnableScheduling
// one bean method per wired port; the composition root is the single place (TPL-18)
@Suppress("TooManyFunctions", "LargeClass")
class RulesConfiguration {
    /** The generated schema, checked at start-up against the table classes (INV-Q-4815, INV-ADR-3968 item 4a). */
    @Bean
    fun rulesSchema(): Schema = Rules.RULES

    /**
     * The event types catalogued for rules; the emission guard refuses any other (INV-AC-1869). Besides the three pack
     * events, the two platform events the mounted setPolicy emits in its transaction: the audit entry and the policy
     * change (INV-T-18451), and the evidence artefact the mapping spreadsheet re-import records (INV-T-4973,
     * INV-REQ-833 b3); no other platform event is listed.
     */
    @Bean
    fun eventCatalogue(): EventCatalogue =
        EventCatalogue(
            RulesEventCatalogue.entries() +
                // the sample request of a dry run (INV-T-19903): published through the outbox of the command
                DryRunSampleEvents.requestedEntry() +
                listOf(
                    PlatformEventCatalogue.AUDIT_ENTRY_RECORDED,
                    PlatformEventCatalogue.POLICY_CHANGED,
                    PlatformEventCatalogue.EVIDENCE_ARTEFACT_RECORDED,
                ).map {
                    CatalogueEntry(
                        it.eventType,
                        checkNotNull(it.topic),
                        setOf(CatalogueEntry.EVERY_SERVICE),
                        contentTopic = false,
                    )
                },
        )

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

    /** Classification by function under the scope pack classification table in force on the date (INV-REQ-658). */
    @Bean
    fun classifyDocument(reader: PackContentsReader): ClassifyDocument = ClassifyDocument(reader)

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

    /** The stored pages of the dry-run samples (INV-T-19903); the pages are deleted when the run ends. */
    @Bean
    fun jooqSamplePageStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqSamplePageStore = JooqSamplePageStore(dsl, tenants)

    /** Publishes `PackDryRunSampleRequested` in the command transaction of `dryRun` (INV-Q-4995). */
    @Bean
    fun dryRunSampleEvents(writer: OutboxWriter): DryRunSampleEvents = DryRunSampleEvents(writer)

    /**
     * The sample comes from the pages canonical-svc published (INV-Q-4995, INV-Q-4870 B); a context that supplies its
     * own [HistoricalSamplePort] (the fixture adapter of the test composition) keeps that one.
     */
    @Bean
    @ConditionalOnMissingBean(HistoricalSamplePort::class)
    fun historicalSamplePort(
        store: JooqSamplePageStore,
        clock: Clock,
    ): HistoricalSamplePort = EventHistoricalSamplePort(store, clock)

    /** The period records tenancy-svc published, stored as the read model of the period calendar (INV-T-20226). */
    @Bean
    fun jooqPeriodRecordsStore(dsl: DSLContext): JooqPeriodRecordsStore = JooqPeriodRecordsStore(dsl)

    /**
     * The period calendar belongs to tenancy-svc and rules-svc computes no period (INV-T-20226, INV-REQ-1303): the last
     * complete period is read from the stored `PeriodRecordsChanged` records; an entity with no record is refused
     * 503 SRT-013. A context that supplies its own [LastCompletePeriodPort] (the fixture of the test composition) keeps
     * that one.
     */
    @Bean
    @ConditionalOnMissingBean(LastCompletePeriodPort::class)
    fun lastCompletePeriodPort(
        store: JooqPeriodRecordsStore,
        transactions: TransactionRunner,
        clock: Clock,
    ): LastCompletePeriodPort =
        // the call comes from the request thread with the tenant bound and no transaction open (ARC-21)
        StoredLastCompletePeriodPort({ tenant, entityRef ->
            transactions.inTransaction {
                store.spans(tenant, entityRef)
            }
        }, clock)

    /** The version named by a call is read from the registry whatever its status (tenant's own or the platform's). */
    @Bean
    @ConditionalOnMissingBean(NamedPackVersions::class)
    fun namedPackVersions(dsl: DSLContext): NamedPackVersions = RegistryNamedPackVersions(dsl)

    /** The pack evaluation of a dry run: [PackDocumentEvaluation] over the registry (INV-T-19901). */
    @Bean
    @ConditionalOnMissingBean(PackEvaluationPort::class)
    fun packEvaluationPort(
        named: NamedPackVersions,
        dsl: DSLContext,
    ): PackEvaluationPort =
        PackDocumentEvaluation(named, ArmedPackLookup(dsl), Evaluator(emptyList()), listOf(PackType(MAPPING_PACK_TYPE)))

    /** The stored delta of the dry runs (INV-T-19902). */
    @Bean
    fun dryRunDeltaStore(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): DryRunDeltaStore = JooqDryRunDeltaStore(dsl, tenants, clock)

    /**
     * The dry-run use case. Absent (null) when no [HistoricalSamplePort] exists, so that the service still starts and
     * `dryRun` is refused naming the unavailable sample (coordinator ruling INV-Q-4900). Every port of the run is its
     * own transaction on the background thread.
     */
    @Bean
    fun runDryRun(
        samples: ObjectProvider<HistoricalSamplePort>,
        evaluation: PackEvaluationPort,
        store: DryRunDeltaStore,
        transactions: TransactionRunner,
        clock: Clock,
    ): RunDryRun? =
        samples.ifAvailable?.let {
            RunDryRun(
                BackgroundSamplePort(it, transactions),
                BackgroundEvaluation(evaluation, transactions),
                BackgroundDryRunStore(store, transactions),
                clock,
            )
        }

    /**
     * The background capacity of dry runs: a fixed pool of `rules.dry-run.parallelism` platform threads whose queue
     * holds no more than the pool can start, so a run never waits unbounded; a call finding no free slot is refused
     * 503 SRT-013 with `Retry-After` (`rules.dry-run.retry-after`).
     * The connections it takes count against the background lane of the service's one pool.
     */
    @Bean
    fun dryRunExecutor(environment: Environment): ExecutorService {
        val parallelism = environment.getRequiredProperty(DRY_RUN_PARALLELISM, Int::class.java)
        return ThreadPoolExecutor(
            parallelism,
            parallelism,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(parallelism),
            { r -> Thread(r, "dry-run").also { it.isDaemon = true } },
        )
    }

    /** The calls of `dryRun` and `getDryRun`; the sample wait comes from `rules.dry-run.sample-wait`. */
    @Bean
    @Suppress("LongParameterList") // the composition root names the collaborators of the calls (TPL-18)
    fun dryRunCalls(
        permission: DryRunPermissionPort,
        runs: ObjectProvider<RunDryRun>,
        store: DryRunDeltaStore,
        requests: SampleRequestPort,
        periods: LastCompletePeriodPort,
        packs: JooqMappingPackStore,
        dsl: DSLContext,
        tenants: TenantContext,
        transactions: TransactionRunner,
        clock: Clock,
        executor: ExecutorService,
        environment: Environment,
    ): DryRunCalls =
        DryRunCalls(
            permission,
            runs,
            store,
            requests,
            periods,
            packs,
            dsl,
            tenants,
            transactions,
            clock,
            executor,
            Semaphore(environment.getRequiredProperty(DRY_RUN_PARALLELISM, Int::class.java)),
            Duration.parse(environment.getRequiredProperty(DRY_RUN_SAMPLE_WAIT)),
            SYNC_WAIT,
            Duration.parse(environment.getRequiredProperty(DRY_RUN_RETRY_AFTER)),
        )

    /**
     * rules-svc is the subscriber of `gst.platform.pack-dry-run-sample-recorded.v1` (platform-contracts
     * event-log/topics.yaml): the handler stores each page in the consumer's transaction, with its own catalogue like
     * the entity-profile consumer above.
     */
    @Bean
    @Suppress("LongParameterList") // the consumer's collaborators are all injected platform beans
    fun dryRunSampleConsumer(
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        store: JooqSamplePageStore,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(RulesEventCatalogue.SERVICE, DryRunSampleEvents.RECORDED_TOPIC, SCHEMA),
            EventCatalogue(listOf(DryRunSampleEvents.recordedEntry())),
            reader,
            scope,
            dsl,
            DryRunSampleRecordedHandler(store, clock),
            rebuildRequired,
            clock,
        )

    /**
     * rules-svc is the subscriber of `gst.tenancy.period-records-changed.v1` (platform-contracts topics.yaml):
     * the handler replaces the period records of the entity in the consumer's transaction and sends nothing.
     */
    @Bean
    @Suppress("LongParameterList") // the consumer's collaborators are all injected platform beans
    fun periodRecordsConsumer(
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        store: JooqPeriodRecordsStore,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(RulesEventCatalogue.SERVICE, PeriodRecordsChangedEvents.TOPIC, SCHEMA),
            EventCatalogue(listOf(PeriodRecordsChangedEvents.entry())),
            reader,
            scope,
            dsl,
            PeriodRecordsChangedHandler(store, clock),
            rebuildRequired,
            clock,
        )

    /** The arming records: the readiness answers, the acknowledgements, the review rows and the arming write. */
    @Bean
    fun jooqPackArmingStore(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
        emitter: PackEventEmitter,
    ): JooqPackArmingStore = JooqPackArmingStore(dsl, platform, transactions, emitter)

    /**
     * rules-svc is the subscriber of `gst.platform.pack-readiness-acknowledged.v1` (platform-contracts
     * event-log/topics.yaml): the handler stores each instance's answer in the consumer's transaction.
     */
    @Bean
    @Suppress("LongParameterList") // the consumer's collaborators are all injected platform beans
    fun packReadinessConsumer(
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        store: JooqPackArmingStore,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(RulesEventCatalogue.SERVICE, PackReadinessEvents.ACKNOWLEDGED_TOPIC, SCHEMA),
            EventCatalogue(listOf(PackReadinessEvents.acknowledgedEntry())),
            reader,
            scope,
            dsl,
            PackReadinessAcknowledgedHandler(store),
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

    // ---- the approval of a STAGED regulatory pack (INV-T-4975; INV-REQ-489) ----

    @Bean
    fun regulatoryPackStore(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
    ): RegulatoryPackStore = PersistedRegulatoryPackStore(dsl, platform, transactions)

    /**
     * The scoped approval permission `regulatory-pack.approve`, decided by `AuthorityReplica.decideTenant` for the
     * caller's tenant (INV-Q-10027): never a role name. A decision that is not answered from the local replica, and a
     * context with no replica, is the 503 SRT-013 of the contract: nothing is read or changed.
     */
    @Bean
    fun approvalPermissionPort(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
    ): ApprovalPermissionPort =
        ApprovalPermissionPort { approver ->
            val decision =
                replica.ifAvailable?.decideTenant(
                    tenants.current(),
                    Principal(approver, PrincipalKind.HUMAN),
                    REGULATORY_PACK_APPROVE,
                ) ?: throw unavailable("the permission decision is not available")
            if (decision.source != DecisionSource.LOCAL) throw unavailable("the permission decision is not available")
            decision.allowed
        }

    /**
     * The scoped permission `pack.dry-run` of `dryRun`, decided by `AuthorityReplica.decideTenant` for the caller's
     * tenant (INV-Q-10027): never a role name. A decision that is not answered from the local replica, and a context
     * with no replica, is the 503 SRT-013 of the contract.
     */
    @Bean
    fun dryRunPermissionPort(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
    ): DryRunPermissionPort =
        DryRunPermissionPort { user ->
            val decision =
                replica.ifAvailable?.decideTenant(
                    tenants.current(),
                    Principal(user, PrincipalKind.HUMAN),
                    PACK_DRY_RUN,
                ) ?: throw unavailable("the permission decision is not available")
            if (decision.source != DecisionSource.LOCAL) throw unavailable("the permission decision is not available")
            decision.allowed
        }

    @Bean
    fun approveRegulatoryPack(
        store: RegulatoryPackStore,
        permission: ApprovalPermissionPort,
    ): ApproveRegulatoryPack = ApproveRegulatoryPack(store, permission)

    // ---- the arming fence, the grace window and the acknowledgement of a delta (INV-T-4977) ----

    /**
     * The scoped arming permission `regulatory-pack.arm`, decided by `AuthorityReplica.decideTenant` for the caller's
     * tenant (INV-Q-10027): never a role name, and the code reads no deployment form to choose a key. A decision that
     * is not answered from the local replica, and a context with no replica, is the 503 SRT-013 of the contract.
     */
    @Bean
    fun armingPermissionPort(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
    ): ArmingPermissionPort =
        ArmingPermissionPort { user -> decideTenantKey(replica, tenants, user, REGULATORY_PACK_ARM) }

    /** The scoped key `regulatory-pack.acknowledge`, decided like [armingPermissionPort]. */
    @Bean
    fun acknowledgementPermissionPort(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
    ): AcknowledgementPermissionPort =
        AcknowledgementPermissionPort { user -> decideTenantKey(replica, tenants, user, REGULATORY_PACK_ACKNOWLEDGE) }

    private fun decideTenantKey(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
        user: String,
        key: String,
    ): Boolean {
        val decision =
            replica.ifAvailable?.decideTenant(tenants.current(), Principal(user, PrincipalKind.HUMAN), key)
                ?: throw unavailable("the permission decision is not available")
        if (decision.source != DecisionSource.LOCAL) throw unavailable("the permission decision is not available")
        return decision.allowed
    }

    /**
     * VP-39 and VP-57 at the tenant's scope and VP-79 at installation scope, read through `PolicyReplica` (no tenant
     * binding is needed: the scope names the tenant). An absent value is the catalogue default (VP-39 review in the
     * grace window, VP-57 the platform maximum, VP-79 14 days); a value this reader does not recognise is the 503
     * SRT-013, never a guess.
     */
    @Bean
    fun tenantReviewPolicyReader(
        policy: PolicyReplica,
        gst: GstProperties,
        clock: Clock,
    ): TenantReviewPolicyReader =
        TenantReviewPolicyReader { tenantId ->
            val today = LocalDate.now(clock)
            val scope = PolicyScope.tenant(TenantId.of(tenantId))
            val review = policy.valueAt(scope, VP_REVIEW_POLICY, today)?.value
            val stored = policy.valueAt(scope, VP_GRACE_WINDOW, today)?.value
            val maximum = policy.valueAt(PolicyScope.installation(), VP_GRACE_MAXIMUM, today)?.value
            TenantReviewPolicy(
                form =
                    if (gst.form ==
                        DeploymentForm.PRIVATE
                    ) {
                        DomainDeploymentForm.PRIVATE
                    } else {
                        DomainDeploymentForm.SAAS
                    },
                policy = ReviewPolicyValues.reviewPolicy(review),
                stored = ReviewPolicyValues.storedDays(stored),
                maximum = ReviewPolicyValues.maximumDays(maximum),
            )
        }

    /** The expected instances of the fence: rules-svc and every pack holder (a missing holder never arms). */
    @Bean
    fun expectedInstances(): ExpectedInstances =
        ExpectedInstances { TrainFence.participants(TrainFence.PACK_HOLDERS + TrainFence.ARMER) }

    /**
     * The window of the fence: `rules.fence.acknowledgement-window`, ISO-8601, required, no default (12-factor III).
     * An absent, blank or unparsable value stops the start of the context and names the key.
     */
    @Bean
    fun fenceAcknowledgementWindow(environment: Environment): FenceAcknowledgementWindow {
        val text =
            environment.getProperty(FENCE_WINDOW)?.takeIf { it.isNotBlank() }
                ?: error("$FENCE_WINDOW is required (ISO-8601 duration)")
        return FenceAcknowledgementWindow(
            runCatching { Duration.parse(text) }
                .getOrElse { throw IllegalStateException("$FENCE_WINDOW is not an ISO-8601 duration: $text", it) },
        )
    }

    @Bean
    @Suppress("LongParameterList") // the collaborators of the use case, named once in the composition root (TPL-18)
    fun armRegulatoryPack(
        permission: ArmingPermissionPort,
        store: JooqPackArmingStore,
        compiling: CompilingArmingStore,
        policies: TenantReviewPolicyReader,
        expected: ExpectedInstances,
        window: FenceAcknowledgementWindow,
        clock: Clock,
    ): ArmRegulatoryPack =
        ArmRegulatoryPack(
            permission,
            store,
            tenantDeltas(store),
            policies,
            store,
            expected,
            compiling,
            clock,
            window.value,
        )

    /** The platform's own dry run is the approval gate, not a tenant delta: no tenant-level policy exists for it. */
    private fun tenantDeltas(store: JooqPackArmingStore): CompleteDeltaReader =
        object : CompleteDeltaReader by store {
            override fun tenantsWithCompleteDelta(
                packType: String,
                version: String,
            ) = store.tenantsWithCompleteDelta(packType, version).filter { !TenantId.of(it.tenantId).isPlatform }
        }

    /** The stylesheets of the armed Schematron packs, compiled once at activation and held in memory (INV-REQ-776). */
    @Bean
    fun compiledPackHold(): CompiledPackHold = CompiledPackHold()

    /** Observes nothing in production; a test supplies its own bean to count the compilations. */
    @Bean
    @ConditionalOnMissingBean(CompileObserver::class)
    fun schematronCompileObserver(): CompileObserver = CompileObserver { }

    /**
     * rules-svc is itself a fence participant: before the arming write it compiles the pack's stylesheets once
     * (INV-REQ-776, INV-AC-7964); a pack that does not compile fails the arming, so the fence abandons it (RUL-005).
     */
    @Bean
    @Suppress("LongParameterList") // the collaborators of the decorator, named once in the composition root (TPL-18)
    fun compilingArmingStore(
        store: JooqPackArmingStore,
        registry: PersistedPackRegistry,
        observer: CompileObserver,
        hold: CompiledPackHold,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
    ): CompilingArmingStore = CompilingArmingStore(store, registry, observer, hold, platform, transactions)

    @Bean
    fun acknowledgePackDelta(
        permission: AcknowledgementPermissionPort,
        store: JooqPackArmingStore,
        transactions: TransactionRunner,
        policies: TenantReviewPolicyReader,
        clock: Clock,
    ): AcknowledgePackDelta {
        // The tenant-bound reads and the write run in their own transaction each; the registry read opens its own
        // platform-bound one, so the use case itself is called outside any transaction.
        val deltas =
            object : CompleteDeltaReader by store {
                override fun completedAt(
                    tenantId: String,
                    packType: String,
                    version: String,
                ) = transactions.inTransaction { store.completedAt(tenantId, packType, version) }
            }
        val acknowledgements =
            object : DeltaAcknowledgementStore {
                override fun recordOnce(acknowledgement: DeltaAcknowledgement) =
                    transactions.inTransaction { store.recordOnce(acknowledgement) }

                override fun find(
                    tenantId: String,
                    packType: String,
                    version: String,
                ) = transactions.inTransaction { store.find(tenantId, packType, version) }
            }
        return AcknowledgePackDelta(permission, store, deltas, policies, acknowledgements, clock)
    }

    // ---- the signed pack import and DEP-001 pack currency (INV-T-4976; INV-REQ-1078, INV-REQ-2468) ----

    /** The mounted read-only directory of the bundles; without the property no bundle is found (fail closed). */
    @Bean
    @ConditionalOnMissingBean(PackSource::class)
    fun packSource(environment: Environment): PackSource =
        environment.getProperty(PACK_SOURCE_DIRECTORY)?.takeIf { it.isNotBlank() }?.let {
            DirectoryPackSource(
                java.nio.file.Path
                    .of(it),
            )
        } ?: PackSource { throw PackBundleNotFoundException(it.value) }

    @Bean
    @ConditionalOnMissingBean(TrustAnchorSource::class)
    fun trustAnchorSource(): TrustAnchorSource = ClasspathTrustAnchors()

    @Bean
    fun packImportStore(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
        registry: PersistedPackRegistry,
        emitter: PackEventEmitter,
        clock: Clock,
    ): PackImportStore = JooqPackImportStore(dsl, platform, transactions, registry, emitter, clock)

    @Bean
    fun packCurrencyStore(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
        emitter: PackEventEmitter,
    ): PackCurrencyStore = JooqPackCurrencyStore(dsl, platform, transactions, emitter)

    /** VP-72, read through `PolicyReplica` at installation scope of the platform tenant; written by `setPolicy`. */
    @Bean
    fun silenceLimitPort(
        policy: PolicyReplica,
        clock: Clock,
    ): SilenceLimitPort = PolicySilenceLimit(policy, clock)

    @Bean
    fun packCurrencyMonitor(
        store: PackCurrencyStore,
        limit: SilenceLimitPort,
        clock: Clock,
    ): PackCurrencyMonitor = PackCurrencyMonitor(store, limit, clock)

    /**
     * The feed client: polls `rules.pack-feed.url` every `rules.pack-feed.poll-interval` (ISO-8601, default PT1H); with
     * no URL there is no client (a null bean) and the installation takes bundles by import only.
     */
    @Bean
    fun packFeedPoller(
        environment: Environment,
        monitor: PackCurrencyMonitor,
        clock: Clock,
    ): PackFeedPoller? =
        environment.getProperty(PACK_FEED_URL)?.let { url ->
            PackFeedPoller(
                HttpPackFeedProbe(url),
                monitor,
                clock,
                Duration.parse(environment.getProperty("rules.pack-feed.poll-interval", "PT1H")),
            )
        }

    @Bean
    fun packCurrencyScheduling(
        poller: ObjectProvider<PackFeedPoller>,
        monitor: PackCurrencyMonitor,
    ): PackCurrencyScheduling = PackCurrencyScheduling(poller.ifAvailable, monitor)

    /**
     * The scoped permission `regulatory-pack.import-verify`, decided by `AuthorityReplica.decideTenant` for the
     * caller's tenant (INV-Q-10027): never a role name. A decision not answered from the local replica is 503 SRT-013.
     */
    @Bean
    fun importPermissionPort(
        replica: ObjectProvider<AuthorityReplica>,
        tenants: TenantContext,
    ): ImportPermissionPort =
        ImportPermissionPort { user ->
            val decision =
                replica.ifAvailable?.decideTenant(
                    tenants.current(),
                    Principal(user, PrincipalKind.HUMAN),
                    REGULATORY_PACK_IMPORT_VERIFY,
                ) ?: throw unavailable("the permission decision is not available")
            if (decision.source != DecisionSource.LOCAL) throw unavailable("the permission decision is not available")
            decision.allowed
        }

    @Bean
    @Suppress("LongParameterList") // the collaborators of the use case, named once in the composition root (TPL-18)
    fun importRegulatoryPack(
        source: PackSource,
        anchors: TrustAnchorSource,
        verifier: PackVerifier,
        validator: PackContentValidator,
        engine: EngineVersion,
        permission: ImportPermissionPort,
        store: PackImportStore,
        currency: PackCurrencyMonitor,
        contentsCheck: PackContentsCheck,
    ): ImportRegulatoryPack =
        ImportRegulatoryPack(
            source,
            anchors,
            verifier,
            validator,
            RunningEngine(engine::current),
            permission,
            store,
            currency,
            contentsCheck,
        )

    /** The pack-type checks JSON Schema cannot express, applied at the import (INV-T-6567). */
    @Bean
    fun packContentsCheck(): PackContentsCheck = PackContentsChecks()

    private companion object {
        const val PACK_FEED_URL = "rules.pack-feed.url"
        const val PACK_SOURCE_DIRECTORY = "rules.pack-source.directory"
        const val REGULATORY_PACK_IMPORT_VERIFY = "regulatory-pack.import-verify"
        const val REGULATORY_PACK_APPROVE = "regulatory-pack.approve"
        const val REGULATORY_PACK_ARM = "regulatory-pack.arm"
        const val REGULATORY_PACK_ACKNOWLEDGE = "regulatory-pack.acknowledge"
        const val VP_REVIEW_POLICY = "VP-39"
        const val VP_GRACE_WINDOW = "VP-57"
        const val VP_GRACE_MAXIMUM = "VP-79"
        const val FENCE_WINDOW = "rules.fence.acknowledgement-window"
        const val PACK_DRY_RUN = "pack.dry-run"
        const val CURSOR_KEY_SECRET = "rules/active-packs-cursor-key"
        const val ACCESS_URL_PROPERTY = "rules.access-svc-url"
        const val SCHEMA = "rules"
    }

    // ---- mapping pack authoring and sign-off (INV-T-4972; INV-Q-14646 to INV-Q-14652) ----

    /** Holds no permission until the adapter over `AuthorityReplica.decide` lands (INV-Q-14652 A). */
    @Bean
    @ConditionalOnMissingBean(PackActionAuthority::class)
    fun packActionAuthority(): PackActionAuthority = RefusingPackActionAuthority()

    /**
     * The one client of access-svc over the edge `rules-svc->access-svc` (INV-T-10700). The three port beans below
     * delegate to it, so each implements one port only and a replacement of one stays the only bean of its type. The
     * base URL is the required property `rules.access-svc-url` with no default (TPL-16, twelve-factor config): the
     * context does not start without it or with a blank value.
     */
    @Bean
    fun accessSvcEdge(
        @Qualifier("internalRestClientBuilder") builder: RestClient.Builder,
        registry: ResilienceRegistry,
        clock: Clock,
        environment: Environment,
    ): AccessSvcEdge =
        environment.getRequiredProperty(ACCESS_URL_PROPERTY).let { url ->
            require(url.isNotBlank()) { "$ACCESS_URL_PROPERTY must not be blank" }
            AccessSvcEdge(AccessSvcClient(builder, registry, clock, url))
        }

    @Bean
    @ConditionalOnMissingBean(PackApprovalDecisionPort::class)
    fun packApprovalDecisionPort(edge: AccessSvcEdge): PackApprovalDecisionPort =
        PackApprovalDecisionPort { edge.client.decide(it) }

    /**
     * The rate-source block over the structure replica and the SCOPE pack in force (INV-T-16546; INV-Q-14651 A,
     * INV-Q-16533 A). Without a [StructureReplica] bean the refusing class answers null (INV-T-4972).
     */
    @Bean
    @ConditionalOnMissingBean(RateSourcePort::class)
    fun rateSourcePort(
        structure: ObjectProvider<StructureReplica>,
        dsl: DSLContext,
        registry: PersistedPackRegistry,
    ): RateSourcePort {
        val replica = structure.ifAvailable ?: return RefusingRateSourcePort()
        val lookup = ArmedPackLookup(dsl)
        return ReplicaRateSourcePort(replica) { tenant, asOf ->
            lookup.resolve(PackTypes.SCOPE, tenant, asOf)?.let { registry.artefactOf(it)?.contents }
        }
    }

    /**
     * The holder copy is signed with the service key of rules-svc (INV-T-16544; INV-Q-14650 A, INV-Q-16531 A) and
     * registered through the tenant-bound registry. Declared before [refusingHolderCopyPort] so that the refusing
     * class is the bean only when no other [HolderCopyPort] exists.
     */
    @Bean
    @ConditionalOnMissingBean(HolderCopyPort::class)
    fun holderCopyPort(
        secrets: SecretStore,
        signer: DetachedSigner,
        verifier: PackVerifier,
        dsl: DSLContext,
        tenants: TenantContext,
    ): HolderCopyPort =
        ServiceKeyHolderCopyWriter(
            HolderCopySigningKeys(secrets),
            signer,
            verifier,
            PersistedPackRegistry(dsl, tenants),
            canonical = { text -> jsonbText(dsl, text) },
        )

    /** The text PostgreSQL returns for a JSON text: the form the `jsonb` columns of `pack_artefact` read back in. */
    private fun jsonbText(
        dsl: DSLContext,
        text: String,
    ): String =
        checkNotNull(dsl.select(DSL.cast(DSL.`val`(text), SQLDataType.JSONB)).fetchOne(0, JSONB::class.java)).data()

    /** Answers Unavailable; the bean only when no other [HolderCopyPort] exists (fail closed, INV-Q-14647 A). */
    @Bean
    @ConditionalOnMissingBean(HolderCopyPort::class)
    fun refusingHolderCopyPort(): HolderCopyPort = RefusingHolderCopyPort()

    @Bean
    @ConditionalOnMissingBean(InstallationApproverFactPort::class)
    fun installationApproverFactPort(edge: AccessSvcEdge): InstallationApproverFactPort =
        InstallationApproverFactPort { edge.client.read() }

    @Bean
    @ConditionalOnMissingBean(MappingAcknowledgementPort::class)
    fun mappingAcknowledgementPort(edge: AccessSvcEdge): MappingAcknowledgementPort =
        MappingAcknowledgementPort { version, client -> edge.client.read(version, client) }

    @Bean
    fun mappingPackStore(
        dsl: DSLContext,
        tenants: TenantContext,
        engineVersion: EngineVersion,
        clock: Clock,
    ): JooqMappingPackStore = JooqMappingPackStore(dsl, tenants, engineVersion, clock)

    /** The build's own version from the `BuildProperties` that `buildInfo()` generates (absent in a bare context). */
    @Bean
    fun engineVersion(build: ObjectProvider<BuildProperties>): EngineVersion = EngineVersion(build.ifAvailable)

    @Bean
    fun dryRunDeltaPort(
        dsl: DSLContext,
        tenants: TenantContext,
    ): DryRunDeltaPort = JooqDryRunDeltaPort(dsl, tenants)

    /** The code catalogue is the pack INV-T-4966 decodes: the reader resolves the ARMED version by date. */
    @Suppress("LongParameterList") // the composition root names the collaborators of the use case (TPL-18)
    @Bean
    fun mappingPackAuthoring(
        store: JooqMappingPackStore,
        authority: PackActionAuthority,
        deltas: DryRunDeltaPort,
        decisions: PackApprovalDecisionPort,
        catalogues: PackContentsReader,
        rateSources: RateSourcePort,
        holderCopies: HolderCopyPort,
        installationFacts: InstallationApproverFactPort,
        acknowledgements: MappingAcknowledgementPort,
        gst: GstProperties,
        clock: Clock,
    ): MappingPackAuthoring =
        MappingPackAuthoring(
            MappingPackCollaborators(
                store,
                authority,
                deltas,
                decisions,
                catalogues,
                rateSources,
                holderCopies,
                installationFacts,
                acknowledgements,
                gst.form == DeploymentForm.PRIVATE,
                catalogues,
            ),
            clock,
        )

    // ---- the mapping spreadsheet round trip (INV-T-4973) ----

    @Bean
    fun mappingImportRecordStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqMappingImportRecordStore = JooqMappingImportRecordStore(dsl, tenants)

    /** The re-import: the IRAS catalogue is the pack INV-T-4966 decodes, the record leaves as an evidence artefact. */
    @Suppress("LongParameterList") // the composition root names the seven collaborators of the use case (TPL-18)
    @Bean
    fun mappingSpreadsheetImports(
        packs: JooqMappingPackStore,
        records: JooqMappingImportRecordStore,
        authority: PackActionAuthority,
        catalogues: CodeCataloguePort,
        evidence: EvidenceArtefactRecorder,
        tenants: TenantContext,
        clock: Clock,
    ): MappingSpreadsheetImports =
        MappingSpreadsheetImporter(packs, records, authority, catalogues, evidence, tenants, clock)
}

/** The one access-svc client; it is no port itself, so a replaced port stays the only bean of its type. */
class AccessSvcEdge(
    val client: AccessSvcClient,
)

private const val MAPPING_PACK_TYPE = "MAPPING"
private const val DRY_RUN_PARALLELISM = "rules.dry-run.parallelism"
private const val DRY_RUN_SAMPLE_WAIT = "rules.dry-run.sample-wait"
private const val DRY_RUN_RETRY_AFTER = "rules.dry-run.retry-after"

/** `dryRun` answers 202 when the run outlasts this (API-17, x-timeout 2000 ms of the contract). */
private val SYNC_WAIT: Duration = Duration.ofMillis(2000)

/** The fence's acknowledgement window, from `rules.fence.acknowledgement-window` (typed, never a bare Duration). */
data class FenceAcknowledgementWindow(
    val value: Duration,
)
