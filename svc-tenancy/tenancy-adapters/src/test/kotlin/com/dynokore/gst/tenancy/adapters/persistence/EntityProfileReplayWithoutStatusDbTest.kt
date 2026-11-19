package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.PgTestImages
import com.dynokore.gst.tenancy.adapters.events.EntityProfileReplaySource
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY_STATUS
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.EXEMPT_PURCHASE_ELECTION
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.MIGRATION_CUTOVER
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-25373 over PostgreSQL with forced row-level security: the replay of EntityProfileChanged returns one state for
 * an entity that has only an exempt-purchase election or a migration cutover, dated by its latest profile entry.
 */
class EntityProfileReplayWithoutStatusDbTest {
    private val mapper = JsonMapper.builder().build()

    private class Seed(
        val t1: TenantId,
        val t2: TenantId,
        val tag: String,
    ) {
        fun ref(name: String) = "$name-$tag"
    }

    private fun d(text: String) = LocalDate.parse(text)

    private fun context(id: TenantId) =
        object : TenantContext {
            override fun current(): TenantId = id

            override fun currentActor(): String = "actor"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private fun version(dsl: DSLContext) = dsl.fetchValue("select nextval('tenancy.structure_version_seq')") as Long

    private fun insertTenant(
        dsl: DSLContext,
        tenant: TenantId,
        tag: String,
    ) {
        dsl
            .insertInto(TENANT)
            .set(TENANT.TENANT_ID, tenant.value)
            .set(TENANT.REF, "T-$tag")
            .set(TENANT.LEGAL_NAME, "Legal")
            .set(TENANT.PLAN_REF, "plan")
            .set(TENANT.CONTRACT_REFERENCE, "c-$tag")
            .set(TENANT.STRUCTURE_VERSION, version(dsl))
            .set(TENANT.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    private fun insertGroup(
        dsl: DSLContext,
        tenant: TenantId,
        id: UUID,
        ref: String,
    ) {
        dsl
            .insertInto(TENANT_GROUP)
            .set(TENANT_GROUP.ID, id)
            .set(TENANT_GROUP.TENANT_ID, tenant.value)
            .set(TENANT_GROUP.REF, ref)
            .set(TENANT_GROUP.NAME, "Group")
            .set(TENANT_GROUP.KIND, "DIVISIONAL")
            .set(TENANT_GROUP.STRUCTURE_VERSION, version(dsl))
            .set(TENANT_GROUP.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    /** An entity with ref [ref] and no profile entry; returns its row id. */
    private fun insertEntity(
        dsl: DSLContext,
        tenant: TenantId,
        group: UUID,
        ref: String,
    ): UUID {
        val id = UUID.randomUUID()
        dsl
            .insertInto(ENTITY)
            .set(ENTITY.ID, id)
            .set(ENTITY.TENANT_ID, tenant.value)
            .set(ENTITY.REF, ref)
            .set(ENTITY.GROUP_ID, group)
            .set(ENTITY.LEGAL_NAME, "Entity $ref")
            .set(ENTITY.CONSTITUTION, "PRIVATE_LIMITED")
            .set(ENTITY.CODE, codeOf(ref))
            .set(ENTITY.LIFECYCLE_STATE, "DRAFT")
            .set(ENTITY.STRUCTURE_VERSION, version(dsl))
            .set(ENTITY.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
        return id
    }

    private fun status(
        dsl: DSLContext,
        tenant: TenantId,
        entity: UUID,
        from: LocalDate,
    ) {
        dsl
            .insertInto(ENTITY_STATUS)
            .set(ENTITY_STATUS.ID, UUID.randomUUID())
            .set(ENTITY_STATUS.TENANT_ID, tenant.value)
            .set(ENTITY_STATUS.ENTITY_ID, entity)
            .set(ENTITY_STATUS.STATUS_KIND, "PARTIAL_EXEMPTION")
            .set(ENTITY_STATUS.EFFECTIVE_FROM, from)
            .set(ENTITY_STATUS.PARTIALLY_EXEMPT, true)
            .set(ENTITY_STATUS.BASIS, "basis")
            .set(ENTITY_STATUS.RECORDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    private fun election(
        dsl: DSLContext,
        tenant: TenantId,
        ref: String,
        from: LocalDate,
    ) {
        dsl
            .insertInto(EXEMPT_PURCHASE_ELECTION)
            .set(EXEMPT_PURCHASE_ELECTION.TENANT_ID, tenant.value)
            .set(EXEMPT_PURCHASE_ELECTION.ENTITY_REF, ref)
            .set(EXEMPT_PURCHASE_ELECTION.EFFECTIVE_FROM, from)
            .set(EXEMPT_PURCHASE_ELECTION.TAX_CODES, arrayOf("TX-E33"))
            .set(EXEMPT_PURCHASE_ELECTION.BASIS, "basis")
            .set(EXEMPT_PURCHASE_ELECTION.RECORDED_BY, "user-1")
            .execute()
    }

    private fun cutover(
        dsl: DSLContext,
        tenant: TenantId,
        entity: UUID,
        on: LocalDate,
    ) {
        dsl
            .insertInto(MIGRATION_CUTOVER)
            .set(MIGRATION_CUTOVER.ID, UUID.randomUUID())
            .set(MIGRATION_CUTOVER.TENANT_ID, tenant.value)
            .set(MIGRATION_CUTOVER.ENTITY_ID, entity)
            .set(MIGRATION_CUTOVER.CUTOVER_DATE, on)
            .set(MIGRATION_CUTOVER.PRIOR_SOLUTION, "prior")
            .set(MIGRATION_CUTOVER.APPROVED_BY, "user-1")
            .set(MIGRATION_CUTOVER.RECORDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    /** T1: E-S, E-B, E-X, E-M, E-C, E-N; T2: E-T2. Refs carry a tag to stay unique. */
    private fun seed(): Seed {
        val tag = UUID.randomUUID().toString().take(8)
        val s = Seed(TenantId(UUID.randomUUID()), TenantId(UUID.randomUUID()), tag)
        Db.superuser { dsl ->
            insertTenant(dsl, s.t1, "1$tag")
            insertTenant(dsl, s.t2, "2$tag")
            val g1 = UUID.randomUUID()
            val g2 = UUID.randomUUID()
            insertGroup(dsl, s.t1, g1, "G-1-$tag")
            insertGroup(dsl, s.t2, g2, "G-2-$tag")
            status(dsl, s.t1, insertEntity(dsl, s.t1, g1, s.ref("E-S")), d("2026-03-01"))
            status(dsl, s.t1, insertEntity(dsl, s.t1, g1, s.ref("E-B")), d("2026-03-01"))
            election(dsl, s.t1, s.ref("E-B"), d("2026-09-01"))
            insertEntity(dsl, s.t1, g1, s.ref("E-X"))
            election(dsl, s.t1, s.ref("E-X"), d("2026-05-10"))
            insertEntity(dsl, s.t1, g1, s.ref("E-M"))
            election(dsl, s.t1, s.ref("E-M"), d("2026-04-01"))
            election(dsl, s.t1, s.ref("E-M"), d("2026-08-01"))
            cutover(dsl, s.t1, insertEntity(dsl, s.t1, g1, s.ref("E-C")), d("2026-06-15"))
            insertEntity(dsl, s.t1, g1, s.ref("E-N"))
            insertEntity(dsl, s.t2, g2, s.ref("E-T2"))
            election(dsl, s.t2, s.ref("E-T2"), d("2026-02-02"))
        }
        return s
    }

    /** The code (at most 10 of A-Z, 0-9): the last ten characters of the upper-cased ref without its dashes. */
    private fun codeOf(ref: String) = ref.replace("-", "").uppercase().takeLast(CODE_LENGTH)

    private class Replayed(
        val rows: List<ReplayNode>,
        val payloads: List<ObjectNode>,
    ) {
        fun payload(ref: String) = payloads.single { it.get("entityRef").asString() == ref }

        fun row(ref: String) = rows.single { it.ref == ref }
    }

    /** `profiles(null, null, limit)` paged to its end (limit 2), each row given to `payload`. */
    private fun replay(tenant: TenantId): Replayed =
        Db.bound(tenant) { dsl ->
            val reader = JooqReplayReader(dsl, context(tenant))
            val source = EntityProfileReplaySource(reader, mapper)
            val rows = ArrayList<ReplayNode>()
            var cursor: ReplayCursor? = null
            do {
                val page = reader.profiles(null, cursor, PAGE)
                rows += page
                cursor = page.lastOrNull()?.let { ReplayCursor(it.type, it.ref) }
            } while (page.size == PAGE)
            Replayed(rows, rows.map { source.payload(it) })
        }

    @Test
    fun `INV-AC-25373 R1 the replay returns exactly the entities with an entry, not E-N, not another tenant`() {
        val s = seed()
        val t1 = replay(s.t1)
        t1.payloads.map { it.get("entityRef").asString() } shouldContainExactlyInAnyOrder
            listOf("E-S", "E-B", "E-X", "E-M", "E-C").map(s::ref)
        replay(s.t2).payloads.map { it.get("entityRef").asString() } shouldBe listOf(s.ref("E-T2"))
    }

    @Test
    fun `INV-AC-25373 R2 effectiveDate is the status date or else the greatest date of the profile entries`() {
        val s = seed()
        val t1 = replay(s.t1)
        val dates =
            listOf(
                "E-S" to "2026-03-01",
                "E-B" to "2026-03-01",
                "E-X" to "2026-05-10",
                "E-M" to "2026-08-01",
                "E-C" to "2026-06-15",
            )
        dates.forEach { (name, date) -> t1.payload(s.ref(name)).get("effectiveDate").asString() shouldBe date }
        replay(s.t2).payload(s.ref("E-T2")).get("effectiveDate").asString() shouldBe "2026-02-02"
    }

    @Test
    fun `INV-AC-25373 R3 changedAttributes lists one item per profile entry in order for E-X, E-M and E-C`() {
        val s = seed()
        val t1 = replay(s.t1)
        listOf("E-X", "E-M", "E-C").map(s::ref).forEach { ref ->
            val profile = t1.row(ref).node.profile
            profile.isNotEmpty() shouldBe true
            val attributes = t1.payload(ref).get("changedAttributes")
            attributes.size() shouldBe profile.size
            profile.forEachIndexed { i, entry ->
                attributes.get(i).get("attribute").asString() shouldBe entry.attribute
                attributes.get(i).get("newValue").asString() shouldBe entry.value
            }
        }
    }

    private object Db {
        private val RUNTIME_PASSWORD = UUID.randomUUID().toString()
        private const val ACTOR = "actor"
        private val KEY = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer(PgTestImages.postgres(PgTestImages.PG17))
                .withStartupTimeout(Duration.ofMinutes(PG_START_TIMEOUT_MINUTES))
                .withStartupAttempts(PG_START_ATTEMPTS)
                .withCommand(*pgFastCommand)
                .withLabel("inv.agent", "c-inv-t-25374")

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

        /** A runtime-role transaction bound to [tenant] and [ACTOR] as TransactionRunnerImpl does, then committed. */
        fun <T> bound(
            tenant: TenantId,
            block: (DSLContext) -> T,
        ): T =
            DriverManager.getConnection(container.jdbcUrl, "tenancy_runtime", RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                val expiry = BindingSignature.expiryFor(Instant.now(), Duration.ofSeconds(30))
                val signature = BindingSignature.sign(KEY.toByteArray(Charsets.UTF_8), tenant, ACTOR, expiry)
                c
                    .prepareStatement(
                        "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true), " +
                            "set_config('app.binding_sig', ?, true)",
                    ).use { s ->
                        s.setString(1, tenant.toString())
                        s.setString(2, ACTOR)
                        s.setString(3, signature)
                        s.execute()
                    }
                val result = block(DSL.using(c, SQLDialect.POSTGRES))
                c.commit()
                result
            }
    }
}

private const val PG_START_TIMEOUT_MINUTES = 3L
private const val PG_START_ATTEMPTS = 3
private val pgFastCommand =
    arrayOf("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")

private const val PAGE = 2
private const val CODE_LENGTH = 10
