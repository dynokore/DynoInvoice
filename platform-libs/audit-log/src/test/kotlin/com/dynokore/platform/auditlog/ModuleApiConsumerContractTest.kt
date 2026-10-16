package com.dynokore.platform.auditlog

import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * Consumer-driven contract test of the module API (done-when: contract tests pass): fake owning-service
 * commands written against only `-api` types ([OverrideService], [OverrideRegister], [AuditedOperations])
 * run against the real implementations on PostgreSQL 17 and 18. `submission-svc`, `access-svc` and
 * `control-plane-svc` do not exist yet: the fakes below play their roles (INV-AC-2166, -2209, -2167, -2593).
 */
@Tag("integration")
class ModuleApiConsumerContractTest {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a committed override has exactly one audit entry and a rolled-back one has none`(image: String) {
        AuditHarness(image).use { h ->
            val tenant = UUID.randomUUID()
            val committed = UUID.randomUUID()
            val rolledBack = UUID.randomUUID()

            h.asTenant(tenant) { h.overrides.override(h.overrideCommand(tenant, committed)) }
            // The second override's process dies after its block, before the commit (a forced rollback).
            assertThrows(Exception::class.java) {
                h.asTenant(tenant) {
                    h.boundary.inTransaction {
                        h.overrides.override(h.overrideCommand(tenant, rolledBack))
                        h.boundary.armKill()
                    }
                }
            }

            val only = listOf(tenant)
            assertEquals(1, h.auditEntries(only, committed.toString()))
            assertEquals(
                1,
                h.count("select count(*) from submission.override_grant where doc_uuid = ?", only, committed),
            )
            assertEquals(0, h.auditEntries(only, rolledBack.toString()))
            assertEquals(
                0,
                h.count("select count(*) from submission.override_grant where doc_uuid = ?", only, rolledBack),
            )

            val expected =
                """{"actor":"user-7","action":"override","reason":"UEN confirmed with the supplier",""" +
                    """"scope":"document:$committed/version:1/code:MDM-003","priorState":{"status":"BLOCKED"}}"""
            val matching =
                h.count(
                    "select count(*) from submission.outbox where subject = ? and data @> ?::jsonb",
                    only,
                    committed.toString(),
                    expected,
                )
            assertEquals(1, matching, "entry holds actor, action, scope, reason and prior state")
            assertTrue(h.overridden(tenant, committed, 1))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a TERMINAL override is refused for every role and writes nothing`(image: String) {
        AuditHarness(image).use { h ->
            val tenant = UUID.randomUUID()
            val everyRole = setOf("tax.approver", "tenant.admin")
            val command = h.overrideCommand(tenant, UUID.randomUUID(), code = AuditHarness.SUB_009, roles = everyRole)
            val refused =
                assertThrows(OverrideRefused::class.java) { h.asTenant(tenant) { h.overrides.override(command) } }
            assertEquals(RefusalReason.TERMINAL, refused.reason)
            assertEquals(0, h.count("select count(*) from submission.override_grant", listOf(tenant)))
            assertEquals(0, h.count("select count(*) from submission.outbox", listOf(tenant)))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an override needs a reason and an authorised role and applies to one document version only`(image: String) {
        AuditHarness(image).use { h ->
            val tenant = UUID.randomUUID()
            val doc = UUID.randomUUID()

            fun refusal(command: OverrideCommand): RefusalReason {
                val refused =
                    assertThrows(OverrideRefused::class.java) { h.asTenant(tenant) { h.overrides.override(command) } }
                return refused.reason
            }

            assertEquals(RefusalReason.REASON_REQUIRED, refusal(h.overrideCommand(tenant, doc, reason = null)))
            assertEquals(RefusalReason.REASON_REQUIRED, refusal(h.overrideCommand(tenant, doc, reason = "  ")))
            val admin = setOf("tenant.admin")
            assertEquals(RefusalReason.ROLE_NOT_AUTHORISED, refusal(h.overrideCommand(tenant, doc, roles = admin)))
            val manual = h.overrideCommand(tenant, doc, code = AuditHarness.PAY_001)
            assertEquals(RefusalReason.NOT_OVERRIDABLE, refusal(manual))
            assertEquals(RefusalReason.UNKNOWN_CODE, refusal(h.overrideCommand(tenant, doc, code = "XXX-999")))
            assertEquals(0, h.count("select count(*) from submission.outbox", listOf(tenant)))

            h.asTenant(tenant) { h.overrides.override(h.overrideCommand(tenant, doc, version = 1)) }
            assertEquals(RefusalReason.ALREADY_OVERRIDDEN, refusal(h.overrideCommand(tenant, doc, version = 1)))
            assertEquals(1, h.auditEntries(listOf(tenant)), "the repeated override left no second entry")

            assertTrue(h.overridden(tenant, doc, 1))
            assertFalse(h.overridden(tenant, doc, 2), "version 2 is blocked afresh")
            assertFalse(h.overridden(tenant, doc, 1, code = "MDM-004"), "another code is not overridden")
        }
    }

    /** Fake `access-svc` command `mapIdpGroup` (tenant.admin) and read `exportSupportBundle` (deployment.operator). */
    private class FakeAccessService(
        private val h: AuditHarness,
    ) {
        fun mapIdpGroup(
            tenant: UUID,
            group: String,
            role: String,
        ) = h.asTenant(tenant) {
            val entry = AuditEntry("tenant-admin-1", "mapIdpGroup", "idp-group:$group", "onboarding")
            h.operations.change(entry, h.context(tenant)) {
                h.dsl
                    .insertInto(MAPPING)
                    .columns(IDP_GROUP, ROLE)
                    .values(group, role)
                    .onConflict(IDP_GROUP)
                    .doUpdate()
                    .set(ROLE, DSL.excluded(ROLE))
                    .execute()
            }
        }

        fun exportSupportBundle(tenant: UUID): UUID =
            h.asTenant(tenant) {
                val entry = AuditEntry("operator-1", "exportSupportBundle", "tenant:$tenant")
                h.operations.read(entry, h.context(tenant)) {
                    // The bundle is assembled from existing data; it reads the tenant's rows and returns an id.
                    h.dsl
                        .selectCount()
                        .from(BUNDLE)
                        .fetchOne()
                    UUID.randomUUID()
                }
            }

        private companion object {
            val MAPPING: Table<*> = DSL.table(DSL.name("submission", "idp_group_mapping"))
            val BUNDLE: Table<*> = DSL.table(DSL.name("submission", "support_bundle"))
            val IDP_GROUP = DSL.field(DSL.name("idp_group"), SQLDataType.CLOB)
            val ROLE = DSL.field(DSL.name("role"), SQLDataType.CLOB)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `when the audit entry cannot be written a change commits nothing and a read returns no result`(image: String) {
        AuditHarness(image).use { h ->
            val tenant = UUID.randomUUID()
            val service = FakeAccessService(h)
            h.seedMapping("finance", "viewer")
            service.mapIdpGroup(tenant, "ops", "viewer")
            assertEquals(1, h.auditEntries(listOf(tenant)), "baseline: with a working outbox the entry is written")

            // The outbox table refuses inserts (INV-AC-2593).
            h.refuseOutbox()

            val change =
                assertThrows(AuditWriteFailed::class.java) { service.mapIdpGroup(tenant, "finance", "approver") }
            assertNotNull(change.cause)
            val unchanged =
                "select count(*) from submission.idp_group_mapping where idp_group = 'finance' and role = 'viewer'"
            assertEquals(1, h.count(unchanged, listOf(tenant)), "mapping unchanged")
            var result: UUID? = null
            assertThrows(AuditWriteFailed::class.java) { result = service.exportSupportBundle(tenant) }
            assertNull(result)
            assertEquals(1, h.auditEntries(listOf(tenant)), "no entry for either failed call")
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a read returns its result once its entry is written`(image: String) {
        AuditHarness(image).use { h ->
            val tenant = UUID.randomUUID()
            val bundle = FakeAccessService(h).exportSupportBundle(tenant)
            assertTrue(bundle.toString().isNotBlank())
            assertEquals(1, h.auditEntries(listOf(tenant)))
            val exported = "select count(*) from submission.outbox where data ->> 'action' = 'exportSupportBundle'"
            assertEquals(1, h.count(exported, listOf(tenant)))
        }
    }
}
