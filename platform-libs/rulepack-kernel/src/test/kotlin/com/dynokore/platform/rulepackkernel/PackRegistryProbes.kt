package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The probe sessions and statements of PackRegistryMigrationPostgresIT, through tenancy-jooq's TenancyProbe only.

/** The installation key of schema `rulepack_test`; [OTHER_KEY] is the key of the look-alike schema `rulepack_other`. */
internal val KEY: ByteArray = SignedBinding.key(1)
internal val OTHER_KEY: ByteArray = SignedBinding.key(2)

/** A key that no schema holds: a binding signed with it is forged everywhere. */
internal val FORGED_KEY: ByteArray = SignedBinding.key(9)

/** An actor the shared actor rule treats as unset (blank), the signed form of "tenant bound, actor unset". */
internal const val UNSET_ACTOR = " "

/** Sets the signed binding `app.binding_sig` of [tenant] and [actor] under [key] in the current probe transaction. */
internal fun TenancyProbe.sign(
    tenant: TenantId,
    actor: String = "tenancy-probe",
    key: ByteArray = KEY,
) {
    val expiry = BindingSignature.expiryFor(Instant.now(), Duration.ofSeconds(60))
    fetchValue(
        "select set_config('app.binding_sig', ?, true)",
        BindingSignature.sign(key, tenant, actor, expiry),
    )
}

/**
 * Runs [block] in one transaction under the signed binding of [tenant] and [actor] (signed with [key], the installation
 * key by default), as the runner of tenancy-kernel binds it; the plain `app.tenant_id` and `app.actor` are not set.
 */
internal fun <T> TenancyProbe.inSigned(
    tenant: TenantId,
    actor: String = "tenancy-probe",
    key: ByteArray = KEY,
    block: TenancyProbe.() -> T,
): T =
    unbound {
        sign(tenant, actor, key)
        block()
    }

/**
 * Binds the tenant with an unset (blank) actor: the signed binding cannot carry a tenant without an actor, so the blank
 * actor is what the actor rule refuses as unset.
 */
internal fun TenancyProbe.bindTenantOnly(tenant: TenantId) = sign(tenant, UNSET_ACTOR)

/** Sets the plain, unsigned `app.tenant_id` and `app.actor`: the forged binding of a session that cannot sign. */
internal fun TenancyProbe.bindUnsigned(
    tenant: TenantId,
    actor: String = "tenancy-probe",
) {
    fetchValue(
        "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true)",
        tenant.toString(),
        actor,
    )
}

/** Who the probe session is: a bound tenant, no tenant, or either with `app.platform_operator = 'true'` set. */
internal sealed interface Identity {
    data class Tenant(
        val tenant: TenantId,
    ) : Identity

    data class Operator(
        val tenant: TenantId?,
    ) : Identity

    data object None : Identity
}

internal fun <T> TenancyProbe.bound(
    identity: Identity,
    block: TenancyProbe.() -> T,
): T =
    when (identity) {
        is Identity.Tenant -> {
            inSigned(identity.tenant, block = block)
        }

        is Identity.Operator -> {
            val withSetting: TenancyProbe.() -> T = {
                fetchValue("select set_config(?, ?, true)", "app.platform_operator", "true")
                block()
            }
            identity.tenant?.let { inSigned(it, block = withSetting) } ?: unbound(withSetting)
        }

        Identity.None -> {
            unbound(block)
        }
    }

/** Calls `register_platform_pack` in the current probe transaction; returns the stored id. */
internal fun TenancyProbe.register(
    packId: String,
    schema: String = "rulepack_test",
    version: String = "1.0.0",
    effectiveTo: LocalDate? = null,
    manifest: String = "{}",
    signature: ByteArray = byteArrayOf(1, 2, 3),
): Any? =
    fetchValue(
        "select $schema.register_platform_pack(?::uuid, ?, 'Scope', ?, ?::date, ?::date, '1.0.0', " +
            "?::jsonb, '{}'::jsonb, ?::bytea, '{}'::jsonb, 'test-anchor')",
        UUID.randomUUID(),
        packId,
        version,
        LocalDate.now(),
        effectiveTo,
        manifest,
        signature,
    )

/** Inserts one row directly in the current probe transaction, the row carrying [rowTenant]. */
internal fun TenancyProbe.insertRow(
    rowTenant: TenantId,
    packId: String,
    version: String = "1.0.0",
    registeredBy: String = "tenancy-probe",
) {
    fetchValue(
        """
        insert into rulepack_test.rule_pack_version
            (id, pack_id, pack_type, version, tenant_id, effective_from, min_engine_version,
             manifest, checksums, signature, contents, trusted_anchor, registered_by)
        values (?, ?, 'Scope', ?, ?::uuid, ?, '1.0.0', '{}'::jsonb, '{}'::jsonb, ?, '{}'::jsonb, 'test-anchor', ?)
        returning pack_id
        """.trimIndent(),
        UUID.randomUUID(),
        packId,
        version,
        rowTenant.toString(),
        LocalDate.now(),
        byteArrayOf(1, 2, 3),
        registeredBy,
    )
}

/** The pack ids visible to the current probe transaction. */
internal fun TenancyProbe.visiblePackIds(): Set<String> =
    fetchRows("select pack_id from rulepack_test.rule_pack_version where version = '1.0.0'")
        .map { it.getValue("pack_id") as String }
        .toSet()

/**
 * The owner inserts one row of [rowTenant] with the actor trigger disabled in the same (rolled back) transaction, so
 * that only the policy can refuse it.
 */
