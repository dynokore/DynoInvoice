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
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import com.dynokore.platform.outboxandqueue.EntityRef as WireEntityRef

/**
 * INV-Q-9229 A: every AuthorityChanged event of this service is a platform event, tenant-wide and keyed on the
 * principal: the type, tenant, entity and aggregate key of the context appended, for each scope level and record kind.
 */
class AuthorityChangedEnvelopeTest {
    private val mapper = JsonMapper.builder().build()
    private val tenantValue = "0a000000-0000-4000-8000-00000000000a"
    private val writer = CapturedEvents()
    private val outbox = AuthorityChangedOutbox(writer, mapper, InMemoryAuthorityHead(), InMemoryAuthorityIncarnation())

    private fun change(
        level: ScopeLevel,
        scopeRef: String,
        state: RoleGrantState = RoleGrantState.ACTIVE,
    ) = RoleGrantChange(
        tenant = TenantRef(tenantValue),
        identity = IdentityRef("IDN-1"),
        grantRef = GrantRef("GRT-1"),
        role = Role.TAX_APPROVER,
        state = state,
        scopeLevel = level,
        scopeRef = scopeRef,
        validFrom = Instant.parse("2026-10-19T00:00:00Z"),
        validUntil = null,
        periodFrom = if (level == ScopeLevel.ENTITY_PERIOD) LocalDate.of(2027, 1, 1) else null,
        periodTo = if (level == ScopeLevel.ENTITY_PERIOD) LocalDate.of(2027, 6, 30) else null,
        authorityVersion = 5,
    )

    private fun assertTenantWide(
        appended: CapturedEvents.Appended,
        key: String,
        label: String,
    ) {
        assertEquals(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, appended.event.type, label)
        assertEquals(TenantId.of(tenantValue), appended.context.tenant, label)
        assertEquals(WireEntityRef.TENANT_WIDE, appended.context.entity, label)
        assertEquals(key, appended.context.aggregateKey, label)
        assertNull(appended.context.docUuid, label)
        assertNull(appended.context.transactionDate, label)
        assertNull(appended.context.aggregateVersion, label)
    }

    @Test
    fun `the event type is the platform type and never the access type`() {
        assertEquals(
            "com.dynokore.gst.platform.AuthorityChanged.v1",
            PlatformEventCatalogue.AUTHORITY_CHANGED.eventType,
        )
        outbox.publish(listOf(change(ScopeLevel.TENANT, tenantValue)))
        assertTrue(
            writer.appended
                .single()
                .event.type != "com.dynokore.gst.access.AuthorityChanged.v1",
        )
    }

    @Test
    fun `each scope level of a role grant event is tenant-wide and keyed on the principal`() {
        val refs =
            mapOf(
                ScopeLevel.TENANT to tenantValue,
                ScopeLevel.GROUP to "GRP-1",
                ScopeLevel.ENTITY to "ENT-1",
                ScopeLevel.ENTITY_PERIOD to "ENT-1",
                ScopeLevel.CONSENT to tenantValue,
            )
        assertEquals(ScopeLevel.entries.toSet(), refs.keys)
        refs.forEach { (level, ref) ->
            RoleGrantState.entries.forEach { state ->
                writer.appended.clear()
                outbox.publish(listOf(change(level, ref, state)))
                assertEquals(1, writer.appended.size, "$level $state")
                assertTenantWide(writer.appended.single(), "IDN-1", "$level $state")
            }
        }
    }

    @Test
    fun `a consent record is tenant-wide and keyed on the agent, and the client entity stays in the record`() {
        val consent =
            Consent(
                ConsentRef("CNS-1"),
                EntityRef("CLI-1"),
                IdentityRef("AGT-1"),
                ConsentTerms(
                    setOf(ScopeItem.SUBMIT_INVOICE_DATA),
                    LocalDate.of(2026, 11, 1),
                    "signatory@client.example",
                ),
                ConsentStatus.LIVE,
                null,
                0,
            )
        outbox.publish(ConsentAuthorityRecord(TenantRef(tenantValue), consent, 9))
        val appended = writer.appended.single()
        assertTenantWide(appended, "AGT-1", "consent")
        assertTrue(appended.event.dataJson.contains("\"clientEntityRef\":\"CLI-1\""))
    }

    @Test
    fun `a principal record is tenant-wide and keyed on the principal, one event whatever the scope`() {
        listOf("tenant:$tenantValue", "entity:ENT-1", "consent:$tenantValue").forEach { scope ->
            writer.appended.clear()
            val principal =
                ServicePrincipal(
                    "PRN-1",
                    TenantRef(tenantValue),
                    PrincipalType.INGEST,
                    scope,
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
            assertEquals(1, writer.appended.size, scope)
            assertTenantWide(writer.appended.single(), "PRN-1", scope)
            assertTrue(
                writer.appended
                    .single()
                    .event.dataJson
                    .contains("\"authorityVersion\":7"),
                scope,
            )
        }
    }

    @Test
    fun `the JSON form of the envelope has tenantid and no entityref`() {
        outbox.publish(listOf(change(ScopeLevel.GROUP, "GRP-1")))
        val json = mapper.readTree(EnvelopeJson(mapper).write(writer.appended.single().envelope))
        assertEquals(tenantValue, json["tenantid"].asString())
        assertFalse(json.has("entityref"))
        assertEquals("IDN-1", json["subject"].asString())
        val read: Envelope = writer.appended.single().delivered()
        assertEquals(WireEntityRef.TENANT_WIDE, read.entity)
    }

    @Test
    fun `the library refuses the old domain type with the tenant-wide entity`() {
        val appended =
            CapturedEvents.Appended(
                com.dynokore.platform.outboxandqueue
                    .OutboundEvent("com.dynokore.gst.platform.AuthorityChanged.v1", "{}"),
                EventContexts.forAggregate(TenantId.of(tenantValue), WireEntityRef.TENANT_WIDE, "IDN-1"),
            )
        assertEquals(WireEntityRef.TENANT_WIDE, appended.envelope.entity)
        assertThrows(IllegalArgumentException::class.java) {
            CapturedEvents.Appended(
                com.dynokore.platform.outboxandqueue
                    .OutboundEvent("com.dynokore.gst.access.AuthorityChanged.v1", "{}"),
                EventContexts.forAggregate(TenantId.of(tenantValue), WireEntityRef.TENANT_WIDE, "IDN-1"),
            )
        }
    }

    @Test
    fun `the channel constant names the platform channel`() {
        assertEquals("platformAuthorityChanged", AuthorityChangedOutbox.CHANNEL)
    }
}
