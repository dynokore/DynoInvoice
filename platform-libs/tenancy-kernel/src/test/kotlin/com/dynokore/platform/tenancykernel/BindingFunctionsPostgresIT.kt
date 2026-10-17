package com.dynokore.platform.tenancykernel

import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * INV-T-5358 (INV-REQ-854 b7, owner ruling INV-Q-4121): `bound_tenant()` and `bound_actor()` on PostgreSQL 16, 17 and
 * 18, each version with its own container and sample. Every refusal is NULL, never an error; the key table is
 * readable by `<svc>_sys` only; EXECUTE is the runtime login's only; the search_path is fixed.
 */
@Tag("integration")
class BindingFunctionsPostgresIT {
    private val tenant = UUID.randomUUID()
    private val key0 = ByteArray(32) { 1 }
    private val key1 = ByteArray(32) { 2 }
    private val key2 = ByteArray(32) { 3 }
    private val key3 = ByteArray(32) { 4 }
    private val now = System.currentTimeMillis() / 1000
    private val future = now + 600

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `binding functions verify the signature and refuse everything else with NULL`(image: String) {
        PostgresProbeFixture(image, initSql = provisioning()).use { fixture ->
            pool(fixture, SYS, SYS_PW).use { sys ->
                pool(fixture, PostgresProbeFixture.APP, PostgresProbeFixture.APP_PASSWORD).use { app ->
                    pool(fixture, OTHER, OTHER_PW).use { other ->
                        writeKeys(sys, key1, key0)
                        assertVerification(app)
                        assertKeyRotation(sys, app)
                        assertAccess(sys, app, other)
                        assertFixedSearchPath(sys, app)
                    }
                }
            }
        }
    }

