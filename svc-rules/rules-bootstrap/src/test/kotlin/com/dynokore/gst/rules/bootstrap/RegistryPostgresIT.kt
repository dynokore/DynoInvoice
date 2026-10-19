package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.adapters.persistence.ArmedPackLookup
import com.dynokore.gst.rules.domain.resolution.PackResolution
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.ResolutionDates
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.serviceruntime.migration.MigrationRunner
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/**
 * The registry against real PostgreSQL, the way TPL-14 builds it: the roles of TPL-12 (rules_migrate owns the schema and
 * every table, rules_runtime is the one runtime login, rules_sys owns the definer functions), the migrations run through
 * service-runtime's MigrationRunner as rules_migrate, the service on its runtime login, and a TenancyProbe on the same
 * login for the catalog and row-level-security probes. No other raw SQL, no connection of the test's own.
 */
@SpringBootTest(classes = [RulesApplication::class])
class RegistryPostgresIT(
    @Autowired private val tx: TransactionRunner,
    @Autowired private val tenants: TenantContext,
    @Autowired private val db: DSLContext,
) {
    private val probe: TenancyProbe = TenancyProbe.connect(pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
    // fresh tenants per test: the database is shared by the class, so no test reads another test's rows
    private val tenantA = TenantId(UUID.randomUUID())
    private val tenantB = TenantId(UUID.randomUUID())

    // ---- seeding through the probe, as the runtime login under a signed binding ----

    private fun seed(
        tenant: TenantId,
        packType: String,
        version: String,
        from: String,
        to: String?,
        status: String = "ARMED",
        supersedes: String? = null,
    ) = probe.inTenant(tenant) {
        fetchValue(
            "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, effective_from, " +
                "effective_to, min_engine_version, supersedes, registered_by) " +
                "values (?::uuid, ?::uuid, ?, ?, ?, 'TENANT_MAPPING', ?, ?::date, ?::date, '1.0.0', ?, 'tenancy-probe') returning id",
            UUID.randomUUID().toString(), tenant.toString(), "$packType-pack", packType, version, status, from, to, supersedes,
        )
    }

    private fun count(tenant: TenantId?): Long {
        // platform rows are visible to every bound tenant by design; the probe counts tenant-owned rows
        val sql = "select count(*) from rules.pack_registry where tenant_id <> 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid"
        return (if (tenant == null) probe.unbound { fetchValue(sql) } else probe.inTenant(tenant) { fetchValue(sql) }) as Long
    }

    private fun resolveAt(
        tenant: TenantId,
        type: PackType,
        date: String,
        preceding: String? = null,
    ): PackResolution =
        tenants.bind(tenant, "rules-test") {
            tx.inTransaction {
                PackResolver(ArmedPackLookup(db)).resolve(
                    listOf(type),
                    tenant,
                    ResolutionDates(TransactionDate(LocalDate.parse(date)), preceding?.let { TransactionDate(LocalDate.parse(it)) }),
                )
            }
        }

    private fun versionOf(resolution: PackResolution): String =
        resolution.shouldBeInstanceOf<PackResolution.Resolved>().binding.applied.single().version.value

    @Test
    fun `the connection is neither a superuser nor able to bypass RLS and runs as rules_runtime`() {
        probe.fetchValue("select (rolsuper or rolbypassrls) from pg_roles where rolname = current_user") shouldBe false
        probe.fetchValue("select current_user") shouldBe "rules_runtime"
        probe.fetchValue("select count(*) from pg_tables where tableowner = current_user") shouldBe 0L
        probe.fetchValue("select tableowner from pg_tables where schemaname = 'rules' and tablename = 'pack_registry'") shouldBe "rules_migrate"
    }

    @Test
    fun `a cross-tenant read returns zero rows - tenant A reading tenant B rows`() {
        seed(tenantB, "MAPPING", "b-1", "2026-01-01", null)
        count(tenantB) shouldBe 1L // not vacuous
        count(tenantA) shouldBe 0L
        count(null) shouldBe 0L // no tenant bound: fails closed
        probe.inTenant(tenantA) { fetchValue("select count(*) from rules.pack_registry where tenant_id = ?::uuid", tenantB.toString()) } shouldBe 0L
        probe.inTenant(tenantA) { fetchValue("select count(*) from rules.pack_entry_source") } shouldBe 0L
    }

    @Test
    fun `INV-AC-1723 the armed pack in force on the document date is resolved through the registry`() {
        seed(tenantA, "MAPPING", "v1", "2026-01-01", "2026-07-01")
        seed(tenantA, "MAPPING", "v2", "2026-07-01", null)
        versionOf(resolveAt(tenantA, PackTypes.MAPPING, "2026-06-20")) shouldBe "v1"
        versionOf(resolveAt(tenantA, PackTypes.MAPPING, "2026-07-15")) shouldBe "v2"
    }

    @Test
    fun `INV-AC-1781 a date before the first armed version resolves nothing and the answer is RUL-002`() {
        seed(tenantA, "SEVERITY_OVERRIDE", "first", "2026-07-01", null)
        val refusal = resolveAt(tenantA, PackType("SEVERITY_OVERRIDE"), "2026-06-20").shouldBeInstanceOf<PackResolution.NoPackEffective>()
        refusal.code shouldBe ErrorCode.RUL_002
        // a version that is not armed is never resolved
        seed(tenantA, "SCENARIO", "draft-only", "2026-01-01", null, status = "DRAFT")
        resolveAt(tenantA, PackType("SCENARIO"), "2026-06-20").shouldBeInstanceOf<PackResolution.NoPackEffective>()
    }

    @Test
    fun `INV-AC-2105 a backfilled document of last November is resolved to the November version`() {
        seed(tenantA, "CALENDAR", "2025.11", "2025-01-01", "2026-01-01")
        seed(tenantA, "CALENDAR", "2026.1", "2026-01-01", null)
        versionOf(resolveAt(tenantA, PackType("CALENDAR"), "2025-11-14")) shouldBe "2025.11"
    }

    @Test
    fun `INV-AC-1726 an armed version refuses an in-place edit and the correction is a new superseding version`() {
        seed(tenantA, "MDE", "v3", "2026-01-01", null)
        shouldThrow<Exception> {
            probe.inTenant(tenantA) { fetchValue("update rules.pack_registry set effective_from = '2026-02-01' where pack_version = 'v3' returning id") }
        }
        shouldThrow<Exception> {
            probe.inTenant(tenantA) { fetchValue("update rules.pack_registry set status = 'DRAFT' where pack_version = 'v3' returning id") }
        }
        seed(tenantA, "MDE", "v4", "2026-04-01", null, status = "ARMED", supersedes = "v3")
        probe.inTenant(tenantA) { fetchValue("select supersedes from rules.pack_registry where pack_version = 'v4'") } shouldBe "v3"
        // v3 stays resolvable for earlier dates, v4 takes over from its own date
        versionOf(resolveAt(tenantA, PackType("MDE"), "2026-03-01")) shouldBe "v3"
        versionOf(resolveAt(tenantA, PackType("MDE"), "2026-04-01")) shouldBe "v4"
    }

    @Test
    fun `INV-AC-1902 a regulatory pack armed on the platform leaves the tenant mapping pack unchanged`() {
        seed(tenantA, "MAPPING", "map-1", "2026-01-01", null)
        platformRegister("SCOPE_1902", "2026.2", "2026-02-01")
        versionOf(resolveAt(tenantA, PackTypes.MAPPING, "2026-03-01")) shouldBe "map-1"
        versionOf(resolveAt(tenantA, PackType("SCOPE_1902"), "2026-03-01")) shouldBe "2026.2"
        probe.inTenant(tenantA) { fetchValue("select status from rules.pack_registry where pack_version = 'map-1'") } shouldBe "ARMED"
    }

    private fun platformRegister(
        packType: String,
        version: String,
        from: String,
    ) = probe.inTenant(TenantId.PLATFORM) {
        fetchValue(
            "select rules.register_platform_pack(?::uuid, ?, ?, ?, 'ARMED', ?::date, null, '1.0.0', null)",
            UUID.randomUUID().toString(), "$packType-pack", packType, version, from,
        )
    }

    @Test
    fun `platform rows are written only through the sys-owned functions`() {
        // the runtime login cannot insert a platform row directly, under any binding
        shouldThrow<Exception> {
            probe.inTenant(TenantId.PLATFORM) {
                fetchValue(
                    "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, effective_from, " +
                        "min_engine_version, registered_by) values (?::uuid, ?::uuid, 'p', 'SCOPE', 'direct', 'REGULATORY', 'ARMED', " +
                        "'2026-01-01', '1.0.0', 'tenancy-probe') returning id",
                    UUID.randomUUID().toString(), TenantId.PLATFORM.toString(),
                )
            }
        }
        // the function refuses a tenant binding and works under the platform binding
        shouldThrow<Exception> {
            probe.inTenant(tenantA) {
                fetchValue(
                    "select rules.register_platform_pack(?::uuid, 'p', 'SCOPE', 'by-tenant', 'ARMED', '2026-01-01'::date, null, '1.0.0', null)",
                    UUID.randomUUID().toString(),
                )
            }
        }
        platformRegister("SCOPE_FN", "via-function", "2026-01-01")
        probe.inTenant(tenantA) { fetchValue("select count(*) from rules.pack_registry where pack_version = 'via-function'") } shouldBe 1L
        // the sys function owner is rules_sys and the execute right is the runtime login's alone
        probe.fetchValue(
            "select pg_get_userbyid(proowner) from pg_proc where proname = 'register_platform_pack' and pronamespace = 'rules'::regnamespace",
        ) shouldBe "rules_sys"
        probe.fetchValue("select has_function_privilege('public', 'rules.register_platform_pack(uuid,text,text,text,text,date,date,text,text)', 'execute')") shouldBe false
    }

    @Test
    fun `INV-AC-2182 the registry holds metadata and activation state and no artefact content`() {
        val tables = probe.fetchRows("select table_name from information_schema.tables where table_schema = 'rules'").map { it["table_name"] as String }
        // deny by default: every table of the schema is classified below, an unknown table is a finding
        val registry = setOf("pack_registry", "pack_entry_source")
        val platformLibraries =
            setOf("binding_key", "flyway_schema_history", "outbox", "outbox_sequence", "inbox", "event_dead_letter", "consumer_sequence",
                "consumer_offset", "consumer_replay", "consumer_replay_marker", "consumer_source_seen", "outbox_claim_v",
                "event_dead_letter_triage_v")
        val unknown = tables.filter { it !in registry && it !in platformLibraries }
        unknown shouldBe emptyList()
        // the registry tables hold no document-shaped column: no json, jsonb, bytea
        val blobs =
            probe.fetchRows(
                "select table_name, column_name, data_type from information_schema.columns where table_schema = 'rules' " +
                    "and table_name in ('pack_registry', 'pack_entry_source') and data_type in ('json', 'jsonb', 'bytea')",
            )
        blobs shouldBe emptyList()
        val names = probe.fetchRows("select column_name from information_schema.columns where table_schema = 'rules' and table_name in ('pack_registry', 'pack_entry_source')").map { it["column_name"] as String }
        names.none { it in setOf("contents", "content", "manifest", "signature", "checksums", "artefact", "artifact") } shouldBe true
    }

    companion object {
        private val secrets: Path =
            Files.createTempDirectory("rules-it-secrets").also { root ->
                Files.createDirectories(root.resolve("db"))
                Files.createDirectories(root.resolve("tenancy"))
                Files.writeString(root.resolve("db/rules_runtime"), "rules_runtime_pw")
                Files.writeString(root.resolve("db/rules_migrate"), "rules_migrate_pw")
                // 32 bytes of 0x01: the key TenancyProbe signs its bindings with (SignedBinding.key())
                Files.writeString(root.resolve("tenancy/binding-key-current"), "\u0001".repeat(32))
            }

        private val initSql: Path =
            Files.createTempFile("rules-init", ".sql").also { file ->
                val key = "01".repeat(32)
                Files.writeString(
                    file,
                    """
                    CREATE ROLE rules_migrate LOGIN PASSWORD 'rules_migrate_pw';
                    CREATE ROLE rules_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'rules_runtime_pw';
                    CREATE ROLE rules_sys NOLOGIN;
                    GRANT rules_sys TO rules_migrate;
                    CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;
                    CREATE SCHEMA rules AUTHORIZATION rules_migrate;
                    GRANT USAGE, CREATE ON SCHEMA rules TO rules_sys;
                    GRANT USAGE ON SCHEMA rules TO rules_runtime;
                    SET ROLE rules_sys;
                    ${BindingKeyMigration.ddl("rules", "rules_sys", "rules_runtime")}
                    INSERT INTO rules.binding_key (slot, secret) VALUES ('current', decode('$key', 'hex'));
                    RESET ROLE;
                    REVOKE CREATE ON SCHEMA rules FROM rules_sys;
                    """.trimIndent(),
                )
                file.toFile().setReadable(true, false)
            }

        /** Testcontainers needs a concrete subclass for its fluent `self()` typing. */
        private class Postgres(
            image: DockerImageName,
        ) : PostgreSQLContainer<Postgres>(image)

        /** Started once for the class and stopped with the JVM (Ryuk); the migrations run in [props]. */
        @JvmStatic
        private val pg: Postgres =
            Postgres(DockerImageName.parse(System.getProperty("gst.pg.image", "postgres:18")))
                .withCopyFileToContainer(MountableFile.forHostPath(initSql, 420), "/docker-entrypoint-initdb.d/00-init.sql")
                .also { it.start() }

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            // The service never migrates at start-up: the test migrates first through MigrationRunner as rules_migrate,
            // as the migration Job does (TPL-12, ARC-20).
            MigrationRunner
                .run(
                    "--spring.datasource.url=${pg.jdbcUrl}",
                    "--gst.bindings.secret-store.mount-root=$secrets",
                    "--spring.flyway.user=rules_migrate",
                    "--spring.flyway.schemas=rules",
                    "--spring.flyway.default-schema=rules",
                    // The schema already holds the signed-binding objects the sys login created (INV-Q-6580), so Flyway
                    // needs a baseline below V1 (whoever provisions the installation decides this, not the test).
                    "--spring.flyway.baseline-on-migrate=true",
                    "--spring.flyway.baseline-version=0",
                    "--service-runtime.flyway.credential=db/rules_migrate",
                    "--gst.service=rules-svc",
                    "--service-runtime.modules.schema=rules",
                ).close()
            registry.add("spring.datasource.url") { pg.jdbcUrl }
            registry.add("gst.bindings.secret-store.mount-root") { secrets.toString() }
            registry.add("gst.instance-id") { "rules-svc-0" }
            registry.add("service-runtime.workload-identity.audience") { "rules-svc" }
            registry.add("service-runtime.pool.profile") { "minimal" }
            registry.add("management.server.port") { "0" }
            registry.add("server.port") { "0" }
        }
    }
}
