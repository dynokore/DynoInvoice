package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AuthorityChangedReplaySource
import com.dynokore.gst.access.adapters.http.ConsentController
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.contract.model.ConsentResponse
import com.dynokore.gst.access.contract.model.GrantConsentRequest
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.GrantResponse
import com.dynokore.gst.access.contract.model.WithdrawConsentRequest
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.outboxandqueue.ReplayedState
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/** The mapper of the tests: payloads are compared as JSON trees. */
internal val REPLAY_JSON = JsonMapper.builder().build()

private val AUTHORITY_CHANGED_TYPE = PlatformEventCatalogue.AUTHORITY_CHANGED.eventType

/**
 * One tenant over the real database for the replay tests of INV-T-9061: grants, consents and principals are written by
 * the service's own controllers (the live path), the source under test is the bean the service registers.
 */
internal class AuthorityReplayKit(
    val kit: PrincipalKit,
) {
    val context = kit.context
    val tenant: TenantId = kit.tenant
    val tenants = kit.tenants
    private val tx = context.getBean(TransactionRunner::class.java)
    private val grantsApi = context.getBean(GrantsController::class.java)
    private val consentApi = context.getBean(ConsentController::class.java)
    private val captured = context.getBean(CapturedEvents::class.java)
    val source: ReplayStateSource = context.getBean(ReplayStateSource::class.java)

    val signatory = "user:signatory-${tenant.value}"
    val agent = "user:agent-${tenant.value}"

    /** The rows seeded straight through the repository (no live event): their refs. */
    val seeded = mutableListOf<String>()

    init {
        kit.clock.freeze()
        listOf("CLI-A", "CLI-B").forEach { client ->
            kit.entity(client)
            val scope = Scope.Entity(EntityRef(client))
            seeded += Seeds.grant(context, tenant, SeedSpec(signatory, Role.CLIENT_SIGNATORY, scope))
        }
        seeded += Seeds.grant(context, tenant, SeedSpec(agent, Role.AGENT_OPERATOR, Scope.Entity(EntityRef("CLI-A"))))
        stampSeeded()
    }

    /** The first administrator and the seeded rows had no live event: they get the version a backfill would draw. */
    fun stampSeeded() {
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement(
                    "update access.role_grant set authority_version = nextval('access.authority_version_seq') " +
                        "where tenant_id = ? and authority_version is null and status in ('ACTIVE', 'REVOKED')",
                ).use {
                    it.setObject(1, tenant.value)
                    it.executeUpdate()
                }
        }
    }

    /** A grant row of any level inserted straight into the table, with a stored version; returns its grant ref. */
    fun insertGrant(
        of: UUID,
        level: String,
        scopeRef: String,
        period: Boolean = false,
    ): String {
        val ref = "GRT-" + UUID.randomUUID().toString().take(8)
        val identity = "user:$ref"
        val dates = if (period) "date '2026-01-01', date '2026-03-31'" else "null, null"
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.execute(
                    "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, " +
                        "updated_at, updated_by, version) values (gen_random_uuid(), '$of', '$identity', 'STAFF', " +
                        "now(), 's', now(), 's', 0)",
                )
                it.execute(
                    "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, " +
                        "scope_ref, period_from, period_to, valid_from, status, grant_source, created_at, " +
                        "created_by, updated_at, updated_by, version, authority_version) values " +
                        "(gen_random_uuid(), '$of', '$ref', '$identity', 'auditor', '$level', '$scopeRef', $dates, " +
                        "now(), 'ACTIVE', 'BY_HAND', now(), 's', now(), 's', 0, nextval('access.authority_version_seq'))",
                )
            }
        }
        return ref
    }

    fun grant(
        identity: String,
        role: Role,
        scope: String,
    ): GrantResponse =
        kit.asAdmin {
            grantsApi
                .grantRole(
                    UUID.randomUUID(),
                    GrantRequest(
                        GrantRequest.GranteeKind.STAFF,
                        identity,
                        role.wire,
                        scope,
                        LocalDate.now().minusDays(1),
                        LocalDate.now().plusDays(400),
                    ),
                ).body!!
        }

    fun revokeGrant(ref: String) = kit.asAdmin { grantsApi.revokeGrant(ref, UUID.randomUUID()) }

    fun consent(client: String): ConsentResponse =
        tenants
            .bind(tenant, signatory) {
                consentApi
                    .grantConsent(
                        UUID.randomUUID(),
                        GrantConsentRequest(
                            clientRef = client,
                            agentRef = tenant.toString(),
                            validFrom = LocalDate.of(2026, 1, 1),
                            authorisingPersonEmail = "director@client.example",
                            scope = null,
                            submitInvoiceData = true,
                            fileGstReturn = false,
                            mappingApproval = false,
                        ),
                    ).body!!
            }

    fun withdraw(consentRef: String): ConsentResponse =
        tenants
            .bind(tenant, signatory) {
                val request = WithdrawConsentRequest("Relationship ended")
                consentApi.withdrawConsent(consentRef, UUID.randomUUID(), request).body!!
            }

    /** The three grants, one revocation, one consent and one principal revocation of the task's state. */
    fun build(): Built {
        kit.entity("CLI-A")
        val g1 = grant("user:a", Role.TAX_APPROVER, "entity:CLI-A")
        val g2 = grant("user:b", Role.ENTITY_ADMIN, "entity:CLI-B")
        val g3 = grant("user:c", Role.AUDITOR, "tenant:$tenant")
        revokeGrant(g2.grantRef)
        val consent = consent("CLI-A")
        val principal = kit.create("INGEST", scope = "tenant:$tenant")
        kit.revoke(principal.principalRef)
        return Built(g1.grantRef, g2.grantRef, g3.grantRef, consent.consentRef, principal.principalRef)
    }

    class Built(
        val g1: String,
        val g2: String,
        val g3: String,
        val consent: String,
        val principal: String,
    )

    /** Every page of [scope] for [of], following the cursor. */
    fun replay(
        scope: ReplayScope? = null,
        limit: Int = 1000,
        of: TenantId = tenant,
    ): List<ReplayedState> {
        val all = mutableListOf<ReplayedState>()
        var cursor: String? = null
        do {
            val page = page(scope, cursor, limit, of)
            all += page.items
            cursor = page.next
        } while (cursor != null)
        return all
    }

    fun page(
        scope: ReplayScope?,
        cursor: String?,
        limit: Int,
        of: TenantId = tenant,
    ) = tenants.bind(of, "svc:access-svc/job:replay") { tx.inTransaction { source.page(scope, cursor, limit) } }

    /** The live events of this tenant, oldest first. */
    fun liveEnvelopes(): List<Envelope> =
        captured.envelopes.filter { it.tenant == tenant && it.type == AUTHORITY_CHANGED_TYPE }

    /** The last live payload of each record key. */
    fun lastLive(): Map<String, JsonNode> =
        liveEnvelopes().map { REPLAY_JSON.readTree(it.dataJson) }.associateBy { keyOf(it) }

    /** The grant rows of this tenant that carry a live record (not PENDING), whatever wrote them. */
    fun heldGrantRefs(): Set<String> =
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement(
                    "select grant_ref from access.role_grant where tenant_id = ? and status in ('ACTIVE', 'REVOKED')",
                ).use {
                    it.setObject(1, tenant.value)
                    it.executeQuery().use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toSet()
                    }
                }
        }

    fun outboxRows(): Int =
        BoundaryFixture.asSuperuser { connection ->
            connection.prepareStatement("select count(*) from access.outbox where tenant_id = ?").use {
                it.setObject(1, tenant.value)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    /** The stored `authority_version` of a row of [table] found by [column] = [ref], null when the column is null. */
    fun storedVersion(
        table: String,
        column: String,
        ref: String,
    ): Long? =
        BoundaryFixture.asSuperuser { connection ->
            connection.prepareStatement("select authority_version from access.$table where $column = ?").use {
                it.setString(1, ref)
                it.executeQuery().use { rows ->
                    check(rows.next()) { "no row of $table for $ref" }
                    rows.getLong(1).takeUnless { rows.wasNull() }
                }
            }
        }

    /** The whole row as JSON text, to compare a row with itself. */
    fun rowText(
        table: String,
        column: String,
        ref: String,
    ): String =
        BoundaryFixture.asSuperuser { connection ->
            connection.prepareStatement("select to_jsonb(t)::text from access.$table t where $column = ?").use {
                it.setString(1, ref)
                it.executeQuery().use { rows ->
                    check(rows.next()) { "no row of $table for $ref" }
                    rows.getString(1)
                }
            }
        }

    companion object {
        /** The key of a record: ROLE:<grantRef>, CONSENT:<client entity> or PRINCIPAL:<principalRef>. */
        fun keyOf(payload: JsonNode): String =
            when {
                payload.has("roleGrantRecord") -> "ROLE:" + payload["roleGrantRecord"]["grantRef"].asString()
                payload.has("consentRecord") -> "CONSENT:" + payload["consentRecord"]["clientEntityRef"].asString()
                payload.has("principalRecord") -> "PRINCIPAL:" + payload["principalRef"].asString()
                else -> error("a record of no known kind: $payload")
            }

        fun keyOf(state: ReplayedState): String = keyOf(REPLAY_JSON.readTree(state.dataJson))

        fun run(block: (AuthorityReplayKit) -> Unit) {
            BoundaryFixture.runner().run { context ->
                context.startupFailure?.let { throw it }
                block(AuthorityReplayKit(PrincipalKit(context)))
            }
        }
    }
}
