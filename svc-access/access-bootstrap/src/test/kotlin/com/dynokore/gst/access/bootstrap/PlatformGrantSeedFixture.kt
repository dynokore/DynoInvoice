package com.dynokore.gst.access.bootstrap

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import org.flywaydb.core.Flyway
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Databases for the seed migration test (INV-T-9280): one base database holds every migration before the seed, built
 * by Flyway as `access_migrate`, the role that owns the tables in production; each scenario runs on a clone of it.
 */
object PlatformGrantSeedFixture {
    const val PLATFORM = "ffffffff-ffff-ffff-ffff-ffffffffffff"
    const val CUSTOMER = "00000000-0000-7000-8000-000000000001"
    const val SEED_MIGRATION = "V202610280900__seed_platform_operator_grants.sql"
    const val BEGIN = "-- seed rows begin"
    private const val BASE = "seed_base"
    private const val LAST_BEFORE_SEED = "202610270900"
    private const val IDENTITIES = "select identity_ref from access.identity"
    private val container get() = BoundaryFixture.container
    val migrations: Path = Path.of("..", "access-adapters", "src", "main", "resources", "db", "migration")
    private val counter = AtomicInteger()
    private val clones = mutableListOf<String>()
    private var built = false

    /** The shipped migration text with [rows] inside the marker lines, as a deployment would add them. */
    fun withRows(
        text: String,
        vararg rows: Pair<String, String>,
    ): String =
        text.replace(
            "\n$BEGIN\n",
            "\n$BEGIN\n" + rows.joinToString("") { "    ('${it.first}', '${it.second}'),\n" },
        )

    private fun url(db: String): String = container.jdbcUrl.replaceFirst("/${container.databaseName}", "/$db")

    fun connect(db: String): Connection = DriverManager.getConnection(url(db), container.username, container.password)

    private fun flyway(
        db: String,
        dir: Path,
        target: String?,
    ): Flyway =
        Flyway
            .configure()
            .dataSource(url(db), container.username, container.password)
            .initSql("SET ROLE access_migrate")
            .schemas("access")
            .defaultSchema("access")
            .locations("filesystem:" + dir.toAbsolutePath().normalize())
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .also { if (target != null) it.target(target) }
            .load()

