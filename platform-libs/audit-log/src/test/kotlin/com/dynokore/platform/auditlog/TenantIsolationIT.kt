package com.dynokore.platform.auditlog

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * RLS on `override_grant` (tenancy-kernel's `TenantRlsMigration`) and the immutability of a grant:
 * PostgreSQL 17 and 18.
 */
@Tag("integration")
class TenantIsolationIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `RLS is enabled and forced and another tenant sees no override`(image: String) {
        AuditHarness(image).use { h ->
            val owner = UUID.randomUUID()
            h.asTenant(owner) { h.overrides.override(h.overrideCommand(owner, UUID.randomUUID())) }

            assertEquals(
                listOf(true, true),
                h.db.app
                    .fetchRows(
                        "select relrowsecurity, relforcerowsecurity from pg_class " +
                            "where oid = 'submission.override_grant'::regclass",
                    ).single()
                    .values
                    .toList(),
            )

            // RLS alone decides here: the probe carries no tenant predicate of its own.
            fun visible(tenant: UUID) =
                h.db.app.inTenant(TenantId(tenant), "audit-test") {
                    fetchValue("select count(*) from submission.override_grant") as Long
                }
            assertEquals(1, visible(owner))
            assertEquals(0, visible(UUID.randomUUID()))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a row cannot be written for another tenant and a grant can be neither updated nor deleted`(image: String) {
        AuditHarness(image).use { h ->
            val tenant = UUID.randomUUID()
            h.asTenant(tenant) { h.overrides.override(h.overrideCommand(tenant, UUID.randomUUID())) }

            fun run(sql: String) = h.db.app.inTenant(TenantId(tenant), "audit-test") { fetchRows(sql) }
            assertThrows(DataAccessException::class.java) {
                run("update submission.override_grant set reason = 'changed'")
            }
            assertThrows(DataAccessException::class.java) { run("delete from submission.override_grant") }
            // The command names tenant B but the session is bound to A: the policy's WITH CHECK refuses the row.
            val other = UUID.randomUUID()
            assertThrows(Exception::class.java) {
                h.asTenant(tenant) { h.overrides.override(h.overrideCommand(other, UUID.randomUUID())) }
            }
            assertEquals(1, h.count("select count(*) from submission.override_grant", listOf(tenant, other)))
        }
    }
}
