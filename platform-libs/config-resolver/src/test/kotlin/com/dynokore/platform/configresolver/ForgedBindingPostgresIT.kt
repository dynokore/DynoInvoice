package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * INV-T-5362 (slice 5 of INV-T-4156; INV-REQ-854 b7): the definer functions read the signed binding only. A forged
 * `app.binding_sig` (signed with a key the database does not hold) gets the refusal the unbound case gets, with nothing
 * written; a value signed for one schema does not bind in a look-alike schema under another key. PostgreSQL 16, 17, 18.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForgedBindingPostgresIT {
    private val harnesses = mutableMapOf<String, WriteHarness>()

    @AfterAll
    fun stop() = harnesses.values.forEach { it.close() }

    private fun harness(image: String) = harnesses.getOrPut(image) { WriteHarness(image) }

    private val tenant = UUID.randomUUID().toString()
    private val platform = TenantId.PLATFORM.value.toString()

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `write_installation_setting under a forged binding is refused as the unbound case is`(image: String) {
        val h = harness(image)
        val args = h.staged()
        val before = h.stored()
        val unbound = h.refusal { h.callInstallation(args, tenant = null, actor = null) }
        val forged = h.refusal { h.callInstallation(args, key = SignedBindingFixture.FORGED_KEY) }
        assertEquals("42501", unbound.sqlState)
        assertEquals(unbound, forged)
        assertEquals(before, h.stored(), "a forged binding wrote rows")
        // the same call under the genuine signature is accepted, so the refusal above is the signature's
        assertEquals(1, h.callInstallation(args).size)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the purge functions under a forged binding are refused as the unbound case is`(image: String) {
        val h = harness(image)
        listOf(
            "select svc.${ConfigSchema.PURGE_SUBJECT}('submission', 'doc-1/v1')",
            "select * from svc.${ConfigSchema.PURGE_TENANT}('$tenant')",
            "select svc.${ConfigSchema.OPTION_VALUE_MAXIMUM}('VP-439', 'LONGER')",
        ).forEach { sql ->
            val unbound = h.refusal { h.raw(null, null) { fetchValue(sql) } }
            val forgedTenant = if ("maximum" in sql) platform else tenant
            val forged = h.refusal { h.raw(forgedTenant, "op", SignedBindingFixture.FORGED_KEY) { fetchValue(sql) } }
            assertEquals("42501", unbound.sqlState, sql)
            assertEquals(unbound, forged, sql)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a value signed for one schema does not bind in a look-alike schema`(image: String) {
        val h = harness(image)
        val sql = "select %s.bound_tenant()::text, %s.bound_actor()"
        val own =
            h.raw(
                tenant,
                "op",
            ) { fetchRows(sql.format(WriteHarness.SCHEMA, WriteHarness.SCHEMA)).single().values }
        assertEquals(listOf(tenant, "op"), own.map { it.toString() })
        val other =
            h.raw(tenant, "op") {
                fetchRows(sql.format(WriteHarness.OTHER_SCHEMA, WriteHarness.OTHER_SCHEMA)).single().values
            }
        other.forEach { assertNull(it, "the look-alike schema accepted a value signed under the other key") }
    }
}
