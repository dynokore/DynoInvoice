package com.dynokore.gst.tenancy.adapters.offboarding

import com.dynokore.gst.tenancy.adapters.PgTestImages
import com.dynokore.gst.tenancy.adapters.events.EntityUnsubmittedCount
import com.dynokore.gst.tenancy.adapters.events.UnsubmittedCountMeasurement
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-23029: the replica of `UnsubmittedDocumentCountMeasured` keeps, per tenant, the measurement with the greatest
 * `measuredAt` and stamps `received_at` only when it applies one, on a PostgreSQL with forced row-level security.
 */
class JooqUnsubmittedCountReplicaTest {
    private val actor = "svc:tenancy-svc"
    private val t10 = Instant.parse("2026-11-20T10:00:00Z")
    private val t1005 = Instant.parse("2026-11-20T10:05:00Z")
    private val t09 = Instant.parse("2026-11-20T09:00:00Z")
    private val recv1 = Instant.parse("2026-11-20T10:00:01Z")
    private val recv2 = Instant.parse("2026-11-20T10:05:01Z")
    private val recv3 = Instant.parse("2026-11-20T10:09:00Z")
    private val first =
        UnsubmittedCountMeasurement(t10, 5, listOf(EntityUnsubmittedCount("E1", 3), EntityUnsubmittedCount("E2", 2)))
    private val second = UnsubmittedCountMeasurement(t1005, 0, emptyList())
    private val older = UnsubmittedCountMeasurement(t09, 1, listOf(EntityUnsubmittedCount("E9", 1)))

    private fun tenant(): TenantId = TenantId(UUID.randomUUID())

    private fun keep(
        tenant: TenantId,
        measurement: UnsubmittedCountMeasurement,
        receivedAt: Instant,
    ): Boolean = Db.bound(tenant, actor) { JooqUnsubmittedCountReplica(it).keep(tenant, measurement, receivedAt) }

    private fun read(
        bound: TenantId,
        asked: TenantId,
    ): StoredUnsubmittedCount? = Db.bound(bound, actor) { JooqUnsubmittedCountReplica(it).read(asked) }

    @Test
    fun `INV-AC-23029 a first event is stored with the receipt time`() {
        val a = tenant()
        keep(a, first, recv1) shouldBe true
        read(a, a) shouldBe StoredUnsubmittedCount(first, recv1)
    }

    @Test
    fun `INV-AC-23029 a later measuredAt replaces the row and sets received_at`() {
        val a = tenant()
        keep(a, first, recv1)
        keep(a, second, recv2) shouldBe true
        read(a, a) shouldBe StoredUnsubmittedCount(second, recv2)
    }

    @Test
    fun `INV-AC-23029 an equal measuredAt changes nothing, received_at included`() {
        val a = tenant()
        keep(a, first, recv1)
        keep(a, second, recv2)
        keep(a, second.copy(total = 3, entities = listOf(EntityUnsubmittedCount("E1", 3))), recv3) shouldBe false
        read(a, a) shouldBe StoredUnsubmittedCount(second, recv2)
    }

    @Test
    fun `INV-AC-23029 an older measuredAt changes nothing, received_at included`() {
        val a = tenant()
        keep(a, first, recv1)
        keep(a, second, recv2)
        keep(a, older, recv3) shouldBe false
        read(a, a) shouldBe StoredUnsubmittedCount(second, recv2)
    }

    @Test
    fun `INV-AC-23029 tenant B bound and asked for tenant A reads null`() {
        val a = tenant()
        val b = tenant()
        keep(a, first, recv1)
        read(b, a) shouldBe null
        read(b, b) shouldBe null
        read(a, a) shouldBe StoredUnsubmittedCount(first, recv1)
    }

    @Test
    fun `INV-AC-23029 the runtime login cannot delete a row`() {
        val a = tenant()
        keep(a, first, recv1)
        shouldThrow<DataAccessException> {
            Db.bound(a, actor) { it.execute("delete from tenancy.unsubmitted_count_replica") }
        }
        read(a, a) shouldBe StoredUnsubmittedCount(first, recv1)
    }

    /** One PostgreSQL with the service schema migrated as the installation does, and the two kinds of session. */
    private object Db {
        // generated per run: no secret literal in the source
        private val RUNTIME_PASSWORD = randomHex()
        private val KEY = randomHex()
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer(PgTestImages.postgres(PgTestImages.PG17))
                .withStartupTimeout(Duration.ofMinutes(PG_START_TIMEOUT_MINUTES))
                .withStartupAttempts(PG_START_ATTEMPTS)
                .withCommand(*pgFastCommand)
                .withLabel("inv.agent", "c-inv-t-22885")

        init {
            container.start()
            Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
            val roles =
                java.nio.file.Files
                    .readString(Path.of("src/integrationTest/resources/roles.sql"))
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
                c.autoCommit = true
                c.createStatement().use { s ->
                    s.execute(roles)
                    s.execute(
                        "CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;\n" +
                            "CREATE SCHEMA IF NOT EXISTS tenancy AUTHORIZATION tenancy_sys;\n" +
                            "SET ROLE tenancy_sys;\n" +
                            BindingKeyMigration.ddl("tenancy", "tenancy_sys", "tenancy_runtime") +
                            "\nRESET ROLE;",
                    )
                }
            }
            Flyway
                .configure()
                .dataSource(container.jdbcUrl, container.username, container.password)
                .schemas("tenancy")
                .defaultSchema("tenancy")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate()
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
                c.createStatement().use { s ->
                    s.execute("ALTER ROLE tenancy_runtime LOGIN PASSWORD '$RUNTIME_PASSWORD'")
                    s.execute("GRANT CONNECT ON DATABASE ${container.databaseName} TO tenancy_runtime")
                    s.execute("GRANT USAGE ON SCHEMA tenancy TO tenancy_runtime")
                    s.execute(
                        "INSERT INTO tenancy.binding_key (slot, secret) VALUES ('current', convert_to('$KEY', 'UTF8'))",
                    )
                }
            }
        }

        fun <T> superuser(block: (DSLContext) -> T): T =
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use {
                block(DSL.using(it, SQLDialect.POSTGRES))
            }

        /** A runtime-role transaction bound to [tenant] and [actor] as TransactionRunnerImpl does, then committed. */
        fun <T> bound(
            tenant: TenantId,
            actor: String,
            block: (DSLContext) -> T,
        ): T =
            DriverManager.getConnection(container.jdbcUrl, "tenancy_runtime", RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                val expiry = BindingSignature.expiryFor(Instant.now(), Duration.ofSeconds(30))
                val signature = BindingSignature.sign(KEY.toByteArray(Charsets.UTF_8), tenant, actor, expiry)
                c
                    .prepareStatement(
                        "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true), " +
                            "set_config('app.binding_sig', ?, true)",
                    ).use { s ->
                        s.setString(1, tenant.toString())
                        s.setString(2, actor)
                        s.setString(3, signature)
                        s.execute()
                    }
                val result = block(DSL.using(c, SQLDialect.POSTGRES))
                c.commit()
                result
            }
    }
}

private fun randomHex(): String =
    ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

// CI-FAST: a loaded CI host can need more than the 60 s Testcontainers default for initdb and its restart; a failed
// start is retried with a fresh container, and the throwaway test database skips fsync.
private const val PG_START_TIMEOUT_MINUTES = 3L
private const val PG_START_ATTEMPTS = 3
private val pgFastCommand =
    arrayOf("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
