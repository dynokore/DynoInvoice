package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.grant.TenantRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `consent:<tenantRef>` (INV-Q-8868 A): read for the bound tenant only, with a check that never allows. */
class ScopeParserConsentTest {
    private val tenant = TenantRef("0a000000-0000-4000-8000-00000000000a")

    @Test
    fun `consent followed by the bound tenant is a consent-derived scope whose check answers false`() {
        val parsed = ScopeParser.parse("consent:${tenant.value}", tenant) as ScopeParser.Parsed.Ok
        val scope = parsed.scope as Scope.ConsentDerived
        assertEquals(ScopeLevel.CONSENT, scope.level)
        assertEquals(tenant, scope.tenant)
        assertFalse(scope.consent.isLive(EntityRef("E1")))
    }

    @Test
    fun `other texts are not read`() {
        val bound = tenant.value
        listOf(
            "consent:T1",
            "consent:0a000000-0000-4000-8000-00000000000b",
            "Consent:$bound",
            "CONSENT:$bound",
            "consent:",
            "consent: $bound",
            "consent:$bound ",
            " consent:$bound",
            "consent:*",
            "consent:$bound,$bound",
            "consent:$bound;entity=E1",
            "consent:$bound:x",
            "consent:" + "x".repeat(65),
            "consentx:$bound",
            "xconsent:$bound",
        ).forEach { assertTrue(ScopeParser.parse(it, tenant) is ScopeParser.Parsed.Unsupported, it) }
    }

    @Test
    fun `a blank text is still missing`() {
        assertEquals(ScopeParser.Parsed.Missing, ScopeParser.parse(" ", tenant))
    }
}
