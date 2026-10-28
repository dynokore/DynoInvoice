package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.election.AccessPointRouteChangedHandler
import com.dynokore.gst.tenancy.adapters.election.ConsumedElectionEvents
import com.dynokore.gst.tenancy.adapters.election.RulePackActivatedHandler
import com.dynokore.gst.tenancy.adapters.election.RulePackStagedHandler
import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.ConstraintRefusal
import com.dynokore.gst.tenancy.adapters.http.PendingChangeAccepted
import com.dynokore.gst.tenancy.application.election.AccessPointRouteStore
import com.dynokore.gst.tenancy.application.election.ElectionCommands
import com.dynokore.gst.tenancy.application.election.ElectionGate
import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.application.election.ElectionStatusQueries
import com.dynokore.gst.tenancy.application.election.ScopePackStore
import com.dynokore.gst.tenancy.contract.model.ElectionRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.PendingElectionChange
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionRequest
import com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.productruntime.ProductEventTypes
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal const val TWO_SOURCES =
    "[{\"sourceId\":\"MAS\",\"bases\":[\"DAILY\"]},{\"sourceId\":\"BNM\",\"bases\":[\"MONTHLY_AVERAGE\"]}]"

internal const val ADMIN = "identity:USR-001"
internal const val OTHER_ADMIN = "identity:USR-004"
internal const val APPROVER = "identity:USR-009"
internal const val ENTITY_ADMIN_ONLY = "identity:USR-010"
internal const val NOBODY = "identity:USR-099"

