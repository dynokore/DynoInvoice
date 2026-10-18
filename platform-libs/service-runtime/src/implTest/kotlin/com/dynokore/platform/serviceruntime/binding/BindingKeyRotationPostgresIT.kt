package com.dynokore.platform.serviceruntime.binding

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.PostgresProbeFixture
import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TestPoolFixture
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.matchers.shouldBe
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID

/**
 * INV-T-5360 (INV-REQ-854 b7): the runner signs with the key read through SecretStore at each transaction start. A
 * rotation of the secrets changes the signing key without a restart, and a value signed with the previous key still
 * verifies, on PostgreSQL 17 and 18. Only jOOQ reaches the database (INV-Q-4196).
 */
@Tag("integration")
class BindingKeyRotationPostgresIT {
    private val tenant = TenantId(UUID.randomUUID())
    private val actor = "user-1"
    private val tenants = TenantContextImpl()
    private val current = SecretStoreBindingKeySource.CURRENT
    private val previous = SecretStoreBindingKeySource.PREVIOUS

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a rotation changes the signing key with no restart and the previous key still verifies`(image: String) {
        val provisioning = SignedBinding.provisioning(SCHEMA, SYS, SYS_PW, PostgresProbeFixture.APP)
        PostgresProbeFixture(image, initSql = provisioning).use { fixture ->
            TestPoolFixture(fixture.jdbcUrl(), SYS, SYS_PW, maximumPoolSize = 1).use { sys ->
                TestPoolFixture(
                    fixture.jdbcUrl(),
                    PostgresProbeFixture.APP,
                    PostgresProbeFixture.APP_PASSWORD,
                    maximumPoolSize = 1,
                ).use { app ->
                    val store = FakeSecretStore().apply { put(current, "key-one-key-one-key-one-key-one!") }
                    val source = SecretStoreBindingKeySource(store)
                    val runner = app.runner(tenants, keys = source) // built once, never rebuilt

                    writeKeys(sys, source)
                    boundBy(runner) shouldBe (tenant.value to actor)

                    // rotate: the secrets change, the database keys follow (migration), the runner is the same object
                    store.put(previous, "key-one-key-one-key-one-key-one!")
                    store.put(current, "key-two-key-two-key-two-key-two!")
                    // the database still holds only key one as current: a value signed with key two does not verify yet
                    boundBy(runner) shouldBe (null to null)
                    writeKeys(sys, source)
                    boundBy(runner) shouldBe (tenant.value to actor)
                    verifies(app, store.keyOf(previous)) shouldBe true // signed with the previous key
                    verifies(app, store.keyOf(current)) shouldBe true

                    // the rotation is finished: only key two is left, a value signed with key one no longer verifies
                    store.remove(previous)
                    writeKeys(sys, source)
                    verifies(app, "key-one-key-one-key-one-key-one!".toByteArray()) shouldBe false
                    boundBy(runner) shouldBe (tenant.value to actor)
                }
            }
        }
    }

    private fun FakeSecretStore.keyOf(ref: com.dynokore.platform.serviceruntime.binding.SecretRef) =
        read(ref).reveal().toByteArray()

    private fun writeKeys(
        sys: TestPoolFixture,
        source: SecretStoreBindingKeySource,
    ) {
        val tx = sys.runner(tenants)
        tenants.bind(tenant, "migration") {
            tx.inTransaction { BindingKeyMigration.writeKeys(DSL.using(tx.require()), SCHEMA, source) }
        }
    }

    private fun boundBy(runner: TransactionRunnerImpl): Pair<UUID?, String?> =
        tenants.bind(tenant, actor) {
            runner.inTransaction {
                val r = DSL.using(runner.require()).fetchOne("select $SCHEMA.bound_tenant(), $SCHEMA.bound_actor()")!!
                (r.get(0) as UUID?) to (r.get(1) as String?)
            }
        }

    /** Whether a value signed by [key] for the tenant and actor verifies in the database. */
    private fun verifies(
        app: TestPoolFixture,
        key: ByteArray,
    ): Boolean {
        val unsigned = app.runner(tenants)
        val value = SignedBinding.value(key, tenant, actor, Instant.now())
        return tenants.bind(tenant, actor) {
            unsigned.inTransaction {
                val dsl = DSL.using(unsigned.require())
                dsl.fetch("select set_config('app.binding_sig', ?, true)", value)
                dsl.fetchOne("select $SCHEMA.bound_tenant()")!!.get(0) == tenant.value
            }
        }
    }

    private companion object {
        const val SCHEMA = "sr_signed"
        const val SYS = "sr_signed_sys"
        const val SYS_PW = "sr_signed_sys_pw"
    }
}
