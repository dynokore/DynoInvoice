package com.dynokore.platform.evidencestore

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EvidenceLedgerSchemaTest {
    @Test
    fun `emits the RLS trailer, the append-only REVOKE and the immutability trigger`() {
        val ddl =
            EvidenceLedgerSchema.trailerDdl(schema = "evidence_svc", table = "audit_log", appRole = "evidence_svc_app")

        // TenantRlsMigration's own DDL, reused rather than duplicated (CODER-BRIEF).
        assertTrue(ddl.contains("ALTER TABLE evidence_svc.audit_log ENABLE ROW LEVEL SECURITY;"))
        assertTrue(ddl.contains("CREATE POLICY tenant_isolation ON evidence_svc.audit_log"))

        // CS-22: DB grants and a trigger, not one or the other.
        assertTrue(ddl.contains("REVOKE UPDATE, DELETE, TRUNCATE ON evidence_svc.audit_log FROM evidence_svc_app;"))
        assertTrue(ddl.contains("CREATE OR REPLACE FUNCTION evidence_svc.audit_log_immutable()"))
        assertTrue(ddl.contains("RAISE EXCEPTION"))
        assertTrue(ddl.contains("CREATE TRIGGER audit_log_no_mutation"))
        assertTrue(ddl.contains("BEFORE UPDATE OR DELETE ON evidence_svc.audit_log"))
    }

    @Test
    fun `refuses a schema, table or role name that is not a plain identifier`() {
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceLedgerSchema.trailerDdl(schema = "ev; DROP TABLE x --", table = "audit_log", appRole = "app")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceLedgerSchema.trailerDdl(schema = "evidence_svc", table = "Audit Log", appRole = "app")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceLedgerSchema.trailerDdl(schema = "evidence_svc", table = "audit_log", appRole = "App Role")
        }
    }
}
