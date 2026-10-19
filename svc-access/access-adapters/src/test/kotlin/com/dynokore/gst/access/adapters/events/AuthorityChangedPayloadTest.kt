package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.grant.AuthorityGrantRecord
import com.dynokore.gst.access.application.grant.GrantedAction
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.outboxandqueue.EmissionGuard
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.productruntime.ProductEventTypes
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate

/**
 * A test per published channel (INV-T-4934): the payload this service writes validates against the AsyncAPI schema of its
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
            ProductEventTypes.AUTHORITY_CHANGED,
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
            EmissionGuard(catalogue, "tenancy-svc").require(ProductEventTypes.AUTHORITY_CHANGED)
        }
    }
}
