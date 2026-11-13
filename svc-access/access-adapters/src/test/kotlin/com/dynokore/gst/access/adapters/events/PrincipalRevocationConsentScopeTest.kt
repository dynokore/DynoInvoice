package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * The revocation outbox reads no scope: it publishes the one principalRecord event for a stored principal whatever its
 * scope, including `consent:<tenant>` (INV-Q-8868 A: only grantRole takes it), because no entity is expanded any more.
 */
class PrincipalRevocationConsentScopeTest {
    private val tenant = TenantRef("0a000000-0000-4000-8000-00000000000a")

    @Test
    fun `a stored principal with a consent scope publishes the one principal record event`() {
        val writer = CapturedEvents()
        val outbox =
            PrincipalRevocationOutbox(
                writer,
                JsonMapper.builder().build(),
                Clock.systemUTC(),
                InMemoryAuthorityHead(),
                InMemoryAuthorityIncarnation(),
            )
        val scope = "consent:${tenant.value}"
        val principal = ServicePrincipal("PRN-1", tenant, PrincipalType.INGEST, scope, null, PrincipalStatus.REVOKED, 1)
        outbox.publishRevoked(principal) { 1L }
        assertEquals(1, writer.appended.size)
        assertEquals(
            "PRN-1",
            writer.appended
                .single()
                .context.aggregateKey,
        )
    }
}
