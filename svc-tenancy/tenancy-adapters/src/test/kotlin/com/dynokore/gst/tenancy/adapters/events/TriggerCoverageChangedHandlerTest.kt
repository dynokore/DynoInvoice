package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.PgTestImages
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TRIGGER_COVERAGE_REPLICA
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
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
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-20941: `TriggerCoverageChanged` is stored once per (tenant, entity, class) in
 * `tenancy.trigger_coverage_replica`, in order, on the tenant-bound [DSLContext] of a PostgreSQL with forced
 * row-level security; a broken payload is a [ContractViolation].
 */
class TriggerCoverageChangedHandlerTest {
    private val actor = "svc:tenancy-svc"
    private val class1 = "OUTBOUND_DOCUMENT_ISSUED"

    private fun tenant(): TenantId = TenantId(UUID.randomUUID())

    private fun payload(
        status: String = "MAPPED",
        changedAt: String = "2026-11-12T10:00:00Z",
        triggerClass: String = class1,
        entityRef: String? = "E1",
        reviewDate: String? = null,
    ): String {
        val fields = mutableListOf<String>()
        if (entityRef != null) fields += """"entityRef":"$entityRef""""
        fields += """"triggerClass":"$triggerClass""""
        fields += """"status":"$status""""
        if (status == "MAPPED") fields += """"adapterRef":"erp-ar-adapter""""
        if (reviewDate != null) fields += """"reviewDate":"$reviewDate""""
        fields += """"changedAt":"$changedAt""""
        fields += """"actorUserId":"user-42""""
        return "{" + fields.joinToString(",") + "}"
    }

    private fun handle(
        tenant: TenantId,
        data: String,
    ): HandleOutcome = Db.bound(tenant, actor) { dsl -> TriggerCoverageChangedHandler(dsl).handle(event(tenant, data)) }

    private fun rows(tenant: TenantId) =
        Db.bound(tenant, actor) { dsl ->
            dsl.selectFrom(TRIGGER_COVERAGE_REPLICA).orderBy(TRIGGER_COVERAGE_REPLICA.TRIGGER_CLASS).fetch()
        }

    private fun event(
        tenant: TenantId,
        data: String,
    ) = Envelope(
        id = EventId("ev-${UUID.randomUUID()}"),
        source = "/gst/ingestion-svc",
        type = TenancyEventCatalogue.TRIGGER_COVERAGE_CHANGED_TYPE,
        subject = "E1",
        time = Instant.parse("2026-11-12T10:00:00Z"),
        tenant = tenant,
        entity = EventEntityRef("E1"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "ingestion-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    @Test
    fun `INV-AC-20941 a first event stores one replica row holding the status and the carried members`() {
        val tenant = tenant()
        handle(tenant, payload()) shouldBe HandleOutcome.APPLIED
        val row = rows(tenant).single()
        row.entityRef shouldBe "E1"
        row.triggerClass shouldBe class1
        row.status shouldBe "MAPPED"
        row.adapterRef shouldBe "erp-ar-adapter"
        row.actorUserId shouldBe "user-42"
        row.changedAt!!.toInstant() shouldBe Instant.parse("2026-11-12T10:00:00Z")
    }

    @Test
    fun `INV-AC-20941 the same event again changes nothing`() {
        val tenant = tenant()
        handle(tenant, payload()) shouldBe HandleOutcome.APPLIED
        handle(tenant, payload()) shouldBe HandleOutcome.IGNORED
        val row = rows(tenant).single()
        row.status shouldBe "MAPPED"
    }

    @Test
    fun `INV-AC-20941 a later event for the same key replaces the row`() {
        val tenant = tenant()
        handle(tenant, payload())
        val later = payload("WAIVED", "2026-11-12T11:00:00Z", reviewDate = "2027-03-01")
        handle(tenant, later) shouldBe HandleOutcome.APPLIED
        val row = rows(tenant).single()
        row.status shouldBe "WAIVED"
        row.reviewDate shouldBe LocalDate.parse("2027-03-01")
        row.adapterRef shouldBe null
        row.changedAt!!.toInstant() shouldBe Instant.parse("2026-11-12T11:00:00Z")
    }

    @Test
    fun `INV-AC-20941 an earlier event for the same key after that is ignored as stale`() {
        val tenant = tenant()
        handle(tenant, payload("WAIVED", "2026-11-12T11:00:00Z", reviewDate = "2027-03-01"))
        handle(tenant, payload("MAPPED", "2026-11-12T09:00:00Z")) shouldBe HandleOutcome.IGNORED
        rows(tenant).single().status shouldBe "WAIVED"
    }

    @Test
    fun `INV-AC-20941 an event for another class is a second row`() {
        val tenant = tenant()
        handle(tenant, payload())
        handle(tenant, payload(triggerClass = "THIRD_PARTY_ISSUANCE")) shouldBe HandleOutcome.APPLIED
        rows(tenant).map { it.triggerClass } shouldBe listOf(class1, "THIRD_PARTY_ISSUANCE").sorted()
    }

    @Test
    fun `INV-AC-20941 a broken payload is a contract violation and writes no row`() {
        val tenant = tenant()
        listOf(
            payload(triggerClass = "SOMETHING_ELSE"),
            payload(status = "DONE"),
            payload(entityRef = null),
            payload(changedAt = "yesterday"),
            "not json",
        ).forEach { data -> shouldThrow<ContractViolation> { handle(tenant, data) } }
        rows(tenant).size shouldBe 0
    }

    @Test
    fun `INV-AC-20941 tenant B sees none of tenant A's rows and no column holds a role name`() {
        val a = tenant()
        val b = tenant()
        handle(a, payload())
        rows(b).size shouldBe 0
        rows(a).size shouldBe 1
        TRIGGER_COVERAGE_REPLICA.fields().map { it.name }.none { it.contains("role") } shouldBe true
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
                .withLabel("inv.agent", "c-inv-t-20942")

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
