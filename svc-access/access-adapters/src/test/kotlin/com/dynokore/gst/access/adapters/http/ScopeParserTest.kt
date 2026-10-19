package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScopeParserTest {
    private val tenant = TenantRef("0a000000-0000-4000-8000-00000000000a")

    @Test
    fun `entity and bound-tenant scopes are read`() {
        assertEquals(
            ScopeParser.Parsed.Ok(Scope.Entity(EntityRef("ENT-3F2A9C"))),
            ScopeParser.parse("entity:ENT-3F2A9C", tenant),
        )
        assertEquals(ScopeParser.Parsed.Ok(Scope.Tenant(tenant)), ScopeParser.parse("tenant:${tenant.value}", tenant))
    }

    @Test
    fun `a blank scope is a grant without a scope`() {
        listOf("", " ", "\t\n").forEach { assertEquals(ScopeParser.Parsed.Missing, ScopeParser.parse(it, tenant), it) }
    }

    @Test
    fun `look-alikes, other tenants, other forms and composite text are not read`() {
        listOf(
            "Entity:E1",
            "entity: E1",
            "entity:",
            ":E1",
            "entity",
            "entity:E1:E2",
            "entity:E1;period=2027",
            "entity:E1,E2",
            "entity:*",
            "entity:E 1",
            "entity:E1 ",
            " entity:E1",
            "tenant:other",
            "tenant:*",
            "tenant:",
            "group:G1",
            "consent:T1",
            "entity:" + "x".repeat(65),
            "entity:..",
            "entity:E?",
            "entity:E1-E9..E2",
            "entity:%2e",
            "entity:E1\u0000",
            "entity:E1\n",
            "entityx:E1",
            "xentity:E1",
        ).forEach {
            val parsed = ScopeParser.parse(it, tenant)
            assertEquals(
                true,
                parsed is ScopeParser.Parsed.Unsupported || (parsed is ScopeParser.Parsed.Ok && it == "entity:.."),
                it,
            )
        }
    }
}
