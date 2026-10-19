package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import java.util.Locale

/**
 * The binding functions and the test key of [schema], for the fixture's init script, which runs as the superuser before
 * the first policy: tenancy-kernel's `BindingKeyMigration.ddl` run as the sys login (the key table and the functions
 * exist once per schema), then the key row; the form of `AuditTestDb.bindingInit` in audit-log. This is test code, so
 * it
 * may switch role; the generator's text is not copied here.
 */
internal fun bindingInit(
    schema: String,
    key: ByteArray,
    sysRole: String,
    runtimeRole: String,
): String =
    "GRANT USAGE, CREATE ON SCHEMA $schema TO $sysRole;\nSET ROLE $sysRole;\n" +
        BindingKeyMigration.ddl(schema, sysRole, runtimeRole) + "\n" +
        "INSERT INTO $schema.binding_key (slot, secret) VALUES ('current', decode('${hex(key)}', 'hex'));\n" +
        "RESET ROLE;\nREVOKE CREATE ON SCHEMA $schema FROM $sysRole;\n"

private fun hex(key: ByteArray): String = key.joinToString("") { "%02x".format(Locale.ROOT, it) }
