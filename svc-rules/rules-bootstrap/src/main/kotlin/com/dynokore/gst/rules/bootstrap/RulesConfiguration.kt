package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.clients.AccessSvcClient
import com.dynokore.gst.rules.adapters.events.DryRunSampleEvents
import com.dynokore.gst.rules.adapters.events.DryRunSampleRecordedHandler
import com.dynokore.gst.rules.adapters.events.EventHistoricalSamplePort
import com.dynokore.gst.rules.adapters.events.OutboxPackEventEmitter
import com.dynokore.gst.rules.adapters.events.RulesEventCatalogue
import com.dynokore.gst.rules.adapters.packs.EngineVersion
import com.dynokore.gst.rules.adapters.packs.HolderCopySigningKeys
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
import com.dynokore.gst.rules.adapters.persistence.JooqSamplePageStore
import com.dynokore.gst.rules.adapters.persistence.PersistedPackRegistry
import com.dynokore.gst.rules.adapters.persistence.jooq.Rules
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.adapters.scope.PackScopeVerdictPort
import com.dynokore.gst.rules.adapters.scope.ProfileScopeFacts
import com.dynokore.gst.rules.adapters.scope.VoluntaryOverSubmission
import com.dynokore.gst.rules.adapters.web.BackgroundDryRunStore
import com.dynokore.gst.rules.adapters.web.BackgroundEvaluation
import com.dynokore.gst.rules.adapters.web.BackgroundSamplePort
import com.dynokore.gst.rules.adapters.web.DryRunCalls
import com.dynokore.gst.rules.adapters.web.MappingSpreadsheetImporter
import com.dynokore.gst.rules.adapters.web.MappingSpreadsheetImports
import com.dynokore.gst.rules.adapters.web.RegistryNamedPackVersions
import com.dynokore.gst.rules.application.ActivePackReader
import com.dynokore.gst.rules.application.ClassifyDocument
import com.dynokore.gst.rules.application.CodeCataloguePort
import com.dynokore.gst.rules.application.DocumentCoder
import com.dynokore.gst.rules.application.DryRunDeltaPort
import com.dynokore.gst.rules.application.DryRunDeltaStore
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.application.HistoricalSamplePort
import com.dynokore.gst.rules.application.HolderCopyPort
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
import com.dynokore.gst.rules.application.PackDocumentEvaluation
import com.dynokore.gst.rules.application.PackEvaluationPort
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.application.PeriodCalendarUnavailableException
import com.dynokore.gst.rules.application.RateSourcePort
import com.dynokore.gst.rules.application.RunDryRun
import com.dynokore.gst.rules.application.SampleRequestPort
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.scope.ScopeVerdictPort
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
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVerifier
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.evaluation.Evaluator
import com.dynokore.platform.serviceruntime.binding.DeploymentForm
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
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
import org.springframework.beans.factory.annotation.Value
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

/** The only place a use case is constructed; `application` stays annotation-free (ARC-03, TPL-18). */
@Configuration(proxyBeanMethods = false)
@Suppress("TooManyFunctions") // one bean method per wired port; the composition root is the single place (TPL-18)
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

    /**
     * The period calendar belongs to tenancy-svc and rules-svc computes no period (INV-T-4974, INV-REQ-1303). Until the
     * production adapter (INV-T-20226) replaces this binding, a `dryRun` without a body is refused 503 SRT-013.
     */
    @Bean
    @ConditionalOnMissingBean(LastCompletePeriodPort::class)
    fun lastCompletePeriodPort(): LastCompletePeriodPort =
        LastCompletePeriodPort { _, _ -> throw PeriodCalendarUnavailableException("no period calendar is configured") }

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
    ): DryRunDeltaStore = JooqDryRunDeltaStore(dsl, tenants)

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
     * 503 SRT-013.
     * The connections it takes count against the background lane of the service's one pool.
     */
    @Bean(destroyMethod = "shutdown")
    fun dryRunExecutor(
        @Value("\${rules.dry-run.parallelism}") parallelism: Int,
    ): ExecutorService =
        ThreadPoolExecutor(
            parallelism,
            parallelism,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(parallelism),
            { r -> Thread(r, "dry-run").also { it.isDaemon = true } },
        )

    /** The calls of `dryRun` and `getDryRun`; the sample wait comes from `rules.dry-run.sample-wait`. */
    @Bean
    @Suppress("LongParameterList") // the composition root names the collaborators of the calls (TPL-18)
    fun dryRunCalls(
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
        @Value("\${rules.dry-run.parallelism}") parallelism: Int,
        @Value("\${rules.dry-run.sample-wait}") sampleWait: String,
    ): DryRunCalls =
        DryRunCalls(
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
            Semaphore(parallelism),
            Duration.parse(sampleWait),
            SYNC_WAIT,
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

/** `dryRun` answers 202 when the run outlasts this (API-17, x-timeout 2000 ms of the contract). */
private val SYNC_WAIT: Duration = Duration.ofMillis(2000)
