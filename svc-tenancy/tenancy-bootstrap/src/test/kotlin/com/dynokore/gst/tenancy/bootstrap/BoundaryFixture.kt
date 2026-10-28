package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.platform.configresolver.ConfigSchema
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.SqlIdentifier
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.flywaydb.core.Flyway
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

/** One PostgreSQL with the service schema migrated as the installation does, and the context runner over it. */
object BoundaryFixture {
    private const val RUNTIME_PASSWORD = "runtime-password-for-tests"
    val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17").withLabel("inv.agent", "c-inv-t-4927")

    /**
     * The tables config-resolver's ConfigSchema.ddl creates (a platform library, INV-T-4926). Their `entity_id` is null
     * for a setting at tenant or group scope by design, so the per-entity rules of INV-AC-1714 and INV-AC-2041 cover
     * the service's own tables only. Exact names; a table added to this list needs a reason here.
     */
    private val libraryTables = listOf("config_setting", "config_resolved_value", "config_relaxation_acknowledgement")
    private val notLibrary = libraryTables.joinToString(",") { "'$it'" }

    private val migrationsDir: Path = Path.of("..", "tenancy-adapters", "src", "main", "resources", "db", "migration")
    private val roles: Path = Path.of("..", "tenancy-adapters", "src", "integrationTest", "resources", "roles.sql")

