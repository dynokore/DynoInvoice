package com.dynokore.platform.tenancykernel

import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** INV-T-5358: the generator's text and the runner's refusal of a missing current key (no database needed). */
class BindingKeyMigrationTest {
    private val sql = BindingKeyMigration.ddl("svc_x", "svc_x_sys", "svc_x_app")

    @Test
    fun `ddl creates stable definer functions with a fixed search_path granted to the runtime login only`() {
        assertTrue(sql.contains("CREATE FUNCTION svc_x.bound_tenant() RETURNS uuid"))
        assertTrue(sql.contains("CREATE FUNCTION svc_x.bound_actor() RETURNS text"))
        assertTrue(
            Regex("STABLE SECURITY DEFINER SET search_path = pg_catalog, svc_x, pg_temp").findAll(sql).count() == 4,
        )
        assertTrue(sql.contains("REVOKE ALL ON FUNCTION svc_x.bound_tenant() FROM PUBLIC;"))
        assertTrue(sql.contains("REVOKE ALL ON TABLE svc_x.binding_key FROM PUBLIC;"))
        assertTrue(sql.contains("GRANT EXECUTE ON FUNCTION svc_x.bound_actor() TO svc_x_app;"))
        assertFalse(sql.contains("TO PUBLIC"))
        assertTrue(sql.contains("ALTER FUNCTION svc_x.bound_tenant() OWNER TO svc_x_sys;"))
        assertFalse(sql.contains("app.tenant_id"))
        assertFalse(sql.contains("app.actor"))
    }

    @Test
    fun `bound_tenant is one function body without an exception block, a loop or a call to another function`() {
        val body = functionText(sql, "svc_x.bound_tenant()")
        for (word in listOf("EXCEPTION", "LOOP", "binding_verified")) {
            assertFalse(body.contains(word), "bound_tenant() text holds $word")
        }
        assertTrue(body.contains("binding_key"), "the text reads the key table")
        // look-alike: bound_actor() may call binding_verified() and is not asserted
        assertTrue(functionText(sql, "svc_x.bound_actor()").contains("binding_verified"))
    }

    /** The text between `CREATE FUNCTION [name]` and the next `$$;`. */
    private fun functionText(
        ddl: String,
        name: String,
    ): String {
        val start = ddl.indexOf("CREATE FUNCTION $name")
        check(start >= 0) { "no function $name" }
        return ddl.substring(start, ddl.indexOf("$" + "$;", start))
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = ["Svc", "svc;drop", "svc x", "", "svc\"", "1svc"])
    fun `ddl refuses a name that is not a plain lower-case identifier`(bad: String) {
        assertThrows(IllegalArgumentException::class.java) { BindingKeyMigration.ddl(bad, "s_sys", "s_app") }
        assertThrows(IllegalArgumentException::class.java) { BindingKeyMigration.ddl("s", bad, "s_app") }
        assertThrows(IllegalArgumentException::class.java) { BindingKeyMigration.ddl("s", "s_sys", bad) }
        assertThrows(IllegalArgumentException::class.java) { BindingKeyMigration.ddl("s", "s_sys", "s_app", bad) }
    }

    @Test
    fun `a missing or empty current key fails the migration before any statement`() {
        val dsl = DSL.using(SQLDialect.POSTGRES) // no connection: any executed statement would fail differently
        for (current in listOf(null, ByteArray(0))) {
            val source =
                object : BindingKeySource {
                    override fun currentKey(): ByteArray? = current

                    override fun previousKey(): ByteArray? = byteArrayOf(1)
                }
            val e =
                assertThrows(IllegalStateException::class.java) {
                    BindingKeyMigration.writeKeys(dsl, "svc_x", source)
                }
            assertTrue(e.message!!.contains("no current binding key"))
        }
    }
}
