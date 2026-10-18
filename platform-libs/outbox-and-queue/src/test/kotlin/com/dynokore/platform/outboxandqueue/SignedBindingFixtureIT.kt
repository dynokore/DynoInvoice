package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * INV-T-5363 (INV-T-4156 slice 6, INV-REQ-854 b7): the fixture's signed binding and the one function this module's
 * fixtures changed, the actor-capture trigger, which reads the actor through `<schema>.bound_actor()`. A forged
 * `app.binding_sig` gets the refusal the unbound case gets (no tenant, no actor); a schema without the binding
 * functions is not a substitute for the refusal, it fails with `undefined_function`.
 */
@Tag("integration")
class SignedBindingFixtureIT {
    private val tenant = UUID.randomUUID()

    private fun fixture(image: String): OutboxPostgresFixture =
        OutboxPostgresFixture(image).also {
            it.createService(
                SCHEMA,
                """
                create table $SCHEMA.probe (tenant_id uuid not null);
                grant insert on $SCHEMA.probe to ${OutboxPostgresFixture.APP};
                """.trimIndent(),
            )
            it.captureActors("$SCHEMA.probe")
        }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `a valid signature binds the tenant and the actor, a forged one gets the unbound refusal`(image: String) {
        fixture(image).use { fixture ->
            val valid = SigningFixture.signed(tenant)
            assertEquals(tenant to OutboxPostgresFixture.WORKLOAD, bound(fixture, valid))
            val unbound = bound(fixture, null)
            assertEquals(null to null, unbound)
            val forged =
                listOf(
                    valid.dropLast(1) + (if (valid.last() == 'A') 'B' else 'A'),
                    SigningFixture.signed(UUID.randomUUID()).replaceBefore('.', tenant.toString()),
                    "",
                    "garbage",
                )
            forged.forEach { assertEquals(unbound, bound(fixture, it), "forged '$it'") }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the capture trigger records the signed actor and nothing for a forged signature`(image: String) {
        fixture(image).use { fixture ->
            insertProbe(fixture, SigningFixture.signed(tenant))
            insertProbe(fixture, SigningFixture.signed(tenant).dropLast(2) + "xx")
            insertProbe(fixture, null)
            assertEquals(
                listOf<String?>(OutboxPostgresFixture.WORKLOAD, null, null),
                fixture.capturedActors("$SCHEMA.probe"),
            )
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17"])
    fun `a schema without the binding functions does not stand in for the refusal`(image: String) {
        fixture(image).use { fixture ->
            fixture.runtimeDataSource().connection.use { c ->
                assertEquals(UNDEFINED_FUNCTION, sqlStateOf(c, "select $OTHER.bound_tenant()"))
            }
            fixture.runAsAdmin(
                "create table $OTHER.probe (tenant_id uuid); " +
                    "grant insert on $OTHER.probe to ${OutboxPostgresFixture.APP};",
            )
            fixture.captureActors("$OTHER.probe")
            fixture.runtimeDataSource().connection.use { c ->
                SigningFixture.bind(c, tenant, transactionLocal = false)
                assertEquals(UNDEFINED_FUNCTION, sqlStateOf(c, "insert into $OTHER.probe values ('$tenant')"))
            }
        }
    }

    private fun sqlStateOf(
        c: Connection,
        sql: String,
    ): String? = assertThrows(SQLException::class.java) { c.createStatement().use { it.execute(sql) } }.sqlState

    private fun insertProbe(
        fixture: OutboxPostgresFixture,
        signature: String?,
    ) {
        fixture.runtimeDataSource().connection.use { c ->
            c.autoCommit = false
            bind(c, signature)
            c.prepareStatement("insert into $SCHEMA.probe (tenant_id) values (?)").use {
                it.setObject(1, tenant)
                it.executeUpdate()
            }
            c.commit()
        }
    }

    private fun bound(
        fixture: OutboxPostgresFixture,
        signature: String?,
    ): Pair<UUID?, String?> =
        fixture.runtimeDataSource().connection.use { c ->
            c.autoCommit = false
            bind(c, signature)
            c
                .prepareStatement("select $SCHEMA.bound_tenant(), $SCHEMA.bound_actor()")
                .use { s ->
                    s.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1, UUID::class.java) to rs.getString(2)
                    }
                }.also { c.rollback() }
        }

    /** Tenant and actor as the runner sets them; the signature is [signature], or none when null (the unbound case). */
    private fun bind(
        c: Connection,
        signature: String?,
    ) {
        if (signature == null) {
            c.prepareStatement("select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true)").use {
                it.setString(1, tenant.toString())
                it.setString(2, OutboxPostgresFixture.WORKLOAD)
                it.execute()
            }
        } else {
            SigningFixture.bind(c, tenant, signature = signature)
        }
    }

    private companion object {
        const val SCHEMA = "payload"
        const val OTHER = "canonical"
        const val UNDEFINED_FUNCTION = "42883"
    }
}
