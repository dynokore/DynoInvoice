package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertTrue(ddl.contains("tenant_id = (select submission.bound_tenant())"))
        assertFalse(ddl.contains("current_setting"), "the policy reads the signed binding only")
        assertEquals(2, ddl.split("(select submission.bound_tenant())").size - 1, "USING and WITH CHECK")
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

    @Test
    fun `with no entity column the text is exactly the text of platform-libs 5dfc6a5f`() {
        val expected =
            """
            |ALTER TABLE submission.filing_fact ENABLE ROW LEVEL SECURITY;
            |ALTER TABLE submission.filing_fact FORCE ROW LEVEL SECURITY;
            |CREATE POLICY tenant_isolation ON submission.filing_fact
            |    USING      (tenant_id = (select submission.bound_tenant()))
            |    WITH CHECK (tenant_id = (select submission.bound_tenant()));
            """.trimMargin()
        assertEquals(expected, TenantRlsMigration.ddl(schema = "submission", table = "filing_fact"))
        assertEquals(expected, TenantRlsMigration.ddl("submission", "filing_fact", "tenant_isolation", null))
    }

    @Test
    fun `an entity column adds the entity predicate to USING and WITH CHECK beside the tenant predicate`() {
        val ddl = TenantRlsMigration.ddl("submission", "filing_fact", entityColumn = "entity_id")
        val predicate =
            "tenant_id = (select submission.bound_tenant()) " +
                "AND entity_id = ANY ((select submission.bound_entities())::uuid[])"
        assertTrue(ddl.contains("    USING      ($predicate)\n"), ddl)
        assertTrue(ddl.contains("    WITH CHECK ($predicate);"), ddl)
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0}")
    @org.junit.jupiter.params.provider.ValueSource(
        strings = ["Entity", "entity id", "entity;drop", "", "e\"", "1e", "entity_id, x", "a.b"],
    )
    fun `an entity column that is not a plain lower-case identifier is refused`(bad: String) {
        assertThrows(IllegalArgumentException::class.java) {
            TenantRlsMigration.ddl("submission", "filing_fact", entityColumn = bad)
        }
    }
}
