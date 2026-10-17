package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

// The probe sessions and statements of PackRegistryMigrationPostgresIT, through tenancy-jooq's TenancyProbe only.

/** Binds only `app.tenant_id` in the current probe transaction, leaving `app.actor` unset. */
internal fun TenancyProbe.bindTenantOnly(tenant: TenantId) {
    fetchValue("select set_config('app.tenant_id', ?, true)", tenant.toString())
}

/**
 * Binds only `app.actor` (to the row's default registered_by), so an unbound insert passes the registered_by
 * trigger and reaches the policy.
 */
internal fun TenancyProbe.bindActorOnly() {
    fetchValue("select set_config('app.actor', ?, true)", "tenancy-probe")
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
            inTenant(identity.tenant, block = block)
        }

        is Identity.Operator -> {
            val withSetting: TenancyProbe.() -> T = {
                fetchValue("select set_config(?, ?, true)", "app.platform_operator", "true")
                block()
            }
            identity.tenant?.let { inTenant(it, block = withSetting) } ?: unbound(withSetting)
        }

        Identity.None -> {
            unbound(block)
        }
    }

/** Calls `register_platform_pack` in the current probe transaction; returns the stored id. */
internal fun TenancyProbe.register(
    packId: String,
    version: String = "1.0.0",
    effectiveTo: LocalDate? = null,
    manifest: String = "{}",
    signature: ByteArray = byteArrayOf(1, 2, 3),
): Any? =
    fetchValue(
        "select rulepack_test.register_platform_pack(?::uuid, ?, 'Scope', ?, ?::date, ?::date, '1.0.0', " +
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

internal fun insert(
    app: TenancyProbe,
    session: TenantId,
    rowTenant: TenantId,
    packId: String,
    version: String = "1.0.0",
) = app.inTenant(session) { insertRow(rowTenant, packId, version) }

internal fun packIds(
    app: TenancyProbe,
    identity: Identity,
): Set<String> =
    app.bound(identity) {
        fetchRows("select pack_id from rulepack_test.rule_pack_version where version = '1.0.0'")
            .map { it.getValue("pack_id") as String }
            .toSet()
    }

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
internal const val MIGRATE = "rulepack_kernel_migrate"
internal const val MIGRATE_PASSWORD = "rulepack_kernel_migrate_pw"

private const val INIT_SCRIPT_TARGET = "/docker-entrypoint-initdb.d/01-rulepack-kernel.sql"
private const val ROLES_RESOURCE = "db/rulepack-kernel-test-roles.sql"
private const val BASE_DIR = "db/pack-registry-it/base"
private const val PLACEHOLDER = "\${packRegistryDdl}"

/** The generated pack-registry DDL the test migrations receive as `packRegistryDdl`. */
internal fun packRegistryDdl(): String =
    PackRegistryMigration.ddl(
        schema = "rulepack_test",
        table = "rule_pack_version",
        runtimeRole = "rulepack_kernel_app",
        sysRole = "rulepack_kernel_sys",
    )

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
 * The container init script: the superuser role script, then the base migration files in version order with the
 * placeholder `packRegistryDdl` replaced by [packRegistryDdl], run under `SET ROLE` to [MIGRATE] so that login owns
 * the objects (route R1 of INV-Q-5245 and INV-Q-5119; no Flyway call).
 */
internal fun packRegistryInitScript(): String =
    resourceText(ROLES_RESOURCE) +
        "\nSET ROLE $MIGRATE;\n" +
        baseFileNames().joinToString("\n") { resourceText("$BASE_DIR/$it").replace(PLACEHOLDER, packRegistryDdl()) } +
        "\nRESET ROLE;\n"

/** Applies the roles and the test migrations as container init SQL, before the container accepts connections. */
internal fun <C : PostgreSQLContainer<C>> C.withPackRegistryInit(): C =
    withCopyToContainer(Transferable.of(packRegistryInitScript()), INIT_SCRIPT_TARGET)
