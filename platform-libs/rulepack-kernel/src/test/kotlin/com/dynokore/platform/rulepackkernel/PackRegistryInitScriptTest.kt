package com.dynokore.platform.rulepackkernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The container init script of the pack-registry ITs (route R1 of INV-Q-5245 and INV-Q-5119) is the roles script, then
 * the files of `db/pack-registry-it/base` in version order with `packRegistryDdl` replaced by the generated DDL, run
 * under `SET ROLE` to the migrate login. No database needed.
 */
class PackRegistryInitScriptTest {
    private fun resource(path: String) =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }
            .use { it.readBytes().decodeToString() }

    private fun expected(): String {
        val ddl =
            PackRegistryMigration.ddl(
                schema = "rulepack_test",
                table = "rule_pack_version",
                runtimeRole = "rulepack_kernel_app",
                sysRole = "rulepack_kernel_sys",
            )
        val migrations =
            listOf(
                "V1__rule_pack_version.sql",
                "V2__rulepack_kernel_app.sql",
                "V3__registered_by_check_probe.sql",
            ).joinToString("\n") { resource("db/pack-registry-it/base/$it").replace("\${packRegistryDdl}", ddl) }
        return resource("db/rulepack-kernel-test-roles.sql") +
            "\nSET ROLE rulepack_kernel_migrate;\n" + migrations + "\nRESET ROLE;\n"
    }

    @Test
    fun `the init script text equals the concatenation of roles, SET ROLE and migrations`() {
        val script = packRegistryInitScript()
        assertEquals(expected(), script)
        assertFalse("\${packRegistryDdl}" in script)
    }

    @Test
    fun `one changed character no longer equals the concatenation`() {
        val script = packRegistryInitScript()
        val changed = script.replaceFirst("rulepack_test", "rulepack_tesu")
        assertNotEquals(expected(), changed)
    }
}