    @Synchronized
    private fun base() {
        if (built) return
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { it.execute("drop database if exists $BASE") }
            c.createStatement().use { it.execute("create database $BASE") }
        }
        connect(BASE).use { c ->
            c.createStatement().use {
                it.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public")
                it.execute("CREATE SCHEMA access AUTHORIZATION access_sys")
                it.execute("SET ROLE access_sys")
                it.execute(BindingKeyMigration.ddl("access", "access_sys", "access_runtime"))
                it.execute("RESET ROLE")
                it.execute("ALTER SCHEMA access OWNER TO access_migrate")
                it.execute("GRANT access_sys TO access_migrate")
            }
        }
        flyway(BASE, migrations, LAST_BEFORE_SEED).migrate()
        // The earlier migrations hand views to access_sys and need its privileges. The seed migration needs none of
        // them and must not use them: access_sys owns access.bound_tenant(), so an inheriting member could run it.
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.execute("REVOKE access_sys FROM access_migrate")
                it.execute("GRANT access_sys TO access_migrate WITH INHERIT FALSE, SET TRUE")
            }
        }
        built = true
    }

    /** A new database with every migration before the seed applied. */
    fun clone(): String {
        base()
        val name = "seed_${counter.incrementAndGet()}"
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { it.execute("create database $name template $BASE") }
        }
        clones += name
        return name
    }

    /** Runs Flyway as `access_migrate` over all migrations, the seed migration being [seed]. */
    fun apply(
        db: String,
        seed: String,
    ) {
        val dir = Files.createTempDirectory("seed-migrations")
        try {
            Files.list(migrations).use { files -> files.forEach { Files.copy(it, dir.resolve(it.fileName)) } }
            Files.writeString(dir.resolve(SEED_MIGRATION), seed)
            flyway(db, dir, null).migrate()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Runs the statements of [text] from the temporary table on, as access_migrate, in one transaction. */
    fun rerun(
        c: Connection,
        text: String,
    ) {
        c.autoCommit = false
        try {
            c.createStatement().use {
                it.execute("SET LOCAL ROLE access_migrate")
                it.execute(text.substring(text.indexOf("CREATE TEMPORARY TABLE")))
            }
            c.commit()
        } finally {
            c.autoCommit = true
        }
    }

    fun drop() {
        BoundaryFixture.asSuperuser { c ->
            (clones + BASE).forEach { c.createStatement().use { s -> s.execute("drop database if exists $it") } }
        }
    }

    fun strings(
        c: Connection,
        sql: String,
    ): List<String> =
        c.createStatement().use { s ->
            s.executeQuery(sql).use { r -> generateSequence { if (r.next()) r.getString(1) else null }.toList() }
        }

    fun count(
        c: Connection,
        table: String,
    ): Long = strings(c, "select count(*) from $table").single().toLong()

    fun pairs(
        c: Connection,
        tenant: String,
    ): List<Pair<String, String>> =
        strings(
            c,
            "select identity_ref || E'\\t' || role from access.role_grant where tenant_id = '$tenant' order by 1",
        ).map { it.substringBefore("\t") to it.substringAfter("\t") }

    fun identities(
        c: Connection,
        tenant: String,
    ): List<String> = strings(c, "$IDENTITIES where tenant_id = '$tenant' order by 1")

    /** Every row of both tables, to compare before and after a second run. */
    fun snapshot(c: Connection): List<String> =
        strings(c, "select tenant_id || '/' || id || '/' || identity_ref || '/' || role from access.role_grant") +
            strings(c, "select tenant_id || '/' || id || '/' || identity_ref from access.identity")

    fun customerIdentity(ref: String): String =
        "insert into access.identity values " +
            "(gen_random_uuid(), '$CUSTOMER', '$ref', 'STAFF', now(), 'u', now(), 'u', 0)"

    fun customerGrant(
        ref: String,
        role: String,
    ): String =
        "insert into access.role_grant values (gen_random_uuid(), '$CUSTOMER', 'g-$role', '$ref', '$role', " +
            "'TENANT', '$CUSTOMER', null, null, now(), null, 'ACTIVE', 'BY_HAND', now(), 'u', now(), 'u', 0)"

    fun insertCustomerRows(c: Connection) {
        c.createStatement().use {
            it.execute(customerIdentity("carol"))
            it.execute(customerGrant("carol", "tenant.admin"))
        }
    }

    /** Lets the runtime login bind a tenant: the signing key and the schema usage the production installation sets. */
    fun enableRuntime(c: Connection) {
        val key = "convert_to('${TestSecrets.BINDING_KEY}', 'UTF8')"
        c.createStatement().use {
            it.execute("insert into access.binding_key (slot, secret) values ('current', $key)")
            it.execute("grant usage on schema access to access_runtime")
        }
    }

    /** The role grants the runtime login reads with [tenant] bound as a signing runner binds it. */
    fun runtimeSees(
        c: Connection,
        tenant: String,
    ): List<String> {
        c.autoCommit = false
        try {
            c.createStatement().use { it.execute("SET LOCAL ROLE access_runtime") }
            val actor = Base64.getUrlEncoder().withoutPadding().encodeToString("u".toByteArray())
            val payload = "$tenant.$actor.${System.currentTimeMillis() / 1000 + 600}"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(TestSecrets.BINDING_KEY.toByteArray(), "HmacSHA256"))
            val signature = mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }
            c.prepareStatement("select set_config('app.binding_sig', ?, true)").use {
                it.setString(1, "$payload.$signature")
                it.execute()
            }
            return strings(c, "select identity_ref || '/' || role from access.role_grant order by 1")
        } finally {
            c.rollback()
            c.autoCommit = true
        }
    }

    private fun policyRows(c: Connection): List<String> =
        strings(
            c,
            "select tablename || '|' || policyname || '|' || cmd || '|' || roles::text || '|' || " +
                "coalesce(qual, 'null') || '|' || coalesce(with_check, 'null') from pg_policies " +
                "where schemaname = 'access' and tablename in ('identity', 'role_grant') order by 1",
        )

    /** Differences between the database and the ruled policy set (INV-Q-11223 gates a, b, c); empty when they agree. */
    fun policyFindings(c: Connection): List<String> {
        val isolation = connect(BASE).use { b -> policyRows(b) }.filter { "|tenant_isolation|" in it }
        val ruled =
            isolation.map { it.replace("{public}", "{access_runtime}") } +
                listOf("identity", "role_grant").map {
                    "$it|platform_seed_insert|INSERT|{access_migrate}|null|(tenant_id = '$PLATFORM'::uuid)"
                }
        val found = mutableListOf<String>()
        if (policyRows(c).sorted() != ruled.sorted()) found += "policies ${policyRows(c)} differ from $ruled"
        val forced =
            "select relname || relrowsecurity || relforcerowsecurity from pg_class " +
                "where oid in ('access.identity'::regclass, 'access.role_grant'::regclass) order by 1"
        strings(c, forced).filter { !it.endsWith("truetrue") }.forEach { found += "security not forced: $it" }
        val execute = "select has_function_privilege('access_migrate', 'access.bound_tenant()', 'EXECUTE')::text"
        if (strings(c, execute) != listOf("false")) found += "access_migrate can execute bound_tenant"
        val definers =
            "select proname from pg_proc where pronamespace = 'access'::regnamespace and prosecdef order by 1"
        if (strings(c, definers) != connect(BASE).use { strings(it, definers) }) found += "a definer function was added"
        return found
    }
}
