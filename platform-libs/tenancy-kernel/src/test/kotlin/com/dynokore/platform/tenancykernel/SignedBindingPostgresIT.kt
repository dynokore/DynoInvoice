package com.dynokore.platform.tenancykernel

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * INV-T-5359 (INV-REQ-854 b7, INV-Q-4121): a transaction the runner signs, on PostgreSQL 16, 17, 18. `bound_tenant()`
 * and `bound_actor()` answer the bound tenant and actor; a changed `app.tenant_id` (other or platform tenant) or a
 * signature changed in one character never moves them to another tenant; the statement text holds neither the
 * signature nor the key; an expired value gives NULL.
 */
@Tag("integration")
class SignedBindingPostgresIT {
    private val tenant = TenantId(UUID.randomUUID())
    private val actor = "user-ä/1"
    private val key = SignedBinding.key(7)
    private val tenants = TenantContextImpl()

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the signed binding is what the functions answer, and nothing in the transaction changes it`(image: String) {
        val provisioning = SignedBinding.provisioning(SCHEMA, SYS, SYS_PW, PostgresProbeFixture.APP)
        PostgresProbeFixture(image, initSql = provisioning).use { fixture ->
            TestPoolFixture(fixture.jdbcUrl(), SYS, SYS_PW, maximumPoolSize = 1).use { sys ->
                TestPoolFixture(
                    fixture.jdbcUrl(),
                    PostgresProbeFixture.APP,
                    PostgresProbeFixture.APP_PASSWORD,
                    maximumPoolSize = 1,
                ).use { app ->
                    val sysTx = sys.runner(tenants, keys = SignedBinding.keys(key))
                    tenants.bind(tenant, "migration") {
                        sysTx.inTransaction {
                            BindingKeyMigration.writeKeys(
                                DSL.using(sysTx.require()),
                                SCHEMA,
                                SignedBinding.keys(key),
                            )
                        }
                    }
                    assertBound(app)
                    assertTamperingChangesNothing(app)
                    assertStatementLog(app)
                    assertExpired(app)
                }
            }
        }
    }

    private fun assertBound(app: TestPoolFixture) {
        val (t, a) = readBound(app.runner(tenants, keys = SignedBinding.keys(key)))
        assertEquals(tenant.value, t)
        assertEquals(actor, a)
    }

    private fun assertTamperingChangesNothing(app: TestPoolFixture) {
        val other = UUID.randomUUID()
        val platform = TenantId.PLATFORM.value
        for (value in listOf(other, platform)) {
            val (t, a) =
                readBound(app.runner(tenants, keys = SignedBinding.keys(key))) { dsl ->
                    dsl.fetch("select set_config('app.tenant_id', ?, true)", value.toString())
                }
            assertEquals(tenant.value, t, "set_config(app.tenant_id, $value)")
            assertEquals(actor, a)
        }
        // one character of the signature changed: NULL, never another tenant
        val (t, a) =
            readBound(app.runner(tenants, keys = SignedBinding.keys(key))) { dsl ->
                val sig = dsl.fetchOne("select current_setting('app.binding_sig')")!!.get(0) as String
                val changed = sig.dropLast(1) + (if (sig.last() == '0') '1' else '0')
                assertNotEquals(sig, changed)
                dsl.fetch("select set_config('app.binding_sig', ?, true)", changed)
            }
        assertNull(t)
        assertNull(a)
    }

    private fun assertStatementLog(app: TestPoolFixture) {
        val log = Log()
        val runner =
            signingRunner(app, log)
        val sig =
            tenants.bind(tenant, actor) {
                runner.inTransaction {
                    DSL.using(runner.require()).fetchOne("select current_setting('app.binding_sig')")!!.get(0) as String
                }
            }
        assertTrue(log.texts.any { it.contains("app.binding_sig") }, log.texts.toString())
        assertCleanLog(log, sig)

        // look-alike: a scratch statement that inlines the signature and the key must fail the same assertion
        val scratch = Log()
        val scratchRunner =
            signingRunner(app, scratch)
        tenants.bind(tenant, actor) {
            scratchRunner.inTransaction {
                DSL.using(scratchRunner.require()).fetch("select set_config('app.binding_sig', '$sig', true)")
            }
        }
        assertThrows(AssertionError::class.java) { assertCleanLog(scratch, sig) }
        val scratchKey = Log()
        val keyRunner =
            signingRunner(app, scratchKey)
        tenants.bind(tenant, actor) {
            keyRunner.inTransaction {
                DSL.using(keyRunner.require()).fetch("select '${hex(key)}'")
            }
        }
        assertThrows(AssertionError::class.java) { assertCleanLog(scratchKey, sig) }
    }

    private fun signingRunner(
        app: TestPoolFixture,
        log: Log,
    ) = TransactionRunnerImpl(TenantAwareDataSource(log.wrap(app.dataSource())), tenants, null, SignedBinding.keys(key))

    private fun assertCleanLog(
        log: Log,
        sig: String,
    ) {
        for (text in log.texts) {
            assertFalse(text.contains(sig), "signature in statement text: $text")
            assertFalse(text.contains(sig.substringAfterLast('.')), "MAC in statement text: $text")
            assertFalse(text.contains(hex(key)), "key in statement text: $text")
        }
    }

    private fun assertExpired(app: TestPoolFixture) {
        val past = Clock.fixed(Instant.now().minusSeconds(120), ZoneOffset.UTC)
        val expired =
            app.runner(
                tenants,
                keys = SignedBinding.keys(key),
                transactionTimeout = Duration.ofSeconds(30),
                clock = past,
            )
        assertEquals(null to null, readBound(expired))
    }

    private fun readBound(
        runner: TransactionRunnerImpl,
        beforeRead: (DSLContext) -> Unit = {},
    ): Pair<UUID?, String?> =
        tenants.bind(tenant, actor) {
            runner.inTransaction {
                val dsl = DSL.using(runner.require())
                beforeRead(dsl)
                val r = dsl.fetchOne("select $SCHEMA.bound_tenant(), $SCHEMA.bound_actor()")!!
                (r.get(0) as UUID?) to (r.get(1) as String?)
            }
        }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** The text of every statement the runner and jOOQ prepared or executed, in order. */
    private class Log {
        val texts = mutableListOf<String>()

        fun wrap(target: DataSource): DataSource =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, m, args ->
                val result = invoke(m, target, args)
                if (m.name == "getConnection") connection(result as Connection) else result
            } as DataSource

        private fun connection(real: Connection): Connection =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, m, args ->
                if (m.name in setOf("prepareStatement", "prepareCall", "nativeSQL")) texts += args!![0] as String
                val result = invoke(m, real, args)
                if (m.name == "createStatement") statement(result as Statement) else result
            } as Connection

        private fun statement(real: Statement): Statement =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Statement::class.java)) { _, m, args ->
                if (m.name.startsWith("execute") && args?.firstOrNull() is String) texts += args[0] as String
                invoke(m, real, args)
            } as Statement

        private fun invoke(
            m: java.lang.reflect.Method,
            target: Any,
            args: Array<Any?>?,
        ): Any? =
            try {
                m.invoke(target, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
    }

    private companion object {
        const val SCHEMA = "tk_signed"
        const val SYS = "tk_signed_sys"
        const val SYS_PW = "tk_signed_sys_pw"
    }
}
