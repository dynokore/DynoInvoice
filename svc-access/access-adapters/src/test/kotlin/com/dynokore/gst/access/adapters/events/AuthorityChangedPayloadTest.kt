package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.consent.ConsentAuthorityRecord
import com.dynokore.gst.access.application.grant.RoleGrantChange
import com.dynokore.gst.access.application.grant.RoleGrantState
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ConsentTerms
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.outboxandqueue.EmissionGuard
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.ProductEventTypes
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * A test per published channel (INV-T-4934): the payload this service writes validates against the AsyncAPI schema of
 * its
 * channel, and the emission guard accepts exactly the catalogued types (TPL-05, API-43).
 */
class AuthorityChangedPayloadTest {
    private val mapper = JsonMapper.builder().build()
    private val outbox =
        AuthorityChangedOutbox(
            outbox = org.mockito.Mockito.mock(com.dynokore.platform.outboxandqueue.OutboxWriter::class.java),
            mapper = mapper,
            head = InMemoryAuthorityHead(),
            incarnations = InMemoryAuthorityIncarnation(),
        )
    private val operatorOutbox =
        OperatorAccessOutbox(
            org.mockito.Mockito.mock(com.dynokore.platform.outboxandqueue.OutboxWriter::class.java),
            mapper,
            InMemoryAuthorityIncarnation(),
        )
    private val schema =
        resource("gst/contracts/service-runtime/schemas/authority-changed-payload.schema.json").let { text ->
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(text)
        }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "$path is not on the classpath" }.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private val tenantValue = "0a000000-0000-4000-8000-00000000000a"

    private fun change(
        level: ScopeLevel = ScopeLevel.ENTITY,
        state: RoleGrantState = RoleGrantState.ACTIVE,
        scopeRef: String = "ENT-1",
        periods: Pair<LocalDate, LocalDate>? = null,
        version: Long = 42,
    ) = RoleGrantChange(
        tenant = TenantRef(tenantValue),
        identity = IdentityRef("IDN-1"),
        grantRef = GrantRef("GRT-1"),
        role = Role.TAX_APPROVER,
        state = state,
        scopeLevel = level,
        scopeRef = scopeRef,
        validFrom = Instant.parse("2026-10-19T00:00:00Z"),
        validUntil = Instant.parse("2027-09-30T23:59:59Z"),
        periodFrom = periods?.first,
        periodTo = periods?.second,
        authorityVersion = version,
    )

    private val span = LocalDate.of(2027, 1, 1) to LocalDate.of(2027, 6, 30)

    /** One change per scope level, with the scope reference the mapper gives it. */
    private val perLevel: List<RoleGrantChange> =
        listOf(
            change(ScopeLevel.TENANT, scopeRef = tenantValue),
            change(ScopeLevel.GROUP, scopeRef = "GRP-1"),
            change(ScopeLevel.ENTITY),
            change(ScopeLevel.ENTITY_PERIOD, periods = span),
            change(ScopeLevel.CONSENT, scopeRef = tenantValue),
        )

    private fun errors(change: RoleGrantChange) =
        schema.validate(outbox.payload(change).toString(), com.networknt.schema.InputFormat.JSON)

    @Test
    fun `INV-AC-22069 every AuthorityChanged payload kind carries the stored incarnation and validates against the schema`() {
        val payloads = AuthorityChangedPayloads(mapper, InMemoryAuthorityIncarnation(4_242))
        val installation =
            com.dynokore.gst.access.adapters.persistence.InstallationRoleGrantRow(
                InstallationGrantRef
                    .of("deployment.operator", "user:op-1"),
                "user:op-1",
                "deployment.operator",
                true,
                42,
                java.time.OffsetDateTime
                    .parse("2026-11-09T01:02:03Z"),
            )
        val byKind =
            mapOf(
                "ROLE_GRANT" to payloads.roleGrant(change()),
                "INSTALLATION" to payloads.installationGrant(installation),
                "CONSENT" to payloads.consent(consent(ConsentStatus.LIVE, ScopeItem.entries.first())),
                "REVOCATION" to payloads.principalRevoked("PRN-1", 9, Instant.parse("2026-11-01T00:00:00Z")),
                "OPERATOR_ACCESS" to payloads.operatorAccess(operatorRecord(null)),
            )
        byKind.forEach { (kind, node) ->
            assertEquals(4_242, node["incarnation"].asInt(), kind)
            val found = schema.validate(node.toString(), com.networknt.schema.InputFormat.JSON)
            assertTrue(found.isEmpty(), "$kind: $found")
        }
    }

    @Test
    fun `a role grant change of every scope level and state validates against the AuthorityChanged schema`() {
        perLevel.forEach { c ->
            RoleGrantState.entries.forEach { state ->
                val errors = errors(c.copy(state = state))
                assertTrue(errors.isEmpty(), "${c.scopeLevel} $state $errors")
            }
        }
        assertTrue(errors(change().copy(validUntil = null)).isEmpty())
    }

    @Test
    fun `the payload of a grant carries the role grant record with each field equal to the expected value`() {
        val node = outbox.payload(change())
        assertEquals("ROLE_GRANT", node["changeKind"].asString())
        assertEquals("IDN-1", node["principalRef"].asString())
        assertEquals(42L, node["authorityVersion"].asLong())
        val record = node["roleGrantRecord"]
        assertEquals("GRT-1", record["grantRef"].asString())
        assertEquals("tax.approver", record["role"].asString())
        assertEquals("ACTIVE", record["state"].asString())
        assertEquals("ENTITY", record["scopeLevel"].asString())
        assertEquals("ENT-1", record["scopeRef"].asString())
        assertEquals("2026-10-19T00:00:00Z", record["validFrom"].asString())
        assertEquals("2027-09-30T23:59:59Z", record["validUntil"].asString())
        assertTrue(!record.has("periodFrom") && !record.has("periodTo"))
        assertTrue(!node.has("grantRecord") && !node.has("consentRecord") && !node.has("principalRecord"))
        assertTrue(!node.has("validity"))
    }

    @Test
    fun `INV-AC-1911 an entity-period grant carries the span as dates and no periodRefs`() {
        val record = outbox.payload(change(ScopeLevel.ENTITY_PERIOD, periods = span))["roleGrantRecord"]
        assertEquals("ENTITY_PERIOD", record["scopeLevel"].asString())
        assertEquals("2027-01-01", record["periodFrom"].asString())
        assertEquals("2027-06-30", record["periodTo"].asString())
        assertTrue(!record.has("periodRefs"))
        assertEquals("2027-09-30T23:59:59Z", record["validUntil"].asString())
    }

    @Test
    fun `a revoke is the same grantRef in state REMOVED and an open-ended grant has no validUntil`() {
        val removed = outbox.payload(change(state = RoleGrantState.REMOVED, version = 43))
        assertEquals("REMOVED", removed["roleGrantRecord"]["state"].asString())
        assertEquals("GRT-1", removed["roleGrantRecord"]["grantRef"].asString())
        assertEquals(43L, removed["authorityVersion"].asLong())
        assertTrue(!outbox.payload(change().copy(validUntil = null))["roleGrantRecord"].has("validUntil"))
    }

    @Test
    fun `a scope level written lower case does not validate against the schema`() {
        val node = outbox.payload(change())
        (node["roleGrantRecord"] as tools.jackson.databind.node.ObjectNode).put("scopeLevel", "entity")
        assertTrue(schema.validate(node.toString(), com.networknt.schema.InputFormat.JSON).isNotEmpty())
    }

    @Test
    fun `the schema refuses a payload the mapper never writes`() {
        assertTrue(
            schema
                .validate(
                    """{"changeKind":"GRANT","principalRef":"p"}""",
                    com.networknt.schema.InputFormat.JSON,
                ).isNotEmpty(),
        )
        assertTrue(
            schema
                .validate(
                    """{"changeKind":"OTHER","principalRef":"p","authorityVersion":1}""",
                    com.networknt.schema.InputFormat.JSON,
                ).isNotEmpty(),
        )
    }

    @Test
    fun `an operator access record validates against the AuthorityChanged schema, with and without an elevation`() {
        val masked = operatorRecord(null)
        val elevated =
            operatorRecord(
                com.dynokore.gst.access.application.operatorsession.ElevationWindowRecord(
                    "ELV-1",
                    Instant.parse("2026-11-11T00:00:00Z"),
                    Instant.parse("2026-11-11T01:00:00Z"),
                ),
            )
        listOf(masked, elevated).forEach {
            val payload = operatorOutbox.payload(it)
            assertTrue(schema.validate(payload.toString(), com.networknt.schema.InputFormat.JSON).isEmpty())
            assertEquals("OPERATOR_ACCESS", payload["changeKind"].asString())
            assertEquals("op-1", payload["principalRef"].asString())
            assertEquals(
                listOf("period-readiness.view", "submission.view-status"),
                payload["operatorAccessRecord"]["permissions"].toList().map { p -> p.asString() },
            )
        }
        assertTrue(!operatorOutbox.payload(masked)["operatorAccessRecord"].has("elevation"))
        assertTrue(operatorOutbox.payload(elevated)["operatorAccessRecord"].has("elevation"))
    }

    private fun operatorRecord(elevation: com.dynokore.gst.access.application.operatorsession.ElevationWindowRecord?) =
        com.dynokore.gst.access.application.operatorsession.OperatorAccessRecord(
            tenant =
                com.dynokore.platform.tenancykernel.TenantId
                    .of(tenantValue),
            operatorRef = "op-1",
            authorityVersion = 7,
            caseRef = "CASE-1",
            role = "platform.support",
            permissions = listOf("period-readiness.view", "submission.view-status"),
            validUntil = Instant.parse("2026-11-11T00:15:00Z"),
            elevation = elevation,
        )

    @Test
    fun `the emission guard accepts the types access produces and rejects an uncatalogued type`() {
        val catalogue = AccessEventCatalogue.load()
        val guard = EmissionGuard(catalogue, AccessEventCatalogue.SERVICE)
        listOf(
            PlatformEventCatalogue.AUTHORITY_CHANGED.eventType,
            PlatformEventCatalogue.OPERATOR_SESSION_KEYS_CHANGED.eventType,
            "com.dynokore.gst.access.AccessAlertRaised.v1",
            "com.dynokore.gst.access.MessageRequested.v1",
        ).forEach { guard.require(it) }
        listOf(
            "com.dynokore.gst.access.NotInTheCatalogue.v1",
            "com.dynokore.gst.access.ExampleHappened.v1",
            "com.dynokore.gst.access.authoritychanged.v1",
            ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED, // produced by tenancy-svc, not by access
            "",
        ).forEach { assertThrows(EmissionNotPermitted::class.java, { guard.require(it) }, it) }
        // another service's guard over the same catalogue does not let it emit access's event
        assertThrows(EmissionNotPermitted::class.java) {
            EmissionGuard(catalogue, "tenancy-svc").require(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType)
        }
    }

    private fun consent(
        status: ConsentStatus,
        vararg items: ScopeItem,
    ) = ConsentAuthorityRecord(
        TenantRef("0a000000-0000-4000-8000-00000000000a"),
        Consent(
            ConsentRef("CNS-1"),
            EntityRef("CLI-1"),
            IdentityRef("AGT-1"),
            ConsentTerms(items.toSet(), LocalDate.of(2026, 11, 1), "signatory@client.example"),
            status,
            if (status == ConsentStatus.WITHDRAWN) "HOS-1" else null,
            0,
        ),
        43,
    )

    @Test
    fun `a consent record, live or withdrawn, with any combination of items validates against the schema`() {
        listOf(
            consent(ConsentStatus.LIVE),
            consent(ConsentStatus.LIVE, ScopeItem.SUBMIT_INVOICE_DATA),
            consent(ConsentStatus.WITHDRAWN, ScopeItem.FILE_GST_RETURN, ScopeItem.MAPPING_APPROVAL),
        ).forEach { record ->
            val errors =
                schema.validate(outbox.consentPayload(record).toString(), com.networknt.schema.InputFormat.JSON)
            assertTrue(errors.isEmpty(), errors.toString())
        }
    }

    @Test
    fun `the consent payload carries the whole state, the agent as principal and nothing of a grant`() {
        val node = outbox.consentPayload(consent(ConsentStatus.WITHDRAWN, ScopeItem.SUBMIT_INVOICE_DATA))
        assertEquals("CONSENT", node["changeKind"].asString())
        assertEquals("AGT-1", node["principalRef"].asString())
        assertEquals(43L, node["authorityVersion"].asLong())
        assertEquals("CLI-1", node["consentRecord"]["clientEntityRef"].asString())
        assertEquals("WITHDRAWN", node["consentRecord"]["state"].asString())
        val items = node["consentRecord"]["scopeItems"]
        assertEquals(listOf("SUBMIT_INVOICE_DATA"), (0 until items.size()).map { items[it].asString() })
        assertTrue(!node.has("grantRecord") && !node.has("principalRecord"))
    }

    @Test
    fun `INV-Q-10878 the three publishers write the platform AuthorityChanged type`() {
        val captured = mutableListOf<OutboundEvent>()
        val writer =
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<Envelope> {
                    captured.addAll(events)
                    return emptyList()
                }
            }
        val grants = AuthorityChangedOutbox(writer, mapper, InMemoryAuthorityHead(), InMemoryAuthorityIncarnation())
        grants.publish(listOf(change()))
        grants.publish(consent(ConsentStatus.LIVE, ScopeItem.SUBMIT_INVOICE_DATA))
        val principal =
            ServicePrincipal(
                "PRN-1",
                TenantRef(tenantValue),
                PrincipalType.INGEST,
                "tenant:$tenantValue",
                null,
                PrincipalStatus.REVOKED,
                1,
            )
        PrincipalRevocationOutbox(
            writer,
            mapper,
            Clock.systemUTC(),
            InMemoryAuthorityHead(),
            InMemoryAuthorityIncarnation(),
        ).publishRevoked(principal) { 7L }
        assertEquals(3, captured.size)
        captured.forEach { assertEquals("com.dynokore.gst.platform.AuthorityChanged.v1", it.type) }
    }
}
