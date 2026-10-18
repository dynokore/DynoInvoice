package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.ConfigSchema.EventMode
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * INV-T-5362, INV-Q-6663: the module's DDL neither installs the binding functions nor switches role, and no definer
 * function reads the transaction settings of the binding (INV-REQ-854 b7). Fails closed: [FORBIDDEN] is matched over
 * the whole text, case-insensitively, and the probes show the check catches each form it must.
 */
class ConfigSchemaBindingDdlTest {
    private fun matches(text: String) = FORBIDDEN.filter { it.containsMatchIn(text) }

    @ParameterizedTest
    @EnumSource(EventMode::class)
    fun `the DDL holds no role switch, key table or function creation`(mode: EventMode) {
        val ddl = ConfigSchema.ddl("svc", "config_app", "config_sys", mode)
        assertEquals(emptyList<Regex>(), matches(ddl))
    }

    @ParameterizedTest
    @EnumSource(EventMode::class)
    fun `each probe is caught by the same check`(mode: EventMode) {
        val ddl = ConfigSchema.ddl("svc", "config_app", "config_sys", mode)
        val probes =
            mapOf(
                "the generator's output" to BindingKeyMigration.ddl("svc", "config_sys", "config_app"),
                "a SET ROLE line" to "$ddl\nSET ROLE x;",
                "set local and a line break" to "$ddl\nset   local\nrole x;",
                "RESET ROLE" to "$ddl\nreset role;",
                "session authorization" to "$ddl\nSET SESSION AUTHORIZATION x;",
                "a function creation, upper case" to "$ddl\nCREATE OR REPLACE FUNCTION s.BOUND_TENANT() returns uuid",
            )
        probes.forEach { (name, text) -> assertTrue(matches(text).isNotEmpty(), "not caught: $name") }
    }

    @ParameterizedTest
    @EnumSource(EventMode::class)
    fun `look-alikes that are not creations pass`(mode: EventMode) {
        val text =
            ConfigSchema.ddl("svc", "config_app", "config_sys", mode) +
                "\n(select s.bound_tenant())\nSET search_path = pg_catalog, s, pg_temp"
        assertEquals(emptyList<Regex>(), matches(text))
    }

    private companion object {
        val FORBIDDEN =
            listOf(
                """\bset\s+(local\s+|session\s+)?role\b""",
                """\breset\s+role\b""",
                """\bset\s+session\s+authorization\b""",
                """binding_key""",
                """binding_verified""",
                """create\s+(or\s+replace\s+)?function\s+\S*bound_(tenant|actor)\s*\(""",
            ).map { Regex(it, RegexOption.IGNORE_CASE) }
    }
}
