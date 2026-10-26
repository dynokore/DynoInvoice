package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.consent.ConsentAuthorityRecord
import com.dynokore.gst.access.application.grant.AuthorityGrantRecord
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantedAction
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ConsentTerms
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
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
        )
    private val schema =
        resource("gst/contracts/access/schemas/authority-changed-payload.schema.json").let { text ->
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(text)
        }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "$path is not on the classpath" }.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private fun record(vararg actions: GrantedAction) =
        AuthorityGrantRecord(
            tenant = TenantRef("0a000000-0000-4000-8000-00000000000a"),
            identity = IdentityRef("IDN-1"),
            entity = EntityRef("ENT-1"),
            authorityVersion = 42,
            actions = actions.toList(),
            windowFrom = Instant.parse("2026-10-19T00:00:00Z"),
            windowTo = null,
        )

    private fun action(
        level: ScopeLevel = ScopeLevel.ENTITY,
        periods: Pair<LocalDate, LocalDate>? = null,
        until: Instant? = Instant.parse("2027-09-30T23:59:59Z"),
    ) = GrantedAction(
        "doc.view",
        level,
        "ENT-1",
        Instant.parse("2026-10-19T00:00:00Z"),
        until,
        periods?.first,
        periods?.second,
    )

    private fun errors(record: AuthorityGrantRecord) =
        schema.validate(outbox.payload(record).toString(), com.networknt.schema.InputFormat.JSON)

    @Test
    fun `a grant record, a period grant and an explicit removal validate against the AuthorityChanged schema`() {
        assertTrue(errors(record(action())).isEmpty())
        assertTrue(
            errors(
                record(action(ScopeLevel.ENTITY_PERIOD, LocalDate.of(2027, 1, 1) to LocalDate.of(2027, 6, 30))),
            ).isEmpty(),
        )
        assertTrue(errors(record(action(until = null))).isEmpty())
        assertTrue(errors(record()).isEmpty(), "an empty list is an explicit removal")
    }

    @Test
    fun `the payload carries the one record, the version and the principal`() {
        val node = outbox.payload(record(action()))
        assertEquals("GRANT", node["changeKind"].asString())
        assertEquals("IDN-1", node["principalRef"].asString())
        assertEquals(42L, node["authorityVersion"].asLong())
        assertEquals("ENT-1", node["grantRecord"]["entityRef"].asString())
        assertTrue(!node.has("consentRecord") && !node.has("principalRecord"))
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
    fun `the emission guard accepts the types access produces and rejects an uncatalogued type`() {
        val catalogue = AccessEventCatalogue.load()
        val guard = EmissionGuard(catalogue, AccessEventCatalogue.SERVICE)
        listOf(
            PlatformEventCatalogue.AUTHORITY_CHANGED.eventType,
            "com.dynokore.gst.access.OperatorSessionKeysChanged.v1",
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
        val grants = AuthorityChangedOutbox(writer, mapper)
        grants.publish(listOf(record(action())))
        grants.publish(consent(ConsentStatus.LIVE, ScopeItem.SUBMIT_INVOICE_DATA))
        val directory =
            object : EntityDirectory {
                override fun membership(entity: EntityRef) = null

                override fun entitiesCoveredBy(scope: Scope) = setOf(EntityRef("ENT-1"))
            }
        val tenant = "0a000000-0000-4000-8000-00000000000a"
        val principal =
            ServicePrincipal(
                "PRN-1",
                TenantRef(tenant),
                PrincipalType.INGEST,
                "tenant:$tenant",
                null,
                PrincipalStatus.REVOKED,
                1,
            )
        PrincipalRevocationOutbox(writer, mapper, Clock.systemUTC(), directory).publishRevoked(principal) { 7L }
        assertEquals(3, captured.size)
        captured.forEach { assertEquals("com.dynokore.gst.platform.AuthorityChanged.v1", it.type) }
    }
}
