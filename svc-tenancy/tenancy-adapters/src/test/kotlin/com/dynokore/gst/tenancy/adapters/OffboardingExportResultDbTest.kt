package com.dynokore.gst.tenancy.adapters

import com.dynokore.gst.tenancy.adapters.events.OffboardingExportFinishedHandler
import com.dynokore.gst.tenancy.adapters.events.OffboardingExportRequestedOutbox
import com.dynokore.gst.tenancy.adapters.events.SchemaFixture
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.adapters.persistence.JooqOffboardingStore
import com.dynokore.gst.tenancy.application.offboarding.CompleteOffboardingExport
import com.dynokore.gst.tenancy.application.offboarding.ExportOrder
import com.dynokore.gst.tenancy.application.offboarding.OffboardingRecord
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.OffboardingState
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-22960 over PostgreSQL built from the migrations, with forced row-level security: the export request is
 * appended to the outbox in the transaction that stores the record (a rollback leaves no row), the result event moves
 * the stored record once, and the database refuses any change of a COMPLETED record.
 */
class OffboardingExportResultDbTest {
    private val mapper = JsonMapper.builder().build()
    private val platform = TenantId.PLATFORM
    private val type = OffboardingExportRequestedOutbox.TYPE

    private class Seed(
        val tenant: TenantId,
        val ref: String,
    )

    private fun context(tenant: TenantId) =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor(): String = "svc:tenancy-svc"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private fun seed(): Seed {
        val id = UUID.randomUUID()
        val ref = "T-" + id.toString().take(8)
        Db.bound(platform) {
            it.fetchValue(
                "select tenancy.create_tenant(?::uuid, ?, 'Legal', 'plan', ?, ?::uuid, 'SG', ?)::text",
                id,
                ref,
                "c-$ref",
                null,
                null,
            )
        }
        return Seed(TenantId(id), ref)
    }

    private fun record(
        seed: Seed,
        offboardingRef: String,
        entityRef: String,
    ) = OffboardingRecord(
        offboardingRef = offboardingRef,
        tenantRef = seed.ref,
        scope = OffboardingScope.ENTITY,
        entityRef = entityRef,
        state = OffboardingState.REQUESTED,
        retentionCustodyHolder = RetentionCustodyHolder("Holder", "holder@example.invalid"),
        export = ExportManifest("EXP-$offboardingRef", ExportState.REQUESTED, emptyList()),
        idempotencyKey = "key-$offboardingRef",
    )

    private fun store(
        dsl: DSLContext,
        tenant: TenantId,
    ) = JooqOffboardingStore(dsl, context(tenant))

    private fun insert(
        seed: Seed,
        offboardingRef: String,
        entityRef: String = "ENT-1",
    ) = Db.bound(seed.tenant) { store(it, seed.tenant).insert(record(seed, offboardingRef, entityRef)) }

    private fun order(
        seed: Seed,
        offboardingRef: String,
        entityRef: String = "ENT-1",
    ) = ExportOrder(offboardingRef, seed.ref, OffboardingScope.ENTITY, entityRef)

    private fun outbox(
        dsl: DSLContext,
        tenant: TenantId,
    ) = OffboardingExportRequestedOutbox(
        JdbcOutboxWriter(dsl, "tenancy", "tenancy-svc", "instance-1", TenancyEventCatalogue.load(), Clock.systemUTC()),
        mapper,
        context(tenant),
    )

    private fun outboxRows(tenant: TenantId): List<String> =
        Db.superuser {
            it
                .fetch(
                    "select data::text from tenancy.outbox where tenant_id = ?::uuid and type = ?",
                    tenant.toString(),
                    type,
                ).map { r -> r.get(0, String::class.java) }
        }