    init {
        container.start()
        Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.autoCommit = true
            val script = connection.createStatement()
            runFile(roles)
            runFile(bindingFunctions())
            migrate()
            script.execute("ALTER ROLE tenancy_runtime LOGIN PASSWORD '$RUNTIME_PASSWORD'")
            val database = SqlIdentifier.require(container.databaseName, "database")
            script.execute("GRANT CONNECT ON DATABASE $database TO tenancy_runtime")
            script.execute("GRANT USAGE ON SCHEMA tenancy TO tenancy_runtime")
            script.execute(
                "INSERT INTO tenancy.binding_key (slot, secret) " +
                    "VALUES ('current', convert_to('${TestSecrets.BINDING_KEY}', 'UTF8'))",
            )
        }
    }

    /**
     * The migration files applied by Flyway as the superuser of the container, the tool production uses (twelve-factor
     * dev/prod parity, INV-T-10864): a psql run would keep a raw U+FEFF that Flyway drops. The binding functions the
     * installer left in the schema are baselined at 0, so V1 still runs.
     */
    private fun migrate() {
        Flyway
            .configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .schemas("tenancy")
            .defaultSchema("tenancy")
            .locations("filesystem:" + migrationsDir.toAbsolutePath().normalize())
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()
    }

    /**
     * The signed-binding functions of the schema, installed before the migrations as the installation does and as the
     * jOOQ codegen does (INV-Q-7418): the migrations never create them, so the text is tenancy-kernel's own.
     */
    private fun bindingFunctions(): Path {
        val file = Files.createTempDirectory("tenancy-binding").resolve("binding-functions.sql")
        val ddl = BindingKeyMigration.ddl("tenancy", "tenancy_sys", "tenancy_runtime")
        Files.writeString(
            file,
            "CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;\n" +
                "CREATE SCHEMA IF NOT EXISTS tenancy AUTHORIZATION tenancy_sys;\n" +
                "SET ROLE tenancy_sys;\n$ddl\nRESET ROLE;\n",
        )
        file.toFile().deleteOnExit()
        file.parent.toFile().deleteOnExit()
        return file
    }

    /** Runs a SQL file as the container's superuser through psql, stopping at the first error. */
    private fun runFile(file: Path) {
        val target = "/tmp/${file.fileName}"
        container.copyFileToContainer(MountableFile.forHostPath(file), target)
        val result =
            container.execInContainer(
                "psql",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                container.username,
                "-d",
                container.databaseName,
                "-f",
                target,
            )
        check(result.exitCode == 0) { "$file failed: ${result.stderr}" }
    }

    /** The exempt names, and the tables config-resolver's own DDL creates: the first must lie within the second. */
    fun exemptAndLibraryTables(): Pair<Set<String>, Set<String>> {
        val ddl = ConfigSchema.ddl("tenancy", "tenancy_runtime", "tenancy_sys")
        val created =
            Regex("""(?i)create\s+table\s+(?:if\s+not\s+exists\s+)?(?:tenancy\.)?(\w+)""")
                .findAll(ddl)
                .map { it.groupValues[1] }
                .toSet()
        return libraryTables.toSet() to created
    }

    /** A superuser connection, for the assertions that must see every row. */
    fun <T> asSuperuser(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use(block)

    /** The nullability of every `entity_id` column of the tenancy schema: (table, is_nullable). */
    fun entityColumns(): List<Pair<String, String>> =
        asSuperuser { c ->
            c
                .prepareStatement(
                    "select table_name, is_nullable from information_schema.columns " +
                        "where table_schema = 'tenancy' and column_name = 'entity_id' " +
                        "and table_name not in ($notLibrary)",
                ).use { s -> s.executeQuery().use(::textPairs) }
        }

    /** The (table, column) pairs of `entity_id` or `group_id` columns that are nullable. */
    fun nullableScopeColumns(): List<Pair<String, String>> =
        asSuperuser { c ->
            c
                .prepareStatement(
                    "select table_name, column_name from information_schema.columns where table_schema = 'tenancy' " +
                        "and column_name in ('entity_id', 'group_id') and is_nullable = 'YES' " +
                        "and table_name not in ($notLibrary)",
                ).use { s -> s.executeQuery().use(::textPairs) }
        }

    /** Every primary or unique key of the tenancy schema: (table, comma-joined sorted columns). */
    fun keyColumns(): List<Pair<String, String>> =
        asSuperuser { c ->
            c
                .prepareStatement(
                    "select cl.relname, array_to_string(array_agg(a.attname order by a.attname), ',') " +
                        "from pg_constraint k join pg_class cl on cl.oid = k.conrelid " +
                        "join pg_namespace n on n.oid = cl.relnamespace " +
                        "join pg_attribute a on a.attrelid = k.conrelid and a.attnum = any (k.conkey) " +
                        "where n.nspname = 'tenancy' and k.contype in ('p', 'u') group by k.oid, cl.relname",
                ).use { s -> s.executeQuery().use(::textPairs) }
        }

    private fun textPairs(rs: java.sql.ResultSet): List<Pair<String, String>> =
        generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toList()

    /** Outbox rows whose payload mentions [text], read as the superuser. */
    fun outboxRowsMentioning(text: String): Long =
        asSuperuser { c ->
            c.prepareStatement("select count(*) from tenancy.outbox where data::text like ?").use { s ->
                s.setString(1, "%$text%")
                s.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    /** A business unit row written straight to the table as the superuser, with or without its entity. */
    fun insertBusinessUnit(
        tenant: UUID,
        code: String,
        entity: UUID?,
    ) {
        asSuperuser { c ->
            c
                .prepareStatement(
                    "insert into tenancy.business_unit " +
                        "(id, tenant_id, ref, name, code, structure_version, created_at, entity_id) " +
                        "values (gen_random_uuid(), ?, ?, 'x', ?, 1, now(), ?)",
                ).use { s ->
                    s.setObject(1, tenant)
                    s.setString(2, "row-$code")
                    s.setString(3, code)
                    s.setObject(4, entity)
                    s.executeUpdate()
                }
        }
    }

    /** Row counts of the five tenant tables as a runtime session with no tenant bound: (table, count). */
    fun unboundRowCounts(): List<Pair<String, Long>> =
        asRuntime { c ->
            c
                .prepareStatement(
                    "select 'tenant', count(*) from tenancy.tenant union all " +
                        "select 'tenant_group', count(*) from tenancy.tenant_group union all " +
                        "select 'entity', count(*) from tenancy.entity union all " +
                        "select 'business_unit', count(*) from tenancy.business_unit union all " +
                        "select 'short_code', count(*) from tenancy.short_code",
                ).use { s ->
                    s.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) to rs.getLong(2) else null }.toList()
                    }
                }
        }

    /** A connection as the runtime login with nothing bound: forced row-level security hides every row. */
    fun <T> asRuntime(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, "tenancy_runtime", RUNTIME_PASSWORD).use(block)

    fun runner(): WebApplicationContextRunner =
        WebApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(
                BoundaryTestConfiguration::class.java,
                FixtureAdapters::class.java,
                TenancyApplication::class.java,
            ).withPropertyValues(
                "spring.datasource.url=${container.jdbcUrl}",
                "spring.datasource.username=tenancy_runtime",
                "service-runtime.workload-identity.audience=tenancy-svc",
                "gst.service=tenancy-svc",
                "gst.instance-id=tenancy-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.event-loops.enabled=false",
                "gst.bindings.secret-store.mount-root=${TestSecrets.mount(RUNTIME_PASSWORD)}",
                "service-runtime.datasource.credential=db/tenancy_runtime",
                "spring.profiles.active=test",
            )
}
