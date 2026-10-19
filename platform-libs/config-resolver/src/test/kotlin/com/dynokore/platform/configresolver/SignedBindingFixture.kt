package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.TenantId
import java.util.Locale
import java.util.UUID

/**
 * The signed tenant binding of the test databases (INV-REQ-854 b7, INV-T-4156 slice 5): the binding functions of
 * tenancy-kernel's generator in the test schema with the installation key in `binding_key`, and the signed value of a
 * raw transaction. Every runner of a harness signs with [KEYS]; a raw `set_config` signs with [sign].
 */
internal object SignedBindingFixture {
    val KEY: ByteArray = SignedBinding.key()
    val KEYS = SignedBinding.keys(KEY)

    /** A key the database does not know: a value signed with it is forged. */
    val FORGED_KEY: ByteArray = SignedBinding.key(fill = 2)

    /**
     * Init SQL for [schema], run as the container's superuser before the module's DDL: pgcrypto, then (unless
     * [withFunctions] is false, the precondition sample) the generator's DDL as [sysRole], which needs CREATE on the
     * schema for the moment, and the key in slot `current`.
     */
    fun script(
        schema: String,
        sysRole: String,
        runtimeRole: String,
        key: ByteArray = KEY,
        withFunctions: Boolean = true,
    ): String =
        """
        CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;
        GRANT USAGE, CREATE ON SCHEMA $schema TO $sysRole;
        GRANT USAGE ON SCHEMA $schema TO $runtimeRole;
        ${if (withFunctions) install(schema, sysRole, runtimeRole, key) else ""}
        REVOKE CREATE ON SCHEMA $schema FROM $sysRole;
        """.trimIndent()

    /**
     * What this module's DDL assumes (INV-Q-6663, the form of audit-log's `AuditTestDb.bindingInit`): the generator's
     * DDL as [sysRole], and the key in slot `current`. The only text here is a call of `BindingKeyMigration.ddl`.
     */
    private fun install(
        schema: String,
        sysRole: String,
        runtimeRole: String,
        key: ByteArray,
    ): String =
        "SET ROLE $sysRole;\n" +
            BindingKeyMigration.ddl(schema, sysRole, runtimeRole) + "\n" +
            "INSERT INTO $schema.binding_key (slot, secret) VALUES ('current', " +
            "decode('${key.joinToString("") { "%02x".format(Locale.ROOT, it) }}', 'hex'));\n" +
            "RESET ROLE;"

    /**
     * The `app.binding_sig` of [tenant] and [actor] as a test names them, or the empty setting when either is absent
     * or not a tenant id (the unbound case). An actor that is blank or holds a control character is signed as it is:
     * the function under test refuses it.
     */
    fun sign(
        tenant: String?,
        actor: String?,
        key: ByteArray = KEY,
    ): String {
        val id = tenant?.let { runCatching { TenantId(UUID.fromString(it)) }.getOrNull() }
        return if (id == null || actor.isNullOrEmpty()) "" else SignedBinding.value(key, id, actor)
    }
}
