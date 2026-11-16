package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.PgTestImages
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.DESPATCH_FACT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY_STATUS
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/**
 * The replica of SubmissionDespatched facts (INV-AC-16774): one fact per (tenant, entity ref), the first fact stays,
 * and forced row-level security hides and refuses another tenant's rows.
 */
class JooqDespatchFactStoreTest {
    private val at = Instant.parse("2026-11-01T01:00:00Z")

    @Test
    fun `INV-AC-16774 the first fact of an entity is inserted and a second one for the same pair changes nothing`() {
        val tenant = DespatchTestDb.tenant()
        val first = UUID.randomUUID()
        DespatchTestDb.bound(tenant) { dsl ->
            val store = JooqDespatchFactStore(dsl)
            store.keepEntityFact(tenant, "E-1", at, first) shouldBe true
            store.keepEntityFact(tenant, "E-1", at.plusSeconds(60), UUID.randomUUID()) shouldBe false
            store.hasFactFor(tenant, "E-1") shouldBe true
        }
        DespatchTestDb.superuser { dsl ->
            val rows = dsl.selectFrom(DESPATCH_FACT).where(DESPATCH_FACT.TENANT_ID.eq(tenant.value)).fetch()
            rows.size shouldBe 1
            rows.single().docUuid shouldBe first
            rows.single().firstDespatchedAt!!.toInstant() shouldBe at
        }
    }

    @Test
    fun `INV-AC-16774 a fact matches its entity ref by exact equality and not by prefix or case`() {
        val tenant = DespatchTestDb.tenant()
        DespatchTestDb.bound(tenant) { dsl ->
            val store = JooqDespatchFactStore(dsl)
            store.keepEntityFact(tenant, "ENT-3F2A9C", at, null)
            store.hasFactFor(tenant, "ENT-3F2A9C") shouldBe true
            store.hasFactFor(tenant, "ENT-3F2A9") shouldBe false
            store.hasFactFor(tenant, "E") shouldBe false
            store.hasFactFor(tenant, "ENT-3F2A9C0") shouldBe false
            store.hasFactFor(tenant, "ent-3f2a9c") shouldBe false
        }
    }

    @Test
    fun `INV-AC-16774 a fact of another tenant for the same entity ref is not seen and a foreign insert is refused`() {
        val owner = DespatchTestDb.tenant()
        val other = DespatchTestDb.tenant()
        DespatchTestDb.bound(owner) { JooqDespatchFactStore(it).keepEntityFact(owner, "E-1", at, null) }
        DespatchTestDb.bound(other) { dsl ->
            JooqDespatchFactStore(dsl).hasFactFor(other, "E-1") shouldBe false
            // the superuser-free session sees no row of the owner even when asked for it by tenant id
            JooqDespatchFactStore(dsl).hasFactFor(owner, "E-1") shouldBe false
        }
        shouldThrow<Exception> {
            DespatchTestDb.bound(other) { JooqDespatchFactStore(it).keepEntityFact(owner, "E-2", at, null) }
        }
    }

    @Test
    fun `INV-AC-16774 the columns of despatch_fact are exactly tenant_id entity_ref first_despatched_at doc_uuid`() {
        val columns =
            DespatchTestDb.superuser { dsl ->
                dsl
                    .select(DSL.field(DSL.name("column_name"), String::class.java))
                    .from(DSL.table(DSL.name("information_schema", "columns")))
                    .where(DSL.field(DSL.name("table_schema")).eq("tenancy"))
                    .and(DSL.field(DSL.name("table_name")).eq("despatch_fact"))
                    .fetch(0, String::class.java)
                    .toSet()
            }
        columns shouldBe setOf("tenant_id", "entity_ref", "first_despatched_at", "doc_uuid")
    }

    @Test
    fun `INV-AC-16774 the table refuses a second row for the same pair and an empty entity ref`() {
        val tenant = DespatchTestDb.tenant()
        DespatchTestDb.superuser { dsl -> raw(dsl, tenant, "E-1") }
        shouldThrow<Exception> { DespatchTestDb.superuser { dsl -> raw(dsl, tenant, "E-1") } }
        shouldThrow<Exception> { DespatchTestDb.superuser { dsl -> raw(dsl, tenant, "") } }
    }

    private fun raw(
        dsl: org.jooq.DSLContext,
        tenant: com.dynokore.platform.tenancykernel.TenantId,
        ref: String,
    ) = dsl
        .insertInto(DESPATCH_FACT)
        .set(DESPATCH_FACT.TENANT_ID, tenant.value)
        .set(DESPATCH_FACT.ENTITY_REF, ref)
        .set(DESPATCH_FACT.FIRST_DESPATCHED_AT, OffsetDateTime.now(ZoneOffset.UTC))
        .execute()
}

