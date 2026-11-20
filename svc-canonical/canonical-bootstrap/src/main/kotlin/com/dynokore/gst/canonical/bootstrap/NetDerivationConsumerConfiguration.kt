package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.events.CapturingNetDerivedVersions
import com.dynokore.gst.canonical.adapters.events.JacksonNetDerivedVersions
import com.dynokore.gst.canonical.adapters.events.NetDerivationRequestedReader
import com.dynokore.gst.canonical.adapters.events.OutboxNetDerivationRefusedPublisher
import com.dynokore.gst.canonical.adapters.persistence.JooqNetDerivationDocuments
import com.dynokore.gst.canonical.adapters.persistence.JooqNetDerivationRecords
import com.dynokore.gst.canonical.adapters.persistence.JooqProcessedNetDerivationRequests
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.NetDerivationFact
import com.dynokore.gst.canonical.application.document.RoutingDirection
import com.dynokore.gst.canonical.application.document.RoutingFactsData
import com.dynokore.gst.canonical.application.document.RoutingNetworkPath
import com.dynokore.gst.canonical.application.netting.NetDerivationRequestHandler
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/**
 * The subscription of canonical-svc to `NetDerivationRequested` (INV-REQ-558 behavior 6) as one inbox consumer on the
 * inbox, offset and dead-letter tables of schema `canonical`, like `AggregateReady`. The handler runs inside one
 * transaction, so the derived version, its `netDerivation` row, its CanonicalVersionCreated or NetDerivationRefused
 * outbox row, the processed-request mark and the inbox row commit together. The acting identity of the written version
 * is the service identity the consumer is bound to (never a role, INV-Q-10647).
 */
@Configuration(proxyBeanMethods = false)
class NetDerivationConsumerConfiguration {
    @Bean
    fun netDerivationDocuments(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqNetDerivationDocuments = JooqNetDerivationDocuments(dsl, tenants)

    @Bean
    fun netDerivationRequest(): NetDerivationRequestScope = NetDerivationRequestScope()

    @Bean
    fun netDerivationRequestHandler(
        dsl: DSLContext,
        tenants: TenantContext,
        outbox: OutboxWriter,
        trace: InboundEventTrace,
        request: NetDerivationRequestScope,
        documents: JooqNetDerivationDocuments,
        writer: CanonicalVersionWriter,
    ): NetDerivationRequestHandler {
        val versions =
            CapturingNetDerivedVersions(
                JacksonNetDerivedVersions { docUuid, version ->
                    checkNotNull(documents.versionAsBase(docUuid, version, actingIdentity(tenants))) {
                        "version $version of $docUuid is not stored"
                    }
                },
            )
        return NetDerivationRequestHandler(
            requests = JooqProcessedNetDerivationRequests(dsl, tenants),
            documents = documents,
            versions = versions,
            writer = writer,
            records = JooqNetDerivationRecords(dsl, tenants),
            refusals =
                OutboxNetDerivationRefusedPublisher(
                    outbox,
                    tenants,
                    { request.entity() },
                    { trace.correlation() },
                    { trace.causation() },
                ),
            factsFor = { docUuid, net -> factsOf(documents, versions, docUuid, net) },
        )
    }

    @Bean
    fun netDerivationRequestedHandler(
        handler: NetDerivationRequestHandler,
        transaction: TransactionRunner,
        trace: InboundEventTrace,
        request: NetDerivationRequestScope,
    ): EventHandler =
        InboundTraceHandler(
            NetDerivationRequestedEventHandler(NetDerivationRequestedReader(), handler, transaction, request),
            trace,
        )

    @Bean
    @Suppress("LongParameterList")
    fun netDerivationRequestedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        netDerivationRequestedHandler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            netDerivationRequestedHandler,
            rebuildRequired,
            clock,
        )

    /**
     * The facts of the CanonicalVersionCreated event of the derived version, as far as they are stored: the entity,
     * period, scope verdict, direction and network path of the base version, the derived canonical text, and a trace
     * reference of the same shape as the record path. `appliedPackVersions` is empty: the pack identifiers are not
     * stored with a version (gap reported in the merge request).
     */
    private fun factsOf(
        documents: JooqNetDerivationDocuments,
        versions: CapturingNetDerivedVersions,
        docUuid: UUID,
        net: NetDerivationFact,
    ): CanonicalVersionCreatedFacts {
        val basis =
            checkNotNull(documents.eventBasisOf(docUuid, net.baseVersion)) {
                "the base version ${net.baseVersion} of $docUuid has no stored event facts"
            }
        val transactionDate = checkNotNull(documents.transactionDateOf(docUuid)) { "$docUuid is not stored" }
        return CanonicalVersionCreatedFacts(
            docUuid = docUuid,
            entityRef = basis.entityRef,
            version = net.baseVersion + 1,
            transactionDate = transactionDate,
            prescribedPeriodRef = basis.prescribedPeriodRef,
            scopeVerdict = basis.scopeVerdict,
            appliedPackVersions = emptyList(),
            traceReference = "trace/$docUuid/v${net.baseVersion + 1}",
            canonicalDocument = versions.takeCanonical(),
            aggregateId = null,
            routingFacts =
                RoutingFactsData(
                    direction = RoutingDirection.valueOf(basis.direction),
                    networkPath =
                        if (basis.receivedViaNetwork) {
                            RoutingNetworkPath.RECEIVED_VIA_NETWORK
                        } else {
                            RoutingNetworkPath.NOT_ON_NETWORK
                        },
                ),
            netDerivation = net,
        )
    }

    private fun actingIdentity(tenants: TenantContext): String =
        checkNotNull(tenants.currentActor()) { "no service identity is bound to the consumer" }

    companion object {
        const val CONSUMER = "canonical-svc"
        const val TOPIC = "gst.submission.net-derivation-requested.v1"
        const val SCHEMA = "canonical"
    }
}

/** The request event being handled on this thread: the entity of the refusal is the entity of the request. */
class NetDerivationRequestScope {
    private val current = ThreadLocal<Envelope>()

    fun <T> within(
        event: Envelope,
        body: () -> T,
    ): T {
        current.set(event)
        try {
            return body()
        } finally {
            current.remove()
        }
    }

    fun entity(): EntityRef = checkNotNull(current.get()) { "no request is being handled" }.entity
}

/** Reads one `NetDerivationRequested` event and hands it to the application handler inside one transaction. */
class NetDerivationRequestedEventHandler(
    private val reader: NetDerivationRequestedReader,
    private val handler: NetDerivationRequestHandler,
    private val transaction: TransactionRunner,
    private val request: NetDerivationRequestScope,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.tenant.isPlatform) throw ContractViolation("the envelope carries no tenantid")
        val requestEventId =
            try {
                UUID.fromString(event.id.value)
            } catch (e: IllegalArgumentException) {
                throw ContractViolation("the event id is not a UUID", e)
            }
        val read = reader.read(event.dataJson)
        request.within(event) {
            transaction.inTransaction {
                handler.handle(requestEventId, read.originalDocUuid, read.expectedBaseVersion, read.creditNotes)
            }
        }
        return HandleOutcome.APPLIED
    }
}
