package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TenantRlsMigrationTest {
    @Test
    fun `emits ENABLE, FORCE and the exact CS-19 policy predicate`() {
        val ddl = TenantRlsMigration.ddl(schema = "submission", table = "filing_fact")

        assertTrue(ddl.contains("ALTER TABLE submission.filing_fact ENABLE ROW LEVEL SECURITY;"))
        assertTrue(ddl.contains("ALTER TABLE submission.filing_fact FORCE ROW LEVEL SECURITY;"))
        assertTrue(ddl.contains("CREATE POLICY tenant_isolation ON submission.filing_fact"))
        assertTrue(ddl.contains("tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid"))
        // Both USING and WITH CHECK carry the predicate (CS-19): a policy with only USING still lets
        // a write insert a foreign tenant_id, checked only on the next read.
        assertTrue(ddl.contains("USING"))
        assertTrue(ddl.contains("WITH CHECK"))
    }

    @Test
    fun `a custom policy name is honoured`() {
        val ddl =
            TenantRlsMigration.ddl(
                schema = "tenancy_kernel_test",
                table = "probe",
                policyName = "probe_tenant_isolation",
            )
        assertTrue(ddl.contains("CREATE POLICY probe_tenant_isolation ON tenancy_kernel_test.probe"))
    }

    @Test
    fun `refuses a schema name that is not a plain identifier`() {
        assertThrows(IllegalArgumentException::class.java) {
            TenantRlsMigration.ddl(schema = "sub; DROP TABLE x --", table = "filing_fact")
        }
    }

    @Test
    fun `refuses a table name that is not a plain identifier`() {
        assertThrows(IllegalArgumentException::class.java) {
            TenantRlsMigration.ddl(schema = "submission", table = "Filing Fact")
        }
    }
}