    private fun assertVerification(app: TestPoolFixture) {
        val actor = "user-ä/1.2"
        assertEquals(tenant to actor, bound(app, sg(actor, future, key1)), "valid")
        assertEquals(tenant to actor, bound(app, sg(actor, future, key0)), "previous key")
        assertEquals(NONE, bound(app, sg(actor, 1, key1)), "expired")
        assertEquals(NONE, bound(app, sg(actor, now - 1, key1)), "just expired")
        assertEquals(NONE, bound(app, sg(actor, future, key2)), "MAC from another key")
        val valid = sg(actor, future, key1).split('.')
        val other = UUID.randomUUID()
        assertEquals(NONE, bound(app, "$other.${valid[1]}.${valid[2]}.${valid[3]}"), "changed tenant")
        assertEquals(NONE, bound(app, "${valid[0]}.${b64("user-b")}.${valid[2]}.${valid[3]}"), "changed actor")
        assertEquals(NONE, bound(app, "${valid[0]}.${valid[1]}.${future + 1}.${valid[3]}"), "changed expiry")
        assertEquals(NONE, bound(app, ""), "empty value")
        assertEquals(NONE, bound(app, null), "missing GUC")
        val (t, a, e) = valid
        val m = valid[3]
        val good = sg(actor, future, key1)
        val malformed =
            listOf(
                "garbage",
                "...",
                "$t.$a.$e",
                "$t.$a.$e.$m.x",
                "$t.$a.$e.${m.uppercase()}",
                "${t.uppercase()}.$a.$e.$m",
                "$t..$e.$m",
                "$t.$a.-1.$m",
                "$t.$a.99999999999999.$m",
                "$t.%%.$e.$m",
                " $good",
                good + "\n",
            )
        malformed.forEach { assertEquals(NONE, bound(app, it), "malformed '$it'") }
        // A MAC that is valid but over an actor that is not UTF-8 gives NULL, not an error.
        val badUtf8 = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(-1, -2))
        assertEquals(NONE, bound(app, signRaw(tenant.toString(), badUtf8, future, key1)), "actor not UTF-8")
    }

    private fun assertKeyRotation(
        sys: TestPoolFixture,
        app: TestPoolFixture,
    ) {
        writeKeys(sys, key2, key1)
        assertEquals(tenant to "a", bound(app, sg("a", future, key2)), "new current")
        assertEquals(tenant to "a", bound(app, sg("a", future, key1)), "now the previous")
        assertEquals(NONE, bound(app, sg("a", future, key0)), "older than the previous")
        writeKeys(sys, key3, key2)
        assertEquals(NONE, bound(app, sg("a", future, key1)), "rotated out")
        assertEquals(tenant to "a", bound(app, sg("a", future, key2)))
        writeKeys(sys, key3, null)
        assertEquals(NONE, bound(app, sg("a", future, key2)), "previous removed")
        assertEquals(tenant to "a", bound(app, sg("a", future, key3)))
    }

    private fun assertAccess(
        sys: TestPoolFixture,
        app: TestPoolFixture,
        other: TestPoolFixture,
    ) {
        assertEquals(1, inTx(sys) { it.fetchCount(KEYS) }, "the owner reads the key table")
        for ((name, login) in listOf("runtime login" to app, "other login" to other)) {
            val e = assertThrows(DataAccessException::class.java, { inTx(login) { it.fetchCount(KEYS) } }, name)
            assertTrue(e.message!!.contains("permission denied"), "$name: ${e.message}")
        }
        val denied = assertThrows(DataAccessException::class.java) { bound(other, sg("a", future, key3)) }
        assertTrue(denied.message!!.contains("permission denied for function"), denied.message)
        for (fn in listOf("binding_verified", "bound_tenant")) {
            assertThrows(DataAccessException::class.java) { inTx(other) { it.fetch("select $SCHEMA.$fn()") } }
        }
        assertThrows(DataAccessException::class.java) { inTx(app) { it.fetch("select $SCHEMA.binding_verified()") } }
        val rows =
            inTx(sys) {
                it
                    .fetch(
                        "select p.proname, pg_get_userbyid(p.proowner), p.prosecdef, p.provolatile, " +
                            "p.proconfig::text from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                            "where n.nspname = ? order by 1",
                        SCHEMA,
                    ).map { r -> r.intoList() }
            }
        assertEquals(listOf("binding_verified", "bound_actor", "bound_tenant"), rows.map { it[0] })
        rows.forEach {
            assertEquals(SYS, it[1])
            assertEquals(true, it[2])
            assertEquals("s", it[3])
            assertEquals("{\"search_path=pg_catalog, pg_temp\"}", it[4])
        }
    }

    private fun assertFixedSearchPath(
        sys: TestPoolFixture,
        app: TestPoolFixture,
    ) {
        // The look-alike `evil.hmac` returns 32 zero bytes; a caller whose search_path puts it first resolves it.
        val sig = signRaw(tenant.toString(), b64("a"), future, null, "0".repeat(64))
        val control =
            inTx(app) {
                it.select(DSL.field("set_config('search_path', 'evil, public', true)")).fetch()
                it.fetchOne("select encode(hmac('a'::bytea, 'b'::bytea, 'sha256'), 'hex')")!!.get(0)
            }
        assertEquals("0".repeat(64), control, "the look-alike is what the caller's search_path resolves")
        val result =
            inTx(app) {
                it.select(DSL.field("set_config('search_path', 'evil, public', true)")).fetch()
                setSig(it, sig)
                it.fetchOne("select $SCHEMA.bound_tenant()")!!.get(0)
            }
        assertNull(result, "a function in a schema the caller controls is not resolved")
        // The same value is accepted when the real key signs it: the probe fails on the MAC, not on the setup.
        assertEquals(tenant to "a", bound(app, sg("a", future, key3)))
        assertEquals(1, inTx(sys) { it.fetchCount(KEYS) })
    }

    private fun bound(
        login: TestPoolFixture,
        sig: String?,
    ): Pair<UUID?, String?> =
        inTx(login) { dsl ->
            if (sig != null) setSig(dsl, sig)
            val r = dsl.fetchOne("select $SCHEMA.bound_tenant(), $SCHEMA.bound_actor()")!!
            (r.get(0) as UUID?) to (r.get(1) as String?)
        }

    private fun setSig(
        dsl: DSLContext,
        sig: String,
    ) {
        dsl.select(DSL.field("set_config('app.binding_sig', {0}, true)", String::class.java, DSL.`val`(sig))).fetch()
    }

    private fun writeKeys(
        sys: TestPoolFixture,
        current: ByteArray,
        previous: ByteArray?,
    ) = inTx(sys) {
        BindingKeyMigration.writeKeys(
            it,
            SCHEMA,
            object : BindingKeySource {
                override fun currentKey(): ByteArray = current

                override fun previousKey(): ByteArray? = previous
            },
        )
    }

    private fun <T> inTx(
        pool: TestPoolFixture,
        block: (DSLContext) -> T,
    ): T {
        val tenants = TenantContextImpl()
        val tx = pool.runner(tenants)
        return tenants.bind(TenantId(tenant), "it") { tx.inTransaction { block(DSL.using(tx.require())) } }
    }

    private fun pool(
        fixture: PostgresProbeFixture,
        user: String,
        password: String,
    ) = TestPoolFixture(fixture.jdbcUrl(), user, password, maximumPoolSize = 1)

    private fun provisioning(): String =
        """
        RESET ROLE;
        CREATE EXTENSION pgcrypto SCHEMA public;
        CREATE ROLE $SYS LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '$SYS_PW';
        CREATE ROLE $OTHER LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '$OTHER_PW';
        CREATE SCHEMA $SCHEMA AUTHORIZATION $SYS;
        GRANT USAGE ON SCHEMA $SCHEMA TO ${PostgresProbeFixture.APP}, $OTHER;
        CREATE SCHEMA evil AUTHORIZATION ${PostgresProbeFixture.APP};
        CREATE FUNCTION evil.hmac(bytea, bytea, text) RETURNS bytea LANGUAGE sql
            AS ${'$'}${'$'}SELECT decode(repeat('00', 32), 'hex')${'$'}${'$'};
        GRANT USAGE ON SCHEMA evil TO ${PostgresProbeFixture.APP};
        SET ROLE $SYS;
        ${BindingKeyMigration.ddl(SCHEMA, SYS, PostgresProbeFixture.APP)}
        RESET ROLE;
        SET ROLE ${PostgresProbeFixture.OWNER};
        """.trimIndent()

    private fun b64(actor: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(actor.toByteArray())

    private fun sg(
        actor: String,
        expiry: Long,
        key: ByteArray,
    ) = signRaw(tenant.toString(), b64(actor), expiry, key)

    private fun signRaw(
        tenant: String,
        actorField: String,
        expiry: Long,
        key: ByteArray?,
        macOverride: String? = null,
    ): String {
        val text = "$tenant.$actorField.$expiry"
        val mac =
            macOverride ?: Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key!!, "HmacSHA256"))
                doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
            }
        return "$text.$mac"
    }

    private companion object {
        const val SCHEMA = "tk_binding"
        const val SYS = "tenancy_kernel_sys"
        const val SYS_PW = "tenancy_kernel_sys_pw"
        const val OTHER = "tenancy_kernel_other"
        const val OTHER_PW = "tenancy_kernel_other_pw"
        val KEYS = DSL.table(DSL.name(SCHEMA, "binding_key"))
        val NONE: Pair<UUID?, String?> = null to null
    }
}
