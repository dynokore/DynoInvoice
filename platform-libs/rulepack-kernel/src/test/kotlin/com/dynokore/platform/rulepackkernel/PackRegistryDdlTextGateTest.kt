package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * DDL-text gate of INV-T-5364 (coordinator rulings INV-Q-6586, INV-Q-6663): `PackRegistryMigration.ddl` runs as
 * `<svc>_migrate` in a schema other modules share, so it neither runs tenancy-kernel's binding generator (which is for
 * `<svc>_sys` only) nor switches role. The gate is deny-by-default over the whole text of every output, not a list of
 * known-good statements, and each probe below is text the same check must catch.
 */
class PackRegistryDdlTextGateTest {
    private val forbidden =
        listOf(
            """\bset\s+(local\s+|session\s+)?role\b""",
            """\breset\s+role\b""",
            """\bset\s+session\s+authorization\b""",
            """binding_key""",
            """binding_verified""",
            """create\s+(or\s+replace\s+)?function\s+\S*bound_(tenant|actor)\s*\(""",
        ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private fun violations(text: String): List<String> =
        forbidden.filter { it.containsMatchIn(text) }.map { it.pattern }

    /** The module's outputs: the default roles of the test fixture and a second role pair. */
    private fun outputs(): List<String> =
        listOf(
            packRegistryDdl(),
            PackRegistryMigration.ddl("other_schema", "other_table", runtimeRole = "other_app", sysRole = "other_sys"),
        )

    @Test
    fun `no output of PackRegistryMigration ddl matches a forbidden pattern`() {
        outputs().forEach { assertEquals(emptyList<String>(), violations(it)) }
    }

    @Test
    fun `the generator output for the same schema and roles is caught`() {
        val generator = BindingKeyMigration.ddl("rulepack_test", SYS, RUNTIME)
        assertTrue(violations(generator).isNotEmpty(), "the generator's own output must fail the gate")
    }

    @Test
    fun `a module output with a role switch appended is caught, in plain and in spaced and broken form`() {
        outputs().forEach { out ->
            assertTrue(violations(out + "\nSET ROLE x;").isNotEmpty())
            assertTrue(violations(out + "\nset   local\nrole x;").isNotEmpty())
            assertTrue(violations(out + "\nRESET   ROLE;").isNotEmpty())
            assertTrue(violations(out + "\nSET SESSION AUTHORIZATION x;").isNotEmpty())
            assertTrue(violations(out + "\nCREATE OR REPLACE FUNCTION s.bound_tenant() RETURNS uuid;").isNotEmpty())
        }
    }

    @Test
    fun `a call of the binding function and a search_path are not violations`() {
        assertEquals(emptyList<String>(), violations("(select s.bound_tenant())"))
        assertEquals(emptyList<String>(), violations("SET search_path = pg_catalog, s, pg_temp"))
    }
}
