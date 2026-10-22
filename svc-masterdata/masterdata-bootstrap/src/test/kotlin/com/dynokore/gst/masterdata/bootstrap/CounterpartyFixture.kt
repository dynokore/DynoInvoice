package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.StructureNodeFeedHandler
import com.dynokore.gst.masterdata.adapters.http.IdentityTriplesController
import com.dynokore.gst.masterdata.application.counterparty.RecordCommand
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.application.identity.ProposeCommand
import com.dynokore.gst.masterdata.application.identity.ProposeIdentityTripleChange
import com.dynokore.gst.masterdata.contract.api.RemediationApi
import com.dynokore.gst.masterdata.contract.api.ResolutionApi
import com.dynokore.gst.masterdata.contract.model.ApprovedScheme
import com.dynokore.gst.masterdata.contract.model.Counterparty
import com.dynokore.gst.masterdata.contract.model.CounterpartyAddress
import com.dynokore.gst.masterdata.contract.model.ResolutionResult
import com.dynokore.gst.masterdata.contract.model.ResolveCounterpartyRequest
import com.dynokore.gst.masterdata.contract.model.ResolveCounterpartyRequestResolvedIdentifiersInner
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.IdentifierInput
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A name that no other test uses, so the tests share one database without depending on one another. */
internal fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

internal val CONSTITUTION = Constitution("COMPANY")
internal val TRANSACTION_DATE: LocalDate = LocalDate.of(2026, 6, 1)
internal val ADDRESS = CounterpartyAddress(addressLine1 = "1 Raffles Place", postalCode = "048616")
internal const val OPERATOR = "IDN-OPERATOR"

/** The real application context over the shared PostgreSQL, with a tenant of its own. */
internal fun runCounterparty(block: (CounterpartyEnv) -> Unit) {
    BoundaryFixture.runner().run { context ->
        context.startupFailure?.let { throw it }
        block(CounterpartyEnv(context))
    }
}

