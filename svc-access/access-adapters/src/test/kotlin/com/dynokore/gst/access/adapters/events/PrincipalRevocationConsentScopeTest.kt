package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.platform.outboxandqueue.OutboxWriter
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** The revocation outbox reads no scope from `consent:<tenant>` (INV-Q-8868 A: only grantRole takes it). */
class PrincipalRevocationConsentScopeTest {
    private val tenant = TenantRef("0a000000-0000-4000-8000-00000000000a")

    @Test
    fun `a stored principal with a consent scope is unreadable and publishes nothing`() {
        val writer = mock(OutboxWriter::class.java)
        val outbox =
            PrincipalRevocationOutbox(
                outbox = writer,
                mapper = JsonMapper.builder().build(),
                clock = mock(Clock::class.java),
                directory = mock(EntityDirectory::class.java),
            )
        val scope = "consent:${tenant.value}"
        val principal = ServicePrincipal("PRN-1", tenant, PrincipalType.INGEST, scope, null, PrincipalStatus.REVOKED, 1)
        assertThrows(IllegalStateException::class.java) { outbox.publishRevoked(principal) { 1L } }
        verifyNoInteractions(writer)
    }
}