/** The election helpers the tests share, over the application context of [ProfileEnv]. */
internal class ElectionEnv(
    val env: ProfileEnv,
) {
    val people: TestPeople = env.context.getBean(TestPeople::class.java)
    val facts: TestFacts = env.context.getBean(TestFacts::class.java)
    val commands: ElectionCommands = env.context.getBean(ElectionCommands::class.java)
    val queries: ElectionStatusQueries = env.context.getBean(ElectionStatusQueries::class.java)
    val gate: ElectionGate = env.context.getBean(ElectionGate::class.java)
    val store: ElectionPolicyStore = env.context.getBean(ElectionPolicyStore::class.java)
    val routes: AccessPointRouteStore = env.context.getBean(AccessPointRouteStore::class.java)
    val packs: ScopePackStore = env.context.getBean(ScopePackStore::class.java)
    private val mapper = JsonMapper.builder().build()
    private val routeHandler = AccessPointRouteChangedHandler(routes, mapper)
    private val stagedHandler = RulePackStagedHandler(packs, mapper)
    private val activatedHandler = RulePackActivatedHandler(packs, mapper)

    /** Roles of the usual people: ADMIN entity.admin, OTHER_ADMIN tenant.admin, APPROVER tax.approver. */
    fun usualPeople(entity: EntityResponse) {
        people.caller = ADMIN
        people.grant(ADMIN, entity.entityRef, "entity.admin")
        people.grant(OTHER_ADMIN, entity.entityRef, "tenant.admin")
        people.grant(APPROVER, entity.entityRef, "tax.approver")
        people.grantPermission(APPROVER, entity.entityRef, ElectionPermissions.APPROVE)
        people.grant(ENTITY_ADMIN_ONLY, entity.entityRef, "entity.admin")
    }

    fun mode(
        entity: EntityResponse,
        mode: SetIssuanceModeRequest.Mode,
        from: LocalDate = TODAY,
        confirmer: String = OTHER_ADMIN,
    ) = env.api.setIssuanceMode(entity.entityRef, UUID.randomUUID(), SetIssuanceModeRequest(mode, from, confirmer))

    fun elect(
        entity: EntityResponse,
        election: String,
        from: LocalDate = TODAY,
    ) = env.api.recordElection(entity.entityRef, UUID.randomUUID(), ElectionRequest(election, from))

    fun rate(
        entity: EntityResponse,
        source: String,
        basis: RateSourceElectionRequest.Basis,
        from: LocalDate = TODAY,
    ) = env.api.electRateSource(
        entity.entityRef,
        UUID.randomUUID(),
        RateSourceElectionRequest(source, basis, from),
    )

    /** The 202 body [block] answers: the call must store a PENDING change and write no version. */
    fun pending(block: () -> Unit): PendingElectionChange =
        try {
            block()
            error("the call did not answer 202")
        } catch (accepted: PendingChangeAccepted) {
            accepted.change
        }

    fun approve(
        entity: EntityResponse,
        changeRef: String,
    ) = env.api.approveElectionChange(entity.entityRef, changeRef, UUID.randomUUID())

    /** The states of the entity's election changes, oldest first, read as the superuser. */
    fun changeStates(entity: EntityResponse): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select state from tenancy.election_change where entity_ref = ? order by requested_at, change_ref",
                ).use { s ->
                    s.setString(1, entity.entityRef)
                    s.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
        }

    /** Audit entries of the entity with the action [action] ("election-change.requested" or ".approved"). */
    fun changeAudits(
        entity: EntityResponse,
        action: String,
    ): Long = outboxCount(entity, AUDIT_TOPIC, action)

    /** Events of the entity other than audit entries (policy changes, profile changes). */
    fun otherEvents(entity: EntityResponse): Long = outboxCount(entity, NOT_AUDIT, "")

    private fun outboxCount(
        entity: EntityResponse,
        topic: String,
        action: String,
    ): Long =
        BoundaryFixture.asSuperuser { c ->
            val comparison = if (topic == NOT_AUDIT) "<>" else "="
            c
                .prepareStatement(
                    "select count(*) from tenancy.outbox where topic $comparison '$AUDIT_TOPIC' " +
                        "and data::text like ? and data::text like ?",
                ).use { s ->
                    s.setString(1, "%${entity.entityRef}%")
                    s.setString(2, "%$action%")
                    s.executeQuery().use {
                        it.next()
                        it.getLong(1)
                    }
                }
        }

    fun history(
        entity: EntityResponse,
        kind: ElectionKind,
    ) = env.inTx { store.history(entity.entityRef, kind) }

    fun status(
        entity: EntityResponse,
        on: LocalDate = TODAY,
    ) = checkNotNull(queries.status(entity.entityRef, on))

    /** The refusal [block] raises: its HTTP status and code, or null when it did not refuse. */
    fun refusal(block: () -> Unit): Pair<Int, String>? =
        try {
            block()
            null
        } catch (refused: ApiRefusal) {
            refused.code.httpStatus to refused.code.code
        } catch (refused: ConstraintRefusal) {
            422 to "POL-014 ${refused.detail}"
        }

    /** The refusal text of [block] (the reason the caller is told), or null when it did not refuse. */
    fun reason(block: () -> Unit): String? =
        try {
            block()
            null
        } catch (refused: ApiRefusal) {
            refused.detail
        } catch (refused: ConstraintRefusal) {
            refused.detail
        }

    fun profileEvents(): List<JsonNode> =
        env.captured.envelopes
            .filter { it.type == ProductEventTypes.ENTITY_PROFILE_CHANGED }
            .map { mapper.readTree(it.dataJson) }

    fun issuanceEvents(): List<JsonNode> =
        profileEvents().filter { event ->
            event["changedAttributes"].values().any {
                it["attribute"].asString() ==
                    "issuanceMode"
            }
        }

    private fun envelope(
        type: String,
        tenant: TenantId,
        subject: String,
        data: String,
    ) = Envelope(
        EventId(UUID.randomUUID().toString()),
        "/gst/" + type.removePrefix("com.dynokore.gst.").substringBefore('.') + "-svc",
        type,
        subject,
        Instant.now(),
        tenant,
        EntityRef(subject),
        null,
        null,
        null,
        "test-0",
        CorrelationId("corr-${UUID.randomUUID()}"),
        CausationId("cause-${UUID.randomUUID()}"),
        data,
    )

    /** Feeds one AccessPointRouteChanged to the handler in the consumer's tenant transaction; true when applied. */
    fun routeChanged(
        tenant: TenantId,
        connection: String,
        entityRefs: List<String>,
        route: String?,
        version: Long,
    ): Boolean {
        val refs = entityRefs.joinToString(",") { "\"$it\"" }
        val routeText = route?.let { ",\"acknowledgementRoute\":\"$it\"" }.orEmpty()
        val data = "{\"connectionRef\":\"$connection\",\"entityRefs\":[$refs]$routeText,\"version\":$version}"
        val event = envelope(ConsumedElectionEvents.ACCESS_POINT_ROUTE_CHANGED, tenant, connection, data)
        return env.inTenant(tenant) { env.inTx { routeHandler.handle(event).name == "APPLIED" } }
    }

    fun staged(
        tenant: TenantId,
        pack: String,
        version: String,
        packType: String = "SCOPE",
        sources: String = "[{\"sourceId\":\"MAS\",\"name\":\"MAS\",\"bases\":[\"DAILY\",\"MONTHLY_AVERAGE\"]}]",
    ): Boolean {
        val data =
            "{\"packIdentifier\":\"$pack\",\"packType\":\"$packType\",\"version\":\"$version\"," +
                "\"effectiveFrom\":\"2026-01-01\",\"minEngineVersion\":\"1\"," +
                "\"artefact\":{\"manifest\":{},\"checksums\":{},\"signature\":\"s\"," +
                "\"contents\":{\"exchangeRateSources\":$sources}}}"
        val event = envelope(ConsumedElectionEvents.RULE_PACK_STAGED, tenant, pack, data)
        return env.inTenant(tenant) { env.inTx { stagedHandler.handle(event).name == "APPLIED" } }
    }

    fun activated(
        tenant: TenantId,
        pack: String,
        version: String,
        from: LocalDate,
    ): Boolean {
        val data = "{\"packIdentifier\":\"$pack\",\"version\":\"$version\",\"effectiveFrom\":\"$from\"}"
        val event = envelope(ConsumedElectionEvents.RULE_PACK_ACTIVATED, tenant, pack, data)
        return env.inTenant(tenant) { env.inTx { activatedHandler.handle(event).name == "APPLIED" } }
    }

    /** Audit entries of the entity's elections of [vp], read as the superuser. */
    fun auditEntries(
        entity: EntityResponse,
        vp: String,
    ): Long =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select count(*) from tenancy.outbox where topic = 'gst.platform.audit-entry-recorded.v1' " +
                        "and data::text like ? and data::text like ?",
                ).use { s ->
                    s.setString(1, "%${entity.entityRef}%")
                    s.setString(2, "%$vp election%")
                    s.executeQuery().use {
                        it.next()
                        it.getLong(1)
                    }
                }
        }

    companion object {
        val UNAUTHENTICATED = ClosedErrorCode.UNAUTHENTICATED
        private const val AUDIT_TOPIC = "gst.platform.audit-entry-recorded.v1"
        private const val NOT_AUDIT = "not-audit"
    }
}
