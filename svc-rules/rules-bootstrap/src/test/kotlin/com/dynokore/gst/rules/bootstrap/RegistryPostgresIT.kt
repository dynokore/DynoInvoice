package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.adapters.persistence.ArmedPackLookup
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_ARTEFACT
import com.dynokore.gst.rules.bootstrap.SchemaInspection.Column
import com.dynokore.gst.rules.bootstrap.SchemaInspection.Grant
import com.dynokore.gst.rules.bootstrap.SchemaInspection.Table
import com.dynokore.gst.rules.domain.resolution.PackResolution
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.ResolutionDates
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.PackRegistryMigration
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
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

private const val PLATFORM = "ffffffff-ffff-ffff-ffff-ffffffffffff"
private const val BINDING_KEY_HEX = "0101010101010101010101010101010101010101010101010101010101010101"
private const val REGISTER_ARGUMENTS = "uuid,text,text,text,text,date,date,text,text"

private const val INSERT_TENANT_PACK =
    "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
        "effective_from, effective_to, min_engine_version, supersedes, registered_by) " +
        "values (?::uuid, ?::uuid, ?, ?, ?, 'TENANT_MAPPING', ?, ?::date, ?::date, '1.0.0', ?, 'tenancy-probe') " +
        "returning id"

private const val INSERT_ARTEFACT =
    "insert into rules.pack_artefact (id, pack_id, pack_type, pack_version, tenant_id, effective_from, effective_to, " +
        "min_engine_version, manifest, checksums, signature, contents, trusted_anchor, registered_by) " +
        "values (?::uuid, ?, ?, ?, ?::uuid, '2026-01-01'::date, null, '1.0.0', '{}'::jsonb, '{}'::jsonb, ?, " +
        "'{}'::jsonb, 'anchor', 'tenancy-probe') returning id"

private const val REGISTER_KERNEL_PACK =
    "select rules.register_platform_pack(?::uuid, ?, ?, ?, ?::date, null, '1.0.0', ?::jsonb, ?::jsonb, ?, ?::jsonb, ?)"

/** The two metadata tables rules-svc owns, the holder copy beside them, and the tables of the libraries and Flyway. */
private val REGISTRY_TABLES = SchemaInspection.REGISTRY_TABLES
private val HOLDER_TABLES = setOf(SchemaInspection.HOLDER)
private val LIBRARY_TABLES =
    setOf(
        "binding_key",
        "flyway_schema_history",
        "outbox",
        "outbox_sequence",
        "inbox",
        "event_dead_letter",
        "consumer_sequence",
        "consumer_offset",
        "consumer_replay",
        "consumer_replay_marker",
        "consumer_source_seen",
        "outbox_claim_v",
        "event_dead_letter_triage_v",
    )

/**
 * The registry against real PostgreSQL, the way TPL-14 builds it: the roles of TPL-12 (rules_migrate owns the schema
 * and every table, rules_runtime is the one runtime login, rules_sys owns the definer functions), the migrations run
 * through service-runtime's MigrationRunner as rules_migrate, the service on its runtime login, and a TenancyProbe on
 * the same login for the catalog and row-level-security probes. No other raw SQL, no connection of the test's own.
 */
