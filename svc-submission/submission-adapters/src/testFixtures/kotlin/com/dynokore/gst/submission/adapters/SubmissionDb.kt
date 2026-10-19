package com.dynokore.gst.submission.adapters

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/**
 * A PostgreSQL 18 database with the roles of INV-AC-2175, the signed-binding functions of the schema and the Flyway
 * migrations of this module applied by `submission_migrate` (TPL-12, TPL-14). The binding functions are installed
 * here, as a test helper, by the sys login (coordinator rulings INV-Q-6586 and INV-Q-6663): the migrations do not run
 * the generator.
 */
object SubmissionDb {
    const val SCHEMA = "submission"
    const val MIGRATE = "submission_migrate"
    const val RUNTIME = "submission_runtime"
    const val SYS = "submission_sys"
    const val MIGRATE_PASSWORD = "submission_migrate_pw"
    const val RUNTIME_PASSWORD = "submission_runtime_pw"

    /** postgres:18 pinned by digest, as platform-libs' testcontainers-pins table pins it (INV-T-5604). */
    private const val IMAGE = "postgres:18@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336"

    // Started once per JVM. A failed start or provisioning is kept and rethrown, never retried (a retry would start
    // another container per access), and the container is stopped.
    private val started: Result<PostgreSQLContainer<*>> by lazy {
        runCatching {
            val postgres = PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            postgres.start()
            runCatching { postgres.provision() }.onFailure { postgres.stop() }.getOrThrow()
            postgres
        }
    }

    private val container: PostgreSQLContainer<*> get() = started.getOrThrow()

    val jdbcUrl: String get() = container.jdbcUrl

    /** The installation key the runners sign with and the schema's `bound_tenant()` verifies. */
    val bindingKey: ByteArray = BINDING_SECRET.toByteArray(Charsets.UTF_8)

    /** The value of the `tenancy/binding-key-current` secret: the key is its UTF-8 bytes (service-runtime README). */
    const val BINDING_SECRET = "submission-test-binding-key-0001"

    fun superuser(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    fun connect(
        user: String,
        password: String,
    ): Connection = DriverManager.getConnection(container.jdbcUrl, user, password)

    private fun resource(name: String): String =
        checkNotNull(SubmissionDb::class.java.getResource(name)) { "missing resource $name" }.readText()

    private fun PostgreSQLContainer<*>.provision() {
        val hex = bindingKey.joinToString("") { "%02x".format(it) }
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(resource("/db/roles.sql"))
                statement.execute("create schema $SCHEMA authorization $MIGRATE")
                statement.execute("create extension if not exists pgcrypto schema public")
                statement.execute("grant usage on schema $SCHEMA to $RUNTIME")
                statement.execute("grant usage, create on schema $SCHEMA to $SYS")
                statement.execute("set role $SYS")
                statement.execute(BindingKeyMigration.ddl(SCHEMA, SYS, RUNTIME))
                statement.execute("insert into $SCHEMA.binding_key (slot, secret) values ('current', decode('$hex', 'hex'))")
                statement.execute("reset role")
                statement.execute("revoke create on schema $SCHEMA from $SYS")
            }
        }
        Flyway.configure()
            .dataSource(jdbcUrl, MIGRATE, MIGRATE_PASSWORD)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .locations("classpath:db/migration")
            // the installer left the binding functions in the schema: baseline it at 0 so V1 still runs
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate()
    }
}
