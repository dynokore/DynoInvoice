package com.dynokore.gst.evidence.adapters

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * A PostgreSQL database (18 unless a [Version] is named) with the roles of INV-AC-2175, the signed-binding functions of the schema and the Flyway
 * migrations of this module applied by `evidence_migrate` (TPL-12, TPL-14). The roles (`db/roles.sql`) and the
 * binding functions (`db/provision.sql`, installed by the sys login as the installer does; coordinator rulings
 * INV-Q-6586 and INV-Q-6663) are SQL files the postgres image runs as superuser when it initialises. This class holds
 * no SQL text: the migrations do not run the binding generator.
 */
object EvidenceDb {
    const val SCHEMA = "evidence"
    const val MIGRATE = "evidence_migrate"
    const val RUNTIME = "evidence_runtime"
    const val SYS = "evidence_sys"
    const val MIGRATE_PASSWORD = "evidence_migrate_pw"
    const val RUNTIME_PASSWORD = "evidence_runtime_pw"

    /** The value of the `tenancy/binding-key-current` secret: the key is its UTF-8 bytes (service-runtime README). */
    const val BINDING_SECRET = "evidence-test-binding-key-0001"

    private const val INIT_DIRECTORY = "/docker-entrypoint-initdb.d/"

    /**
     * The PostgreSQL majors the database tests run on (INV-Q-11759), each image pinned by the digest of the table
     * `testcontainers-pins/README.md` of platform-libs (INV-T-5604). A container starts lazily, once per JVM. A failed
     * start or migration is kept and rethrown, never retried (a retry would start another container per access), and
     * the container is stopped.
     */
    enum class Version(
        val major: Int,
        val image: String,
    ) {
        PG16(16, "postgres:16@sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d"),
        PG17(17, "postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3"),
        PG18(18, "postgres:18@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336"),
        ;

        internal val started: Result<PostgreSQLContainer<*>> by lazy {
            runCatching {
                val postgres =
                    PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                        .withCopyFileToContainer(
                            MountableFile.forClasspathResource("db/roles.sql"),
                            "${INIT_DIRECTORY}00-roles.sql",
                        ).withCopyFileToContainer(
                            MountableFile.forClasspathResource("db/provision.sql"),
                            "${INIT_DIRECTORY}01-provision.sql",
                        )
                postgres.start()
                runCatching { migrate(postgres) }.onFailure { postgres.stop() }.getOrThrow()
                postgres
            }
        }

        val jdbcUrl: String get() = started.getOrThrow().jdbcUrl

        fun superuser(): Connection =
            started.getOrThrow().let { DriverManager.getConnection(it.jdbcUrl, it.username, it.password) }

        /** The display name of a parameterised run: the image tag. */
        override fun toString(): String = image.substringBefore('@')
    }

    // the members below serve the postgres:18 container
    private val container: PostgreSQLContainer<*> get() = Version.PG18.started.getOrThrow()

    val jdbcUrl: String get() = container.jdbcUrl

    /** The installation key the runners sign with and the schema's `bound_tenant()` verifies. */
    val bindingKey: ByteArray = BINDING_SECRET.toByteArray(Charsets.UTF_8)

    fun superuser(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    /** A superuser connection to another database of the container (a scratch database). */
    fun superuserOf(url: String): Connection = DriverManager.getConnection(url, container.username, container.password)

    fun connect(
        user: String,
        password: String,
    ): Connection = DriverManager.getConnection(container.jdbcUrl, user, password)

    /**
     * A scratch database of the same container, holding what the installer and the migrations leave
     * (`db/provision.sql`, then this module's Flyway migrations) and, first, the objects of other services that
     * [foreignSetup] creates as superuser: the stand-in for an operational store (INV-AC-1711, INV-AC-7871,
     * INV-AC-7872). [probeMigration] is
     * a scratch migration applied after the real ones and never committed. The database is dropped after [block].
     */
    fun <T> withScratchDatabase(
        foreignSetup: String,
        probeMigration: String? = null,
        block: (url: String) -> T,
    ): T {
        val name = "scratch_" + UUID.randomUUID().toString().replace("-", "")
        superuser().use { it.createStatement().use { statement -> statement.execute("create database $name") } }
        val url = container.jdbcUrl.substringBeforeLast('/') + "/$name"
        val directory = Files.createTempDirectory("evidence-probe")
        try {
            DriverManager.getConnection(url, container.username, container.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(checkNotNull(javaClass.getResource("/db/provision.sql")).readText())
                    statement.execute(foreignSetup)
                }
            }
            val locations = mutableListOf("classpath:db/migration")
            if (probeMigration != null) {
                Files.writeString(directory.resolve("V1000__probe.sql"), probeMigration)
                locations += "filesystem:$directory"
            }
            Flyway
                .configure()
                .dataSource(url, MIGRATE, MIGRATE_PASSWORD)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations(*locations.toTypedArray())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate()
            return block(url)
        } finally {
            directory.toFile().deleteRecursively()
            superuser().use {
                it.createStatement().use { statement ->
                    statement.execute("drop database if exists $name with (force)")
                }
            }
        }
    }

    private fun migrate(postgres: PostgreSQLContainer<*>) {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, MIGRATE, MIGRATE_PASSWORD)
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