@SpringBootTest(classes = [RulesApplication::class])
class RegistryPostgresIT(
    @Autowired private val tx: TransactionRunner,
    @Autowired private val tenants: TenantContext,
    @Autowired private val db: DSLContext,
    @Autowired private val environment: Environment,
) {
    private val probe: TenancyProbe = TenancyProbe.connect(pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")

    // fresh tenants per test: the database is shared by the class, so no test reads another test's rows
    private val tenantA = TenantId(UUID.randomUUID())
    private val tenantB = TenantId(UUID.randomUUID())

    /** One tenant pack row as the probe inserts it: the version in its dates, state and place in the chain. */
    private data class Seed(
        val packType: String,
        val version: String,
        val from: String,
        val to: String? = null,
        val status: String = "ARMED",
        val supersedes: String? = null,
    )

    private fun seed(
        tenant: TenantId,
        pack: Seed,
    ) = value(
        tenant,
        INSERT_TENANT_PACK,
        UUID.randomUUID().toString(),
        tenant.toString(),
        "${pack.packType}-pack",
        pack.packType,
        pack.version,
        pack.status,
        pack.from,
        pack.to,
        pack.supersedes,
    )

    private fun count(tenant: TenantId?): Long {
        // platform rows are visible to every bound tenant by design; the probe counts tenant-owned rows
        val sql = "select count(*) from rules.pack_registry where tenant_id <> '$PLATFORM'::uuid"
        val count = if (tenant == null) probe.unbound { fetchValue(sql) } else value(tenant, sql)
        return count as Long
    }

    private fun value(
        tenant: TenantId,
        sql: String,
        vararg binds: Any?,
    ): Any? = probe.inTenant(tenant) { fetchValue(sql, *binds) }

    private fun resolveAt(
        tenant: TenantId,
        type: PackType,
        date: String,
    ): PackResolution =
        tenants.bind(tenant, "rules-test") {
            tx.inTransaction {
                val dates = ResolutionDates(TransactionDate(LocalDate.parse(date)))
                PackResolver(ArmedPackLookup(db)).resolve(listOf(type), tenant, dates)
            }
        }

    private fun versionOf(resolution: PackResolution): String =
        resolution
            .shouldBeInstanceOf<PackResolution.Resolved>()
            .binding.applied
            .single()
            .version.value

    private fun platformRegister(
        packType: String,
        version: String,
        from: String,
    ) = value(
        TenantId.PLATFORM,
        "select rules.register_platform_pack(?::uuid, ?, ?, ?, 'ARMED', ?::date, null, '1.0.0', null)",
        UUID.randomUUID().toString(),
        "$packType-pack",
        packType,
        version,
        from,
    )

    @Test
    fun `the connection is neither a superuser nor able to bypass RLS and runs as rules_runtime`() {
        probe.fetchValue("select (rolsuper or rolbypassrls) from pg_roles where rolname = current_user") shouldBe false
        probe.fetchValue("select current_user") shouldBe "rules_runtime"
        probe.fetchValue("select count(*) from pg_tables where tableowner = current_user") shouldBe 0L
        probe.fetchValue(
            "select tableowner from pg_tables where schemaname = 'rules' and tablename = 'pack_registry'",
        ) shouldBe "rules_migrate"
    }

    @Test
    fun `a cross-tenant read returns zero rows - tenant A reading tenant B rows`() {
        seed(tenantB, Seed("MAPPING", "b-1", "2026-01-01", null))
        count(tenantB) shouldBe 1L // not vacuous
        count(tenantA) shouldBe 0L
        count(null) shouldBe 0L // no tenant bound: fails closed
        val byOwner = "select count(*) from rules.pack_registry where tenant_id = ?::uuid"
        value(tenantA, byOwner, tenantB.toString()) shouldBe 0L
        value(tenantA, "select count(*) from rules.pack_entry_source") shouldBe 0L
    }

    @Test
    fun `INV-AC-1723 the armed pack in force on the document date is resolved through the registry`() {
        seed(tenantA, Seed("MAPPING", "v1", "2026-01-01", "2026-07-01"))
        seed(tenantA, Seed("MAPPING", "v2", "2026-07-01", null))
        versionOf(resolveAt(tenantA, PackTypes.MAPPING, "2026-06-20")) shouldBe "v1"
        versionOf(resolveAt(tenantA, PackTypes.MAPPING, "2026-07-15")) shouldBe "v2"
    }

    @Test
    fun `INV-AC-1781 a date before the first armed version resolves nothing and the answer is RUL-002`() {
        seed(tenantA, Seed("SEVERITY_OVERRIDE", "first", "2026-07-01", null))
        val early = resolveAt(tenantA, PackType("SEVERITY_OVERRIDE"), "2026-06-20")
        early.shouldBeInstanceOf<PackResolution.NoPackEffective>().code shouldBe ErrorCode.RUL_002
        // a version that is not armed is never resolved
        seed(tenantA, Seed("SCENARIO", "draft-only", "2026-01-01", null, status = "DRAFT"))
        resolveAt(tenantA, PackType("SCENARIO"), "2026-06-20").shouldBeInstanceOf<PackResolution.NoPackEffective>()
    }

    @Test
    fun `INV-AC-2105 a backfilled document of last November is resolved to the November version`() {
        seed(tenantA, Seed("CALENDAR", "2025.11", "2025-01-01", "2026-01-01"))
        seed(tenantA, Seed("CALENDAR", "2026.1", "2026-01-01", null))
        versionOf(resolveAt(tenantA, PackType("CALENDAR"), "2025-11-14")) shouldBe "2025.11"
    }

    @Test
    fun `INV-AC-1726 an armed version refuses an in-place edit and the correction is a new superseding version`() {
        seed(tenantA, Seed("MDE", "v3", "2026-01-01", null))
        val edit = "update rules.pack_registry set {set} where pack_version = 'v3' returning id"
        shouldThrow<Exception> { value(tenantA, edit.replace("{set}", "effective_from = '2026-02-01'")) }
        shouldThrow<Exception> { value(tenantA, edit.replace("{set}", "status = 'DRAFT'")) }
        seed(tenantA, Seed("MDE", "v4", "2026-04-01", null, status = "ARMED", supersedes = "v3"))
        value(tenantA, "select supersedes from rules.pack_registry where pack_version = 'v4'") shouldBe "v3"
        // v3 stays resolvable for earlier dates, v4 takes over from its own date
        versionOf(resolveAt(tenantA, PackType("MDE"), "2026-03-01")) shouldBe "v3"
        versionOf(resolveAt(tenantA, PackType("MDE"), "2026-04-01")) shouldBe "v4"
    }

    @Test
    fun `INV-AC-1902 a regulatory pack armed on the platform leaves the tenant mapping pack unchanged`() {
        seed(tenantA, Seed("MAPPING", "map-1", "2026-01-01", null))
        platformRegister("SCOPE_1902", "2026.2", "2026-02-01")
        versionOf(resolveAt(tenantA, PackTypes.MAPPING, "2026-03-01")) shouldBe "map-1"
        versionOf(resolveAt(tenantA, PackType("SCOPE_1902"), "2026-03-01")) shouldBe "2026.2"
        value(tenantA, "select status from rules.pack_registry where pack_version = 'map-1'") shouldBe "ARMED"
    }

    @Test
    fun `platform rows are written only through the sys-owned functions`() {
        // the runtime login cannot insert a platform row directly, under any binding
        shouldThrow<Exception> {
            value(
                TenantId.PLATFORM,
                INSERT_TENANT_PACK.replace("'TENANT_MAPPING'", "'REGULATORY'"),
                UUID.randomUUID().toString(),
                PLATFORM,
                "p",
                "SCOPE",
                "direct",
                "ARMED",
                "2026-01-01",
                null,
                null,
            )
        }
        // the function refuses a tenant binding and works under the platform binding
        shouldThrow<Exception> {
            value(
                tenantA,
                "select rules.register_platform_pack(?::uuid, 'p', 'SCOPE', 'by-tenant', 'ARMED', " +
                    "'2026-01-01'::date, null, '1.0.0', null)",
                UUID.randomUUID().toString(),
            )
        }
        platformRegister("SCOPE_FN", "via-function", "2026-01-01")
        value(tenantA, "select count(*) from rules.pack_registry where pack_version = 'via-function'") shouldBe 1L
        // the sys function owner is rules_sys and the execute right is not PUBLIC's
        // both overloads of the name (nine and twelve parameters) are owned by rules_sys
        names(
            "select pg_get_userbyid(proowner) as n from pg_proc " +
                "where proname = 'register_platform_pack' and pronamespace = 'rules'::regnamespace",
        ) shouldBe setOf("rules_sys")
        probe.fetchValue(
            "select has_function_privilege('public', 'rules.register_platform_pack($REGISTER_ARGUMENTS)', 'execute')",
        ) shouldBe false
    }

    @Test
    fun `INV-AC-2182 the registry holds metadata and activation state and no artefact content`() {
        val tables =
            probe
                .fetchRows("select table_name from information_schema.tables where table_schema = 'rules'")
                .map { it["table_name"] as String }
        // deny by default: every table of the schema is classified above, an unknown table is a finding
        tables.filter { it !in REGISTRY_TABLES && it !in HOLDER_TABLES && it !in LIBRARY_TABLES } shouldBe emptyList()
        // the registry tables hold no document-shaped column: no json, jsonb, bytea
        val registry = "table_schema = 'rules' and table_name in ('pack_registry', 'pack_entry_source')"
        probe.fetchRows(
            "select table_name, column_name from information_schema.columns where $registry " +
                "and data_type in ('json', 'jsonb', 'bytea')",
        ) shouldBe emptyList()
        val names =
            probe
                .fetchRows("select column_name from information_schema.columns where $registry")
                .map { it["column_name"] as String }
        val documentColumns = setOf("contents", "content", "manifest", "signature", "checksums", "artefact")
        names.none { it in documentColumns } shouldBe true
    }

    private fun catalogTables(): List<Table> =
        probe
            .fetchRows(
                "select n.nspname as s, c.relname as t from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                    "where c.relkind in ('r', 'p') and n.nspname = 'rules'",
            ).map { Table(it["s"] as String, it["t"] as String) }

    private fun catalogColumns(): List<Column> =
        probe
            .fetchRows(
                "select n.nspname as s, c.relname as t, a.attname as a, y.typname as y from pg_attribute a " +
                    "join pg_class c on c.oid = a.attrelid join pg_namespace n on n.oid = c.relnamespace " +
                    "join pg_type y on y.oid = a.atttypid " +
                    "where a.attnum > 0 and not a.attisdropped and c.relkind in ('r', 'p') and n.nspname = 'rules'",
            ).map { Column(Table(it["s"] as String, it["t"] as String), it["a"] as String, it["y"] as String) }

    private fun columnsOf(table: String): Set<String> =
        catalogColumns().filter { it.table == Table("rules", table) }.map { it.name }.toSet()

    private fun names(sql: String): Set<String> = probe.fetchRows(sql).map { it["n"] as String }.toSet()

    @Test
    fun `INV-AC-2182 the holder copy is rules pack_artefact with the kernel columns`() {
        SchemaInspection.tableFindings(catalogTables(), catalogColumns(), LIBRARY_TABLES) shouldBe emptyList()
        columnsOf("pack_artefact") shouldBe SchemaInspection.HOLDER_COLUMNS
        columnsOf("pack_artefact").contains("version") shouldBe false
        probe.fetchValue(
            "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                "where c.relname = 'pack_artefact' and n.nspname <> 'rules'",
        ) shouldBe 0L
        // the V3 table is the kernel table for schema rules, owned by the migrating role
        probe.fetchValue(
            "select tableowner from pg_tables where schemaname = 'rules' and tablename = 'pack_artefact'",
        ) shouldBe "rules_migrate"
    }

    @Test
    fun `INV-AC-2182 the metadata tables keep their columns`() {
        columnsOf("pack_registry") shouldBe
            setOf(
                "id",
                "tenant_id",
                "pack_id",
                "pack_type",
                "pack_version",
                "layer",
                "status",
                "effective_from",
                "effective_to",
                "min_engine_version",
                "supersedes",
                "registered_by",
                "registered_at",
            )
        columnsOf("pack_entry_source") shouldBe
            setOf(
                "id",
                "tenant_id",
                "pack_type",
                "pack_version",
                "entry_id",
                "source_class",
                "source_citation",
                "accepted",
            )
    }

    @Test
    fun `INV-AC-2182 the metadata tables keep their policies`() {
        val policies = "select policyname as n from pg_policies where schemaname = 'rules' and tablename = "
        names(policies + "'pack_registry'") shouldBe
            setOf(
                "pack_registry_tenant_read",
                "pack_registry_tenant_insert",
                "pack_registry_tenant_update",
                "pack_registry_platform_register",
                "pack_registry_platform_select",
                "pack_registry_platform_advance",
            )
        names(policies + "'pack_entry_source'") shouldBe
            setOf(
                "pack_entry_source_tenant_read",
                "pack_entry_source_tenant_insert",
                "pack_entry_source_tenant_update",
                "pack_entry_source_platform_register",
            )
    }

    @Test
    fun `INV-AC-2182 the metadata tables keep their triggers and the nine-parameter function beside the kernel one`() {
        val triggers =
            "select t.tgname as n from pg_trigger t join pg_class c on c.oid = t.tgrelid " +
                "where not t.tgisinternal and c.relnamespace = 'rules'::regnamespace and c.relname = "
        names(triggers + "'pack_registry'") shouldBe
            setOf("pack_registry_registered_by_insert", "pack_registry_frozen_update")
        names(triggers + "'pack_entry_source'") shouldBe setOf("pack_entry_source_frozen")
        // the nine-parameter function of INV-T-4965 and the kernel's twelve-parameter one share a name
        names(
            "select array_to_string(proargtypes::regtype[], ',') as n from pg_proc " +
                "where pronamespace = 'rules'::regnamespace and proname = 'register_platform_pack'",
        ) shouldBe setOf(REGISTER_ARGUMENTS, PackRegistryMigration.REGISTER_PLATFORM_PACK_ARGUMENTS.replace(" ", ""))
        names(
            "select array_to_string(proargtypes::regtype[], ',') as n from pg_proc " +
                "where pronamespace = 'rules'::regnamespace and proname = 'advance_platform_pack'",
        ) shouldBe setOf("text,text,text,date")
    }

    @Test
    fun `INV-AC-2182 the holder copy is granted to the runtime login for insert and select only`() {
        val rows =
            probe.fetchRows(
                "select pg_get_userbyid(c.relowner) as owner, case when x.grantee = 0 then 'PUBLIC' " +
                    "else pg_get_userbyid(x.grantee) end as grantee, x.privilege_type as p, x.is_grantable as g, " +
                    "null::text as col from pg_class c, aclexplode(c.relacl) x " +
                    "where c.oid = 'rules.pack_artefact'::regclass " +
                    "union all select pg_get_userbyid(c.relowner), case when x.grantee = 0 then 'PUBLIC' " +
                    "else pg_get_userbyid(x.grantee) end, x.privilege_type, x.is_grantable, a.attname::text " +
                    "from pg_attribute a join pg_class c on c.oid = a.attrelid, aclexplode(a.attacl) x " +
                    "where c.oid = 'rules.pack_artefact'::regclass and a.attacl is not null",
            )
        rows.map { it["owner"] }.toSet() shouldBe setOf("rules_migrate")
        val grants =
            rows.map { Grant(it["grantee"] as String, it["p"] as String, it["g"] as Boolean, it["col"] as String?) }
        // GAP (merge request): the kernel DDL also grants INSERT to the sys role, whose definer function writes
        // the platform rows
        SchemaInspection.grantFindings(grants, mapOf(SchemaInspection.SYS to setOf("INSERT"))) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-2182 each register_platform_pack writes its own table`() {
        val registryBefore = platformCount("rules.pack_registry")
        val artefactBefore = platformCount("rules.pack_artefact")
        platformRegister("SCOPE_ARITY", "nine", "2026-01-01")
        platformCount("rules.pack_registry") shouldBe registryBefore + 1
        platformCount("rules.pack_artefact") shouldBe artefactBefore
        registerKernelPack("SCOPE_ARITY", "twelve", "2026-01-01")
        platformCount("rules.pack_registry") shouldBe registryBefore + 1
        platformCount("rules.pack_artefact") shouldBe artefactBefore + 1
        // the twelve-parameter function keeps the sys owner and is not executable by PUBLIC
        probe.fetchValue(
            "select has_function_privilege('public', 'rules.register_platform_pack(" +
                PackRegistryMigration.REGISTER_PLATFORM_PACK_ARGUMENTS + ")', 'execute')",
        ) shouldBe false
    }

    private fun platformCount(table: String): Long =
        value(TenantId.PLATFORM, "select count(*) from $table where tenant_id = '$PLATFORM'::uuid") as Long

    private fun registerKernelPack(
        packType: String,
        version: String,
        from: String,
    ) = value(
        TenantId.PLATFORM,
        REGISTER_KERNEL_PACK,
        UUID.randomUUID().toString(),
        "$packType-pack",
        packType,
        version,
        from,
        "{\"id\": 1}",
        "{\"contents\": \"ab\"}",
        byteArrayOf(1, 2, 3),
        "{\"rules\": []}",
        "anchor-1",
    )

    private fun seedArtefact(
        tenant: TenantId,
        version: String,
    ) = value(
        tenant,
        INSERT_ARTEFACT,
        UUID.randomUUID().toString(),
        "mapping-pack",
        "MAPPING",
        version,
        tenant.toString(),
        byteArrayOf(9),
    )

    private fun artefactVersions(tenant: TenantId): Set<String> =
        tenants.bind(tenant, "rules-test") {
            tx.inTransaction {
                db
                    .select(PACK_ARTEFACT.PACK_VERSION)
                    .from(PACK_ARTEFACT)
                    .fetch(PACK_ARTEFACT.PACK_VERSION)
                    .filterNotNull()
                    .toSet()
            }
        }

    @Test
    fun `INV-AC-2182 the reference table is declared and a tenant reads the platform rows and its own`() {
        PackRegistryMigration.referenceTable("rules", "pack_artefact") shouldBe "rules.pack_artefact"
        val declared = Binder.get(environment).bind("inv.tenancy.reference-tables", Bindable.listOf(String::class.java))
        declared.get().contains(PackRegistryMigration.referenceTable("rules", "pack_artefact")) shouldBe true
        registerKernelPack("MAPPING", "platform-v", "2026-01-01")
        seedArtefact(tenantA, "tenant-a-v")
        seedArtefact(tenantB, "tenant-b-v")
        val seenByA = artefactVersions(tenantA)
        seenByA.contains("platform-v") shouldBe true
        seenByA.contains("tenant-a-v") shouldBe true
        seenByA.contains("tenant-b-v") shouldBe false
        // no valid binding: no row, not even a platform row
        probe.unbound { fetchValue("select count(*) from rules.pack_artefact") } shouldBe 0L
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
                // statement groups joined outside SQL text (INV-REQ-854 b5): no generated SQL is spliced into a literal
                val roles =
                    listOf(
                        "CREATE ROLE rules_migrate LOGIN PASSWORD 'rules_migrate_pw';",
                        "CREATE ROLE rules_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'rules_runtime_pw';",
                        "CREATE ROLE rules_sys NOLOGIN;",
                        "GRANT rules_sys TO rules_migrate;",
                        "CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;",
                        "CREATE SCHEMA rules AUTHORIZATION rules_migrate;",
                        "GRANT USAGE, CREATE ON SCHEMA rules TO rules_sys;",
                        "GRANT USAGE ON SCHEMA rules TO rules_runtime;",
                    )
                val binding =
                    listOf(
                        "SET ROLE rules_sys;",
                        BindingKeyMigration.ddl("rules", "rules_sys", "rules_runtime"),
                        // 32 bytes of 0x01: the key TenancyProbe signs its bindings with (SignedBinding.key())
                        "INSERT INTO rules.binding_key (slot, secret) VALUES ('current', decode('" + BINDING_KEY_HEX +
                            "', 'hex'));",
                        "RESET ROLE;",
                        "REVOKE CREATE ON SCHEMA rules FROM rules_sys;",
                    )
                Files.writeString(file, (roles + binding).joinToString("\n"))
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
                .withCopyFileToContainer(
                    MountableFile.forHostPath(initSql, 420),
                    "/docker-entrypoint-initdb.d/00-init.sql",
                ).also { it.start() }

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            // The service never migrates at start-up: the test migrates first through MigrationRunner as
            // rules_migrate, as the migration Job does (TPL-12, ARC-20).
            MigrationRunner
                .run(
                    "--spring.datasource.url=${pg.jdbcUrl}",
                    "--gst.bindings.secret-store.mount-root=$secrets",
                    "--spring.flyway.user=rules_migrate",
                    "--spring.flyway.schemas=rules",
                    "--spring.flyway.default-schema=rules",
                    // The schema already holds the signed-binding objects the sys login created (INV-Q-6580),
                    // so Flyway needs a baseline below V1 (the installation decides this, not the test).
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
