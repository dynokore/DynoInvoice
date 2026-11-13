package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.PERIOD_DUE_DATE_VERSION
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.application.period.StoredPeriod
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * INV-AC-20668 over PostgreSQL with forced row-level security: the per-period due date counter drawn by
 * `JooqPeriodRecordStore.setDueDate` in the changing transaction.
 */
class JooqPeriodDueDateVersionDbTest {
    private val start = LocalDate.of(2026, 1, 1)
    private val ref = "2026-Q1"

    private class Seed(
        val tenant: TenantId,
        val e1: UUID,
        val e2: UUID,
    )

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

    private fun period(periodRef: String = ref) =
        StoredPeriod(periodRef, start, start.plusMonths(3).minusDays(1), "F5", start.plusMonths(4), emptySet())

    private fun insertEntity(
        dsl: DSLContext,
        tenant: TenantId,
        group: UUID,
        id: UUID,
        label: String,
    ) {
        dsl
            .insertInto(ENTITY)
            .set(ENTITY.ID, id)
            .set(ENTITY.TENANT_ID, tenant.value)
            .set(ENTITY.REF, "e-$label")
            .set(ENTITY.GROUP_ID, group)
            .set(ENTITY.LEGAL_NAME, "Entity $label")
            .set(ENTITY.CONSTITUTION, "COMPANY")
            .set(ENTITY.CODE, "E$label".uppercase())
            .set(ENTITY.LIFECYCLE_STATE, "DRAFT")
            .set(ENTITY.STRUCTURE_VERSION, dsl.fetchValue("select nextval('tenancy.structure_version_seq')") as Long)
            .set(ENTITY.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    /** A tenant with one group and two entities, each with one period record in force. */
    private fun seed(): Seed {
        val tag = UUID.randomUUID().toString().take(8)
        val tenant = TenantId(UUID.randomUUID())
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        Db.superuser { dsl ->
            fun version() = dsl.fetchValue("select nextval('tenancy.structure_version_seq')") as Long
            dsl
                .insertInto(TENANT)
                .set(TENANT.TENANT_ID, tenant.value)
                .set(TENANT.REF, "T-$tag")
                .set(TENANT.LEGAL_NAME, "Legal")
                .set(TENANT.PLAN_REF, "plan")
                .set(TENANT.CONTRACT_REFERENCE, "c-$tag")
                .set(TENANT.STRUCTURE_VERSION, version())
                .set(TENANT.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
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
            ids.forEachIndexed { index, id -> insertEntity(dsl, tenant, group, id, "$index$tag") }
        }
        Db.bound(tenant) { dsl -> ids.forEach { JooqPeriodRecordStore(dsl, context(tenant)).insert(it, period()) } }
        return Seed(tenant, ids[0], ids[1])
    }

    private fun draw(
        tenant: TenantId,
        entity: UUID,
        periodRef: String = ref,
    ): Long =
        Db.bound(
            tenant,
        ) { JooqPeriodRecordStore(it, context(tenant)).setDueDate(entity, periodRef, start.plusMonths(5)) }

    @Test
    fun `INV-AC-20668 the first call returns 1 and the second 2`() {
        val s = seed()
        draw(s.tenant, s.e1) shouldBe 1L
        draw(s.tenant, s.e1) shouldBe 2L
    }

    @Test
    fun `INV-AC-20668 two entities with the same period reference each start at 1`() {
        val s = seed()
        draw(s.tenant, s.e1) shouldBe 1L
        draw(s.tenant, s.e2) shouldBe 1L
        draw(s.tenant, s.e1) shouldBe 2L
    }

    @Test
    fun `INV-AC-20668 the counter keeps counting after the period record is superseded and re-created`() {
        val s = seed()
        draw(s.tenant, s.e1) shouldBe 1L
        draw(s.tenant, s.e1) shouldBe 2L
        Db.bound(s.tenant) {
            val store = JooqPeriodRecordStore(it, context(s.tenant))
            store.supersedeFrom(s.e1, start)
            store.insert(s.e1, period())
        }
        draw(s.tenant, s.e1) shouldBe 3L
    }

    @Test
    fun `INV-AC-20668 a counter row of another tenant is neither read nor incremented`() {
        val a = seed()
        val b = seed()
        draw(a.tenant, a.e1) shouldBe 1L
        draw(a.tenant, a.e1) shouldBe 2L
        draw(b.tenant, b.e1) shouldBe 1L
        // The runtime login bound to B sees no counter row of A.
        Db.bound(b.tenant) { dsl -> dsl.fetchCount(PERIOD_DUE_DATE_VERSION) } shouldBe 1
        Db.bound(
            b.tenant,
        ) { dsl -> dsl.fetchCount(PERIOD_DUE_DATE_VERSION, PERIOD_DUE_DATE_VERSION.ENTITY_ID.eq(a.e1)) } shouldBe
            0
        Db.superuser { dsl ->
            dsl
                .select(PERIOD_DUE_DATE_VERSION.VERSION)
                .from(PERIOD_DUE_DATE_VERSION)
                .where(PERIOD_DUE_DATE_VERSION.ENTITY_ID.eq(a.e1))
                .fetchOne(PERIOD_DUE_DATE_VERSION.VERSION)
        } shouldBe 2L
    }

    @Test
    fun `INV-AC-20668 two threads changing the same period at once get two distinct versions n+1 and n+2`() {
        val s = seed()
        draw(s.tenant, s.e1) shouldBe 1L
        val pool = Executors.newFixedThreadPool(2)
        try {
            val go = CountDownLatch(1)
            val results =
                (1..2).map {
                    pool.submit<Long> {
                        go.await()
                        draw(s.tenant, s.e1)
                    }
                }
            go.countDown()
            results.map { it.get(60, TimeUnit.SECONDS) }.toSet() shouldBe setOf(2L, 3L)
        } finally {
            pool.shutdownNow()
        }
        draw(s.tenant, s.e1) shouldBe 4L
    }

    private object Db {
        private const val RUNTIME_PASSWORD = "runtime-password-for-tests"
        private const val ACTOR = "actor"
        private const val KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3")
                .withStartupTimeout(Duration.ofMinutes(PG_START_TIMEOUT_MINUTES))
                .withStartupAttempts(PG_START_ATTEMPTS)
                .withCommand(*pgFastCommand)
                .withLabel("inv.agent", "c-inv-t-20669")

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

// CI-FAST: a loaded CI host can need more than the 60 s Testcontainers default for initdb and its restart; a failed
// start is retried with a fresh container, and the throwaway test database skips fsync.
private const val PG_START_TIMEOUT_MINUTES = 3L
private const val PG_START_ATTEMPTS = 3
private val pgFastCommand =
    arrayOf("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
