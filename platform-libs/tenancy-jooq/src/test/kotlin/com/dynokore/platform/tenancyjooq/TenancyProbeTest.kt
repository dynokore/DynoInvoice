package com.dynokore.platform.tenancyjooq

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import java.io.File
import java.util.zip.ZipFile

/** TenancyProbe runs only as the runtime login and never ships in the main jar (coordinator ruling INV-Q-4140). */
class TenancyProbeTest {
    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `the probe refuses a superuser session`(image: String) {
        val failure =
            assertThrows<IllegalStateException> { Databases.of(image).superuserProbe().fetchValue("select 1") }
        assertTrue("refuses a superuser or BYPASSRLS session" in failure.message.orEmpty())
    }

    @ParameterizedTest
    @FieldSource("com.dynokore.platform.tenancyjooq.Images#ALL")
    fun `the probe runs as the runtime login, bound or unbound`(image: String) {
        val probe = Databases.of(image).probe
        assertEquals("tenancy_kernel_app", probe.fetchValue("select current_user"))
        assertEquals(
            Tenants.A.toString(),
            probe.inTenant(Tenants.A) {
                fetchValue("select current_setting(?, true)", TENANT_SETTING)
            },
        )
        assertTrue(
            probe
                .inTenant(Tenants.A) { fetchValue("select current_setting('app.binding_sig', true)") }
                .toString()
                .startsWith("${Tenants.A}."),
        )
        assertEquals(
            "",
            probe.unbound {
                fetchValue("select coalesce(current_setting(?, true), '')", TENANT_SETTING)
            },
        )
    }

    @Test
    fun `the main and impl jars contain no TenancyProbe class (unzip -l)`() {
        val jars = System.getProperty("tenancyJooq.jars").split(File.pathSeparator).map(::File)
        assertEquals(2, jars.size)
        jars.forEach { jar ->
            val entries =
                ZipFile(jar).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toList()
                }
            assertTrue(entries.any { it.endsWith("TableClasses.class") || it.endsWith("TenancyJooq.class") }, jar.name)
            assertEquals(emptyList<String>(), entries.filter { "TenancyProbe" in it }, jar.name)
        }
    }

    private companion object {
        /** The tenant setting the runner binds, passed as a bind value. */
        const val TENANT_SETTING = "app.tenant_id"
    }
}
