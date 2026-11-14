package com.dynokore.gst.tenancy.adapters.coverage

import com.dynokore.gst.tenancy.adapters.PgTestImages
import com.dynokore.gst.tenancy.application.lifecycle.TriggerCoverage
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.domain.GoLiveGate
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
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
 * INV-AC-20943: [ReplicaTriggerCoverage] reads `tenancy.trigger_coverage_replica` on the tenant-bound [DSLContext] of a
 * PostgreSQL with forced row-level security and fails closed.
 */
class ReplicaTriggerCoverageTest {
    private val actor = "svc:tenancy-svc"
    private val classes = ReplicaTriggerCoverage.TRIGGER_CLASSES
    private val statuses = listOf("MAPPED", "WAIVED", "COVERED_BY_OTHER_SOLUTION")

    private fun tenant(): TenantId = TenantId(UUID.randomUUID())

    private fun insert(
        tenant: TenantId,
        entity: String,
        triggerClass: String,
        status: String = "MAPPED",
    ) = Db.superuser { dsl ->
        dsl
            .execute(
                "insert into tenancy.trigger_coverage_replica " +
                    "(tenant_id, entity_ref, trigger_class, status, changed_at) values (?, ?, ?, ?, now())",
                tenant.value,
                entity,
                triggerClass,
                status,
            )
    }

    private fun uncovered(
        tenant: TenantId,
        entity: String,
    ): List<String> = Db.bound(tenant, actor) { dsl -> ReplicaTriggerCoverage(dsl).uncovered(entity) }

    @Test
    fun `INV-AC-20943 the adapter is assignable to the TriggerCoverage port at compile time`() {
        val dsl = DSL.using(SQLDialect.POSTGRES)
        val port: TriggerCoverage = ReplicaTriggerCoverage(dsl)
        (port is ReplicaTriggerCoverage) shouldBe true
    }

    @Test
    fun `INV-AC-20943 the five class names equal the contract enum in declaration order`() {
        classes shouldBe
            listOf(
                "OUTBOUND_DOCUMENT_ISSUED",
                "INBOUND_DOCUMENT_RECORDED",
                "AGGREGABLE_BATCH_CLOSED",
                "JOURNAL_ONLY_SUPPLY",
                "THIRD_PARTY_ISSUANCE",
            )
    }

    @Test
    fun `INV-AC-20943 no replica row for the entity is refused as trigger coverage unknown`() {
        val a = tenant()
        shouldThrow<UnavailableInput> { uncovered(a, "E-none") }.input shouldBe GoLiveGate.COVERAGE_UNKNOWN
    }

    @Test
    fun `INV-AC-20943 four rows name the one class without a row`() {
        val a = tenant()
        classes.take(4).forEach { insert(a, "E-four", it) }
        uncovered(a, "E-four") shouldBe listOf(classes[4])
        val b = tenant()
        classes.drop(1).forEach { insert(b, "E-four", it) }
        uncovered(b, "E-four") shouldBe listOf(classes[0])
    }

    @Test
    fun `INV-AC-20943 five rows of MAPPED, WAIVED and COVERED_BY_OTHER_SOLUTION pass with no uncovered class`() {
        val a = tenant()
        classes.forEachIndexed { i, c -> insert(a, "E-five", c, statuses[i % statuses.size]) }
        uncovered(a, "E-five") shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20943 an unreadable replica table is refused as trigger coverage unknown never passed`() {
        val a = tenant()
        classes.forEach { insert(a, "E-down", it) }
        val connection = DriverManager.getConnection(Db.jdbcUrl(), "tenancy_runtime", Db.runtimePassword())
        val dsl = DSL.using(connection, SQLDialect.POSTGRES)
        connection.close()
        shouldThrow<UnavailableInput> { ReplicaTriggerCoverage(dsl).uncovered("E-down") }.input shouldBe
            GoLiveGate.COVERAGE_UNKNOWN
        // an unbound session (no tenant) reads no row either: still unknown, never an empty list
        DriverManager.getConnection(Db.jdbcUrl(), "tenancy_runtime", Db.runtimePassword()).use { c ->
            shouldThrow<UnavailableInput> {
                ReplicaTriggerCoverage(DSL.using(c, SQLDialect.POSTGRES)).uncovered("E-down")
            }.input shouldBe GoLiveGate.COVERAGE_UNKNOWN
        }
    }

    @Test
    fun `INV-AC-20943 rows of another tenant or another entity change no answer`() {
        val a = tenant()
        val b = tenant()
        classes.forEach { insert(b, "E1", it) }
        classes.take(4).forEach { insert(a, "E-other", it) }
        shouldThrow<UnavailableInput> { uncovered(a, "E1") }.input shouldBe GoLiveGate.COVERAGE_UNKNOWN
        classes.take(2).forEach { insert(a, "E1", it) }
        uncovered(a, "E1") shouldBe classes.drop(2)
        uncovered(b, "E1") shouldBe emptyList()
        shouldThrow<UnavailableInput> { uncovered(b, "E-other") }.input shouldBe GoLiveGate.COVERAGE_UNKNOWN
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
                .withLabel("inv.agent", "c-inv-t-20944")

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

        fun jdbcUrl(): String = container.jdbcUrl

        fun runtimePassword(): String = RUNTIME_PASSWORD

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
