package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.InstallationRoleGrantRow
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime

/**
 * INV-T-9235: the payload of an INSTALLATION role grant validates against the `authority-changed-payload` schema of the
 * pinned bundle, and carries no `scopeRef`. The pinned schema holds "absent exactly when INSTALLATION" as prose only,
 * so the absence is asserted on the payload itself.
 */
class InstallationGrantPayloadTest {
    private val mapper = JsonMapper.builder().build()
    private val payloads = AuthorityChangedPayloads(mapper, InMemoryAuthorityIncarnation())
    private val schema =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "gst/contracts/service-runtime/schemas/authority-changed-payload.schema.json",
            ),
        ) { "the schema is not on the classpath" }.use {
            SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(it.readBytes().toString(Charsets.UTF_8))
        }

    private fun row(active: Boolean) =
        InstallationRoleGrantRow(
            InstallationGrantRef.of("deployment.operator", "user:op-1"),
            "user:op-1",
            "deployment.operator",
            active,
            42,
            OffsetDateTime.parse("2026-11-09T01:02:03Z"),
        )

    @Test
    fun `INV-AC-9212 an ACTIVE and a REMOVED installation grant validate against the schema with no scopeRef`() {
        listOf(true, false).forEach { active ->
            val node = payloads.installationGrant(row(active))
            assertEquals(
                emptyList<Any>(),
                schema.validate(node.toString(), com.networknt.schema.InputFormat.JSON).toList(),
            )
            val record = node["roleGrantRecord"]
            assertEquals("INSTALLATION", record["scopeLevel"].asString())
            assertEquals(if (active) "ACTIVE" else "REMOVED", record["state"].asString())
            assertFalse(record.has("scopeRef"))
            assertEquals("user:op-1", node["principalRef"].asString())
            assertEquals(42, node["authorityVersion"].asLong())
        }
    }
}
