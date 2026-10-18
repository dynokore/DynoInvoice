package com.dynokore.platform.tenancykernel

import java.time.Duration
import java.time.Instant

/**
 * The signing helper of the library slices (INV-T-4156, slice 2): a library test that runs under a signed binding takes
 * its key source and its provisioning from here, and builds the runner with `TestPoolFixture.runner(tenants, keys = ...)`.
 */
object SignedBinding {
    /** A fixed test key: 32 bytes of [fill]. */
    fun key(fill: Int = 1): ByteArray = ByteArray(32) { fill.toByte() }

    /** A key source with [current] and, while a rotation is in flight, [previous]. Hands out copies. */
    fun keys(
        current: ByteArray? = key(),
        previous: ByteArray? = null,
    ): BindingKeySource =
        object : BindingKeySource {
            override fun currentKey(): ByteArray? = current?.copyOf()

            override fun previousKey(): ByteArray? = previous?.copyOf()
        }

    /** The signed value of [tenant] and [actor] under [key], expiring [timeout] after [start]. */
    fun value(
        key: ByteArray,
        tenant: TenantId,
        actor: String,
        start: Instant = Instant.now(),
        timeout: Duration = Duration.ofSeconds(30),
    ): String = BindingSignature.sign(key, tenant, actor, BindingSignature.expiryFor(start, timeout))

    /**
     * Provisioning SQL for a test database, to run as the owner after the roles script (`PostgresProbeFixture`'s
     * `initSql`): pgcrypto, a `<svc>_sys` login and the binding functions in [schema] for the runtime login [runtimeRole].
     */
    fun provisioning(
        schema: String,
        sysRole: String,
        sysPassword: String,
        runtimeRole: String,
    ): String =
        """
        RESET ROLE;
        CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;
        CREATE ROLE $sysRole LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '$sysPassword';
        CREATE SCHEMA $schema AUTHORIZATION $sysRole;
        GRANT USAGE ON SCHEMA $schema TO $runtimeRole;
        SET ROLE $sysRole;
        ${BindingKeyMigration.ddl(schema, sysRole, runtimeRole)}
        RESET ROLE;
        SET ROLE ${PostgresProbeFixture.OWNER};
        """.trimIndent()
}