    private val unusedTenantStore: TenantStore =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TenantStore::class.java)) { _, m, _ ->
            error("${m.name} is not expected for an ENTITY record")
        } as TenantStore

    private fun envelope(
        seed: Seed,
        data: String,
    ) = Envelope(
        id = EventId("ev-${UUID.randomUUID()}"),
        source = "/gst/evidence-svc",
        type = TenancyEventCatalogue.OFFBOARDING_EXPORT_FINISHED_TYPE,
        subject = "tenant",
        time = Instant.parse("2026-11-20T10:00:00Z"),
        tenant = seed.tenant,
        entity = EventEntityRef.TENANT_WIDE,
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "evidence-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun deliver(
        seed: Seed,
        data: String,
    ): HandleOutcome =
        Db.bound(seed.tenant) { dsl ->
            val store = store(dsl, seed.tenant)
            val tx =
                object : TransactionRunner {
                    override fun <T> inTransaction(block: () -> T): T = block()
                }
            OffboardingExportFinishedHandler(CompleteOffboardingExport(tx, store, unusedTenantStore), mapper, store)
                .handle(envelope(seed, data))
        }

    private fun row(
        seed: Seed,
        offboardingRef: String,
    ) = Db.superuser {
        it
            .fetchOne(
                "select state, export_state, cardinality(export_contents), export_failure_reason " +
                    "from tenancy.offboarding_record where tenant_id = ?::uuid and offboarding_ref = ?",
                seed.tenant.toString(),
                offboardingRef,
            )!!
            .intoArray()
            .toList()
    }

    private fun finished(
        seed: Seed,
        ref: String,
        rest: String,
    ) =
        """{"tenantRef":"${seed.ref}","offboardingRef":"$ref","exportRef":"EXP-$ref","finishedAt":"2026-11-20T10:00:00Z",$rest}"""

    private val sixContents =
        """"state":"READY","contents":["TRANSMITTED_XML","ACKNOWLEDGEMENTS","DECISION_TRACES",""" +
            """"RECONCILIATION_WORK_PAPERS","SOURCE_DOCUMENT_REFERENCES","AUDIT_LOG"]"""

    @Test
    fun `INV-AC-22960 a committed request leaves one outbox row that validates and a rolled back one leaves none`() {
        val seed = seed()
        insert(seed, "OFF-C")
        insert(seed, "OFF-R", "ENT-2")
        val manifest =
            Db.bound(seed.tenant) { outbox(it, seed.tenant).produce(order(seed, "OFF-C")) }
        manifest shouldBe ExportManifest("EXP-OFF-C", ExportState.REQUESTED, emptyList())
        Db.bound(seed.tenant, commit = false) { outbox(it, seed.tenant).produce(order(seed, "OFF-R", "ENT-2")) }
        val rows = outboxRows(seed.tenant)
        rows.size shouldBe 1
        SchemaFixture
            .errors(
                SchemaFixture.schema("offboarding-export-requested-payload.schema.json"),
                rows.single(),
            ).shouldBeEmpty()
        mapper.readTree(rows.single()).get("offboardingRef").asString() shouldBe "OFF-C"
    }

    @Test
    fun `INV-AC-22960 the stored entity of a tenant request is tenant-wide and of an entity request its entity`() {
        val seed = seed()
        Db.bound(seed.tenant) {
            outbox(it, seed.tenant).produce(ExportOrder("OFF-T", seed.ref, OffboardingScope.TENANT, null))
            outbox(it, seed.tenant).produce(order(seed, "OFF-E", "ENT-1"))
        }
        val entities =
            Db.superuser {
                it
                    .fetch(
                        "select data->>'offboardingRef', entity_ref from tenancy.outbox " +
                            "where tenant_id = ?::uuid and type = ?",
                        seed.tenant.toString(),
                        type,
                    ).associate { r -> r.get(0, String::class.java) to r.get(1, String::class.java) }
            }
        entities["OFF-T"] shouldBe EventEntityRef.TENANT_WIDE.toString()
        entities["OFF-E"] shouldBe "ENT-1"
        entities.values.none { v -> v == seed.ref } shouldBe true
    }

    @Test
    fun `INV-AC-22960 a failed append is refused as unavailable tenant-export and stores nothing`() {
        val seed = seed()
        val thrown =
            shouldThrow<UnavailableInput> {
                // the schema holds no unknown type: a catalogue without this producer refuses the append
                OffboardingExportRequestedOutbox(
                    JdbcOutboxWriterRefusing,
                    mapper,
                    context(seed.tenant),
                ).produce(order(seed, "OFF-X"))
            }
        thrown.input shouldBe "tenant-export"
        outboxRows(seed.tenant).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-22960 a READY envelope with six contents completes the record and the repeat changes nothing`() {
        val seed = seed()
        insert(seed, "OFF-1")
        deliver(seed, finished(seed, "OFF-1", sixContents)) shouldBe HandleOutcome.APPLIED
        row(seed, "OFF-1") shouldBe listOf("COMPLETED", "READY", 6, null)
        deliver(seed, finished(seed, "OFF-1", sixContents)) shouldBe HandleOutcome.IGNORED
        row(seed, "OFF-1") shouldBe listOf("COMPLETED", "READY", 6, null)
    }

    @Test
    fun `INV-AC-22960 a FAILED envelope stores the reason`() {
        val seed = seed()
        insert(seed, "OFF-2")
        deliver(
            seed,
            finished(seed, "OFF-2", """"state":"FAILED","failureReason":"no source for AUDIT_LOG""""),
        ) shouldBe
            HandleOutcome.APPLIED
        row(seed, "OFF-2") shouldBe listOf("FAILED", "FAILED", 0, "no source for AUDIT_LOG")
    }

    @Test
    fun `INV-AC-22960 an unknown record and a payload without state change no row and are refused`() {
        val seed = seed()
        insert(seed, "OFF-3")
        shouldThrow<ContractViolation> { deliver(seed, finished(seed, "OFF-NONE", sixContents)) }
        shouldThrow<ContractViolation> {
            deliver(seed, finished(seed, "OFF-3", """"contents":["TRANSMITTED_XML"]"""))
        }
        row(seed, "OFF-3") shouldBe listOf("REQUESTED", "REQUESTED", 0, null)
    }

    @Test
    fun `INV-AC-22960 a tenantRef of another tenant is refused and changes no row`() {
        val seed = seed()
        val other = seed()
        insert(seed, "OFF-4")
        val data = finished(other, "OFF-4", sixContents)
        shouldThrow<ContractViolation> { deliver(seed, data) }
        row(seed, "OFF-4") shouldBe listOf("REQUESTED", "REQUESTED", 0, null)
    }

    @Test
    fun `INV-AC-22960 the database refuses a statement that changes a COMPLETED record`() {
        val seed = seed()
        insert(seed, "OFF-5")
        deliver(seed, finished(seed, "OFF-5", sixContents))
        shouldThrow<DataAccessException> {
            Db.bound(seed.tenant) {
                it.execute(
                    "update tenancy.offboarding_record set state = 'FAILED', export_failure_reason = 'x' " +
                        "where offboarding_ref = 'OFF-5'",
                )
            }
        }
        row(seed, "OFF-5") shouldBe listOf("COMPLETED", "READY", 6, null)
    }

    @Test
    fun `INV-AC-22960 the runtime login holds no DELETE and no UPDATE of other columns`() {
        val seed = seed()
        insert(seed, "OFF-6")
        shouldThrow<DataAccessException> {
            Db.bound(
                seed.tenant,
            ) { it.execute("delete from tenancy.offboarding_record where offboarding_ref = 'OFF-6'") }
        }
        shouldThrow<DataAccessException> {
            Db.bound(seed.tenant) { it.execute("update tenancy.offboarding_record set scope = 'TENANT'") }
        }
    }

    /** An outbox writer that fails every append, as a refused or unreachable outbox does. */
    private object JdbcOutboxWriterRefusing : com.dynokore.platform.outboxandqueue.OutboxWriter {
        override fun append(
            events: List<com.dynokore.platform.outboxandqueue.OutboundEvent>,
            context: com.dynokore.platform.outboxandqueue.EventContext,
        ): List<Envelope> = error("the outbox refused the append")
    }

    private object Db {
        // generated per run: no secret literal in the source
        private val RUNTIME_PASSWORD = randomHex()
        private val KEY = randomHex()
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer(PgTestImages.postgres(PgTestImages.PG17))
                .withStartupTimeout(Duration.ofMinutes(3))
                .withStartupAttempts(3)
                .withCommand(
                    "postgres",
                    "-c",
                    "fsync=off",
                    "-c",
                    "synchronous_commit=off",
                    "-c",
                    "full_page_writes=off",
                ).withLabel("inv.agent", "c-inv-t-22966")

        init {
            container.start()
            Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
            val roles = Files.readString(Path.of("src/integrationTest/resources/roles.sql"))
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

        /** A runtime-role transaction bound to [tenant] as TransactionRunnerImpl does, committed or rolled back. */
        fun <T> bound(
            tenant: TenantId,
            commit: Boolean = true,
            block: (DSLContext) -> T,
        ): T =
            DriverManager.getConnection(container.jdbcUrl, "tenancy_runtime", RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                val actor = "svc:tenancy-svc"
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
                try {
                    val result = block(DSL.using(c, SQLDialect.POSTGRES))
                    if (commit) c.commit() else c.rollback()
                    result
                } catch (e: Exception) {
                    c.rollback()
                    throw e
                }
            }
    }
}

private fun randomHex(): String =
    ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(Locale.ROOT, it) }
