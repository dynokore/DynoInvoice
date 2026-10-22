package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The REVOCATION payload this service writes validates against the AsyncAPI schema of platform-contracts. */
class PrincipalRevocationPayloadTest {
    private val outbox =
        PrincipalRevocationOutbox(
            outbox = org.mockito.Mockito.mock(com.dynokore.platform.outboxandqueue.OutboxWriter::class.java),
            mapper = JsonMapper.builder().build(),
            clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC),
            directory = org.mockito.Mockito.mock(com.dynokore.gst.access.application.grant.EntityDirectory::class.java),
        )
    private val schema =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "gst/contracts/access/schemas/authority-changed-payload.schema.json",
            ),
        ).use { it.readBytes().toString(Charsets.UTF_8) }
            .let { SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(it) }

    @Test
    fun `a principal revocation validates against the AuthorityChanged schema and carries REVOCATION and REVOKED`() {
        val principal =
            ServicePrincipal(
                "PRN-1",
                TenantRef("0a000000-0000-4000-8000-00000000000a"),
                PrincipalType.INGEST,
                "tenant:0a000000-0000-4000-8000-00000000000a",
                null,
                PrincipalStatus.REVOKED,
                1,
            )
        val node = outbox.payload(principal, 7)
        assertTrue(schema.validate(node.toString(), InputFormat.JSON).isEmpty())
        assertEquals("REVOCATION", node.get("changeKind").asString())
        assertEquals("REVOKED", node.get("principalRecord").get("state").asString())
        assertEquals(7L, node.get("authorityVersion").asLong())
    }
}