/** What the criteria tests call: the generated server interfaces, the use cases and the structure feed handler. */
internal class CounterpartyEnv(
    val context: AssertableWebApplicationContext,
) {
    private val admin = "IDN-ADMIN"
    private val otherAdmin = "IDN-ADMIN-2"
    val tenants: TenantContext = context.getBean(TenantContext::class.java)
    val resolution: ResolutionApi = context.getBean(ResolutionApi::class.java)
    val remediation: RemediationApi = context.getBean(RemediationApi::class.java)
    val record: RecordCounterparty = context.getBean(RecordCounterparty::class.java)
    val feed: StructureNodeFeedHandler = context.getBean(StructureNodeFeedHandler::class.java)
    val captured: CapturedEvents = context.getBean(CapturedEvents::class.java)
    val tenant = TenantId(UUID.randomUUID())

    /** A second environment over the same context with a tenant of its own. */
    fun anotherTenant() = CounterpartyEnv(context)

    fun <T> inTenant(
        actor: String = OPERATOR,
        block: () -> T,
    ): T = tenants.bind(tenant, actor) { block() }

    /** Configures the identity triple of [entity], in force from 2025, so that its resolution answers. */
    fun triple(entity: String) {
        val change =
            inTenant(admin) {
                context.getBean(ProposeIdentityTripleChange::class.java).handle(
                    ProposeCommand(
                        EntityRef(entity),
                        CONSTITUTION,
                        IdentityTriple("0195:U-$entity", "U-$entity", "G-$entity"),
                        LocalDate.of(2025, 1, 1),
                        PersonRef(admin),
                    ),
                )
            }
        inTenant(otherAdmin) {
            context
                .getBean(IdentityTriplesController::class.java)
                .confirmIdentityTripleChange(entity, change.changeRef, UUID.randomUUID())
        }
    }

    @Suppress("LongParameterList") // a test builder: every part of the request is optional
    fun request(
        vararg identifiers: Pair<String, String>,
        evidence: String = "EVD-1",
        name: String? = null,
        address: CounterpartyAddress? = null,
        arcs: Boolean? = null,
        schemes: List<ApprovedScheme>? = null,
    ) = ResolveCounterpartyRequest(
        resolvedIdentifiers =
            identifiers.map { ResolveCounterpartyRequestResolvedIdentifiersInner(it.first, it.second) },
        evidenceRef = evidence,
        legalName = name,
        address = address,
        arcsGstChargeAgreed = arcs,
        approvedSchemes = schemes,
    )

    /** `resolveMasterDataGap` through the generated interface, as the business user [actor]. */
    fun write(
        party: String,
        request: ResolveCounterpartyRequest,
        actor: String = OPERATOR,
    ): Counterparty = inTenant(actor) { remediation.resolveMasterDataGap(party, UUID.randomUUID(), request).body!! }

    /** A write through the application use case, as an import or a signed-link confirmation would make it. */
    fun recorded(
        party: String,
        origin: WriteOrigin,
        evidence: String,
        change: CounterpartyChange,
    ) = inTenant { record.handle(RecordCommand(PartyRef(party), change, evidence, origin)) }

    fun identifiers(vararg pairs: Pair<IdentifierScheme, String>) = pairs.map { IdentifierInput(it.first, it.second) }

    fun resolve(
        entity: String,
        scheme: String?,
        value: String?,
        on: LocalDate = TRANSACTION_DATE,
    ): ResolutionResult =
        inTenant { resolution.resolveCounterparty(entity, CONSTITUTION.code, on, scheme, value).body!! }

    fun history(
        party: String,
        limit: Int = 100,
        cursor: String? = null,
    ) = inTenant { resolution.getCounterpartyHistory(party, limit, cursor).body!! }

    /** The finding triples (code, severity, disposition) of a resolution. */
    fun codes(result: ResolutionResult) =
        result.findings.orEmpty().map { Triple(it.code, it.severity.value, it.disposition) }

    /** Applies a structure node through the handler the consumer runs, as the tenancy event would carry it. */
    @Suppress("LongParameterList") // a test builder: the node and the event are described by their parts
    fun structure(
        ref: String,
        kind: String,
        parent: String?,
        uen: String?,
        gstn: String? = null,
        retired: Boolean = false,
        version: Int = 1,
        type: String = ProductEventTypes.ENTITY_PROFILE_CHANGED,
    ) {
        val node =
            buildString {
                append("{\"kind\":\"$kind\",\"retired\":$retired,\"name\":\"$ref\",\"profile\":[]")
                append(",\"structureVersion\":$version")
                if (parent != null) append(",\"parentRef\":\"$parent\"")
                if (uen != null) append(",\"uen\":\"$uen\"")
                if (gstn != null) append(",\"gstn\":\"$gstn\"")
                append("}")
            }
        val data =
            if (type == ProductEventTypes.ENTITY_PROFILE_CHANGED) {
                "{\"entityRef\":\"$ref\",\"changedAttributes\":[],\"node\":$node}"
            } else {
                "{\"nodeType\":\"$kind\",\"nodeRef\":\"$ref\",\"changeType\":\"UPDATED\",\"node\":$node}"
            }
        val envelope =
            Envelope(
                id = EventId(UUID.randomUUID().toString()),
                source = "/gst/tenancy",
                type = type,
                subject = ref,
                time = Instant.now(),
                tenant = tenant,
                entity =
                    com.dynokore.platform.outboxandqueue
                        .EntityRef(ref),
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "tenancy-svc-0",
                correlation = CorrelationId("c-1"),
                causation = CausationId("c-1"),
                dataJson = data,
            )
        inTenant { feed.handle(envelope) }
    }

    /** Rows of [table] about [party], selected as text and seen as the superuser (every tenant). */
    fun rows(
        table: String,
        party: String,
        columns: String,
        where: String = "true",
    ): List<List<String?>> =
        BoundaryFixture.asSuperuser { c ->
            c
                .createStatement()
                .executeQuery("select $columns from masterdata.$table where party_ref = '$party' and $where order by 1")
                .use { rs ->
                    val out = mutableListOf<List<String?>>()
                    while (rs.next()) out += (1..rs.metaData.columnCount).map { rs.getString(it) }
                    out
                }
        }

    fun count(sql: String): Int =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /** POST of a raw JSON body to `resolveMasterDataGap`: the status and the body text. */
    fun post(
        party: String,
        body: String,
    ): Pair<Int, String> {
        val web = context.sourceApplicationContext as WebApplicationContext
        val mvc = MockMvcBuilders.webAppContextSetup(web).build()
        return inTenant {
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/internal/v1/masterdata/counterparties/$party/resolution")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andReturn()
                .let { it.response.status to it.response.contentAsString }
        }
    }
}
