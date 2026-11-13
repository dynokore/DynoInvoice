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
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import com.dynokore.platform.outboxandqueue.EntityRef as OutboxEntityRef

/**
 * INV-T-9060 (INV-Q-17072 A): each of the three append sites of AuthorityChanged advances the head record with the
 * authorityVersion it appends; an event of another type does not; the heartbeat payload validates against the schema.
 */
class AuthorityHeadSitesTest {
    private val mapper = JsonMapper.builder().build()
    private val writer = CapturedEvents()
    private val head = InMemoryAuthorityHead()
    private val tenantValue = "0a000000-0000-4000-8000-00000000000a"

    private fun grant(version: Long) =
        RoleGrantChange(
            tenant = TenantRef(tenantValue),
            identity = IdentityRef("IDN-1"),
            grantRef = GrantRef("GRT-1"),
            role = Role.TAX_APPROVER,
            state = RoleGrantState.ACTIVE,
            scopeLevel = ScopeLevel.ENTITY,
            scopeRef = "ENT-1",
            validFrom = Instant.parse("2026-10-19T00:00:00Z"),
            validUntil = null,
            periodFrom = null,
            periodTo = null,
            authorityVersion = version,
        )

    @Test
    fun `INV-AC-9060 a grant append advances the head record with its authorityVersion`() {
        AuthorityChangedOutbox(writer, mapper, head).publish(listOf(grant(21), grant(22)))
        assertEquals(listOf(21L, 22L), head.advanced)
        assertEquals(22L, head.current())
    }

    @Test
    fun `INV-AC-9060 a consent change append advances the head record with its authorityVersion`() {
        val consent =
            Consent(
                ConsentRef("CNS-1"),
                EntityRef("CLI-1"),
                IdentityRef("AGT-1"),
                ConsentTerms(setOf(ScopeItem.SUBMIT_INVOICE_DATA), LocalDate.of(2026, 11, 1), "s@client.example"),
                ConsentStatus.LIVE,
                null,
                0,
            )
        AuthorityChangedOutbox(
            writer,
            mapper,
            head,
        ).publish(ConsentAuthorityRecord(TenantRef(tenantValue), consent, 31))
        assertEquals(listOf(31L), head.advanced)
    }

    @Test
    fun `INV-AC-9060 a principal revocation append advances the head record with its authorityVersion`() {
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
        PrincipalRevocationOutbox(writer, mapper, Clock.systemUTC(), head).publishRevoked(principal) { 41L }
        assertEquals(listOf(41L), head.advanced)
    }

    @Test
    fun `INV-AC-9060 an event of another type appended through the writer does not advance the head record`() {
        writer.append(
            listOf(OutboundEvent("com.dynokore.gst.platform.AuditEntryRecorded.v1", "{}")),
            EventContexts.forAggregate(TenantId.PLATFORM, OutboxEntityRef.PLATFORM, "x"),
        )
        assertTrue(head.advanced.isEmpty())
        assertEquals(null, head.current())
    }

    @Test
    fun `INV-AC-9060 the heartbeat payload validates against the contract schema and carries the version`() {
        val outbox =
            AuthorityHeadOutbox(
                org.mockito.Mockito.mock(com.dynokore.platform.tenancykernel.PlatformTenantBinding::class.java),
                org.mockito.Mockito.mock(com.dynokore.platform.tenancykernel.TransactionRunner::class.java),
                org.mockito.Mockito.mock(org.jooq.DSLContext::class.java),
                head,
                writer,
                mapper,
                Clock.systemUTC(),
            )
        val text =
            checkNotNull(
                javaClass.classLoader.getResourceAsStream(
                    "gst/contracts/access/schemas/authority-head-observed-payload.schema.json",
                ),
            ) {
                "schema missing"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(text)
        val node = outbox.payload(13)
        assertTrue(schema.validate(node.toString(), InputFormat.JSON).isEmpty())
        assertEquals(13L, node["authorityVersion"].asLong())
        assertTrue(!node.has("tenantId") && !node.has("entityRef"))
    }
}