internal fun TenancyProbe.insertBypassingTheActorTrigger(
    rowTenant: TenantId,
    packId: String,
) {
    fetchValue(
        "select rulepack_test.insert_bypassing_the_actor_trigger(?, ?, ?::uuid, 'tenancy-probe')",
        packId,
        "0.0.${packId.hashCode().toUInt()}", // a lineage of its own: only the policy may refuse the row
        rowTenant.toString(),
    )
}

internal fun insert(
    app: TenancyProbe,
    session: TenantId,
    rowTenant: TenantId,
    packId: String,
    version: String = "1.0.0",
) = app.inSigned(session) { insertRow(rowTenant, packId, version) }

internal fun packIds(
    app: TenancyProbe,
    identity: Identity,
): Set<String> = app.bound(identity) { visiblePackIds() }

internal fun assertRefusedByPolicy(block: () -> Unit) = assertRefusedWith("row-level security policy", block)

internal fun assertRefusedWith(
    expected: String,
    block: () -> Unit,
) {
    val refused = assertThrows(DataAccessException::class.java, block)
    val message = refused.message.orEmpty()
    assertTrue(expected in message, "expected '$expected' in: $message")
}

/** The migration login (`<svc>_migrate`): NOSUPERUSER, NOBYPASSRLS, owns the table, member of the sys role. */
internal const val SYS = "rulepack_kernel_sys"
internal const val RUNTIME = "rulepack_kernel_app"
internal const val MIGRATE = "rulepack_kernel_migrate"
internal const val MIGRATE_PASSWORD = "rulepack_kernel_migrate_pw"

private const val INIT_SCRIPT_TARGET = "/docker-entrypoint-initdb.d/01-rulepack-kernel.sql"
private const val ROLES_RESOURCE = "db/rulepack-kernel-test-roles.sql"
private const val BASE_DIR = "db/pack-registry-it/base"
private const val PLACEHOLDER = "\${packRegistryDdl}"

/** The generated pack-registry DDL the test migrations receive as `packRegistryDdl`. */
internal fun packRegistryDdl(schema: String = "rulepack_test"): String =
    PackRegistryMigration.ddl(
        schema = schema,
        table = "rule_pack_version",
        runtimeRole = RUNTIME,
        sysRole = SYS,
    )

private fun hex(key: ByteArray): String = key.joinToString("") { "%02x".format(it) }

/**
 * The binding functions and the test key of [schema], as the init script of the superuser creates them before the
 * module's own DDL: tenancy-kernel's `BindingKeyMigration.ddl` run as the sys login (the key table and the functions
 * exist once per schema), then the key row, the form of `AuditTestDb.bindingInit` in audit-log. This is test code, so
 * it may switch role; the generator's text is not copied here.
 */
internal fun bindingInit(
    schema: String,
    key: ByteArray,
): String =
    "GRANT USAGE, CREATE ON SCHEMA $schema TO $SYS;\nSET ROLE $SYS;\n" +
        BindingKeyMigration.ddl(schema, SYS, RUNTIME) + "\n" +
        "INSERT INTO $schema.binding_key (slot, secret) VALUES ('current', decode('${hex(key)}', 'hex'));\n" +
        "RESET ROLE;\nREVOKE CREATE ON SCHEMA $schema FROM $SYS;\n"

private fun resourceText(path: String): String =
    checkNotNull(PackRegistryMigration::class.java.classLoader.getResourceAsStream(path)) { "missing $path" }
        .use { it.readBytes().decodeToString() }

/** The files of `db/pack-registry-it/base` in version order (`V<n>__name.sql`). */
private fun baseFileNames(): List<String> {
    val dir = Path.of(checkNotNull(PackRegistryMigration::class.java.classLoader.getResource(BASE_DIR)).toURI())
    return Files.list(dir).use { files ->
        files
            .map { it.fileName.toString() }
            .filter { it.endsWith(".sql") }
            .toList()
            .sortedBy { Regex("""V(\d+)__""").find(it)!!.groupValues[1].toInt() }
    }
}

/**
 * The container init script: the superuser role script, the binding functions and key of both schemas
 * ([bindingInit]), then the base migration files in version order with the placeholder `packRegistryDdl` replaced by
 * [packRegistryDdl], run under `SET ROLE` to [MIGRATE] so that login owns the objects (route R1 of INV-Q-5245 and
 * INV-Q-5119; no Flyway call).
 */
internal fun packRegistryInitScript(): String =
    resourceText(ROLES_RESOURCE) +
        "\n" + bindingInit("rulepack_test", KEY) + bindingInit("rulepack_other", OTHER_KEY) +
        "SET ROLE $MIGRATE;\n" +
        baseFileNames().joinToString("\n") { resourceText("$BASE_DIR/$it").replace(PLACEHOLDER, packRegistryDdl()) } +
        "\n${packRegistryDdl("rulepack_other")}\n" +
        "GRANT USAGE ON SCHEMA rulepack_other TO rulepack_kernel_app;\n" +
        "GRANT INSERT, SELECT ON rulepack_other.rule_pack_version TO rulepack_kernel_app;\n" +
        "RESET ROLE;\n"

/** Applies only the roles script: roles and schemas, no binding functions and no registry (the precondition sample). */
internal fun <C : PostgreSQLContainer<C>> C.withRolesOnlyInit(): C =
    withCopyToContainer(Transferable.of(resourceText(ROLES_RESOURCE)), INIT_SCRIPT_TARGET)

/** Applies the roles and the test migrations as container init SQL, before the container accepts connections. */
internal fun <C : PostgreSQLContainer<C>> C.withPackRegistryInit(): C =
    withCopyToContainer(Transferable.of(packRegistryInitScript()), INIT_SCRIPT_TARGET)