/** One PostgreSQL with the service schema migrated as the installation does, shared by the despatch replica tests. */
internal object DespatchTestDb {
    private val random = SecureRandom()
    private val runtimePassword = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
    private val key = HexFormat.of().formatHex(ByteArray(32).also(random::nextBytes))
    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer(PgTestImages.postgres(PgTestImages.PG17))
            .withStartupTimeout(Duration.ofMinutes(3))
            .withStartupAttempts(3)
            .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
            .withLabel("inv.agent", "c-inv-t-16777")

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
                s.execute("ALTER ROLE tenancy_runtime LOGIN PASSWORD '$runtimePassword'")
                s.execute("GRANT CONNECT ON DATABASE ${container.databaseName} TO tenancy_runtime")
                s.execute("GRANT USAGE ON SCHEMA tenancy TO tenancy_runtime")
                s.execute(
                    "INSERT INTO tenancy.binding_key (slot, secret) VALUES ('current', convert_to('$key', 'UTF8'))",
                )
            }
        }
    }

    fun <T> superuser(block: (DSLContext) -> T): T =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use {
            block(DSL.using(it, SQLDialect.POSTGRES))
        }

    /** A tenant row (with one group) written as the superuser. */
    fun tenant(): TenantId {
        val tenant = TenantId(UUID.randomUUID())
        val tag = tenant.value.toString().take(8)
        superuser { dsl ->
            dsl
                .insertInto(TENANT)
                .set(TENANT.TENANT_ID, tenant.value)
                .set(TENANT.REF, "T-$tag")
                .set(TENANT.LEGAL_NAME, "Legal")
                .set(TENANT.PLAN_REF, "plan")
                .set(TENANT.CONTRACT_REFERENCE, "c-$tag")
                .set(TENANT.STRUCTURE_VERSION, nextVersion(dsl))
                .set(TENANT.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
        }
        return tenant
    }

    private fun nextVersion(dsl: DSLContext): Long =
        dsl.fetchValue("select nextval('tenancy.structure_version_seq')") as Long

    /** The ref of a new entity of [tenant] with an OBLIGATION_START row for each of [obligationStarts] (from, date). */
    fun entity(
        tenant: TenantId,
        vararg obligationStarts: Pair<LocalDate, LocalDate>,
    ): String {
        val entity = UUID.randomUUID()
        val tag = entity.toString().take(8)
        superuser { dsl ->
            fun version() = nextVersion(dsl)
            val group = UUID.randomUUID()
            dsl
                .insertInto(TENANT_GROUP)
                .set(TENANT_GROUP.ID, group)
                .set(TENANT_GROUP.TENANT_ID, tenant.value)
                .set(TENANT_GROUP.REF, "g-$tag")
                .set(TENANT_GROUP.NAME, "Group")
                .set(TENANT_GROUP.KIND, "DIVISIONAL")
                .set(TENANT_GROUP.STRUCTURE_VERSION, version())
                .set(TENANT_GROUP.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
            dsl
                .insertInto(ENTITY)
                .set(ENTITY.ID, entity)
                .set(ENTITY.TENANT_ID, tenant.value)
                .set(ENTITY.REF, "e-$tag")
                .set(ENTITY.GROUP_ID, group)
                .set(ENTITY.LEGAL_NAME, "Entity")
                .set(ENTITY.CONSTITUTION, "COMPANY")
                .set(ENTITY.CODE, "E$tag".uppercase())
                .set(ENTITY.LIFECYCLE_STATE, "DRAFT")
                .set(ENTITY.STRUCTURE_VERSION, version())
                .set(ENTITY.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
            obligationStarts.forEach { (from, date) -> obligationStart(dsl, tenant, entity, from, date) }
        }
        return "e-$tag"
    }

    private fun obligationStart(
        dsl: DSLContext,
        tenant: TenantId,
        entity: UUID,
        from: LocalDate,
        date: LocalDate,
    ) {
        dsl
            .insertInto(ENTITY_STATUS)
            .set(ENTITY_STATUS.ID, UUID.randomUUID())
            .set(ENTITY_STATUS.TENANT_ID, tenant.value)
            .set(ENTITY_STATUS.ENTITY_ID, entity)
            .set(ENTITY_STATUS.STATUS_KIND, "OBLIGATION_START")
            .set(ENTITY_STATUS.EFFECTIVE_FROM, from)
            .set(ENTITY_STATUS.OBLIGATION_START_DATE, date)
            .set(ENTITY_STATUS.OBLIGATION_START_BASIS, "MANDATORY_DATE")
            .set(ENTITY_STATUS.RECORDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    /** A runtime-role transaction bound to [tenant] as TransactionRunnerImpl does, then committed. */
    fun <T> bound(
        tenant: TenantId,
        block: (DSLContext) -> T,
    ): T =
        DriverManager.getConnection(container.jdbcUrl, "tenancy_runtime", runtimePassword).use { c ->
            c.autoCommit = false
            val expiry = BindingSignature.expiryFor(Instant.now(), Duration.ofSeconds(30))
            val actor = "svc:tenancy-svc"
            val signature = BindingSignature.sign(key.toByteArray(Charsets.UTF_8), tenant, actor, expiry)
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

    /** A [DSLContext] over a connection that is already closed: every read through it throws. */
    fun brokenDsl(): DSLContext {
        val connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        connection.close()
        return DSL.using(connection, SQLDialect.POSTGRES)
    }
}
