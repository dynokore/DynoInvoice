package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.events.EntityProfileChangedOutbox
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.EXEMPT_PURCHASE_ELECTION
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.application.election.ElectionCommands
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseElectionRow
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseElections
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseOutcome
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseProfileExtras
import com.dynokore.gst.tenancy.application.migration.MigrationCutover
import com.dynokore.gst.tenancy.application.migration.MigrationCutoverStore
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureQueries
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-18133 over PostgreSQL with forced row-level security: the exempt-purchase election of E1 is stored
 * effective-dated, published in EntityProfileChanged node.profile, and the structure read and the replay read of E1
 * return the same two entries; E2 and another tenant return neither.
 */
class ExemptPurchaseElectionStoreTest {
    private val mapper = JsonMapper.builder().build()
    private val clock = Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC)
    private val from = LocalDate.of(2026, 10, 1)
    private val basis = "Legal fees relate to exempt supplies"

    private class Seed(
        val tenant: TenantId,
        val e1: String,
        val e2: String,
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

    /** A port the scenario never calls through: every method answers an empty list or nothing. */
    private inline fun <reified T : Any> idle(): T =
        T::class.java.cast(
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                if (method.returnType == List::class.java) emptyList<Any>() else null
            },
        )

    /** A tenant with one group and the entities E1 and E2 (E2 only when [second]). */
    private fun seed(): Seed {
        val tag = UUID.randomUUID().toString().take(8)
        val tenant = TenantId(UUID.randomUUID())
        val e1 = "e1-$tag"
        val e2 = "e2-$tag"
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
            listOf(e1, e2).forEachIndexed { index, ref ->
                insertEntity(dsl, NodeSeed(tenant, group, tag, ::version), ref, index)
            }
        }
        return Seed(tenant, e1, e2)
    }

    private class NodeSeed(
        val tenant: TenantId,
        val group: UUID,
        val tag: String,
        val version: () -> Long,
    )

    private fun insertEntity(
        dsl: DSLContext,
        seed: NodeSeed,
        ref: String,
        index: Int,
    ) {
        dsl
            .insertInto(ENTITY)
            .set(ENTITY.ID, UUID.randomUUID())
            .set(ENTITY.TENANT_ID, seed.tenant.value)
            .set(ENTITY.REF, ref)
            .set(ENTITY.GROUP_ID, seed.group)
            .set(ENTITY.LEGAL_NAME, "Entity $index")
            .set(ENTITY.CONSTITUTION, "COMPANY")
            .set(ENTITY.CODE, "E$index${seed.tag}".uppercase())
            .set(ENTITY.LIFECYCLE_STATE, "DRAFT")
            .set(ENTITY.STRUCTURE_VERSION, seed.version())
            .set(ENTITY.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    /** A cutover store that holds no record, so the profile carries no migrationCutoverDate. */
    private val noCutovers =
        object : MigrationCutoverStore {
            override fun find(entityRef: String): MigrationCutover? = null

            override fun insert(
                entityId: UUID,
                cutover: MigrationCutover,
            ): Boolean = error("insert is not used")
        }

    private fun commands(
        dsl: DSLContext,
        tenant: TenantId,
    ): ElectionCommands {
        val tenants = context(tenant)
        val store = JooqExemptPurchaseElectionStore(dsl, tenants)
        val structure = JooqStructureStore(dsl, tenants)
        val direct =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        val writer =
            JdbcOutboxWriter(dsl, "tenancy", "tenancy-svc", "instance-1", TenancyEventCatalogue.load(), clock)

        return ElectionCommands(
            direct,
            structure,
            idle(),
            JooqEntityStatusStore(dsl, tenants),
            EntityProfileChangedOutbox(writer, mapper),
            idle(),
            idle(),
            idle(),
            { _, _, _ -> true },
            { "user-1" },
            idle(),
            idle(),
            tenants,
            ExemptPurchaseElections(
                direct,
                structure,
                store,
                { _, _, _ -> true },
                { "user-1" },
                idle(),
                tenants,
                clock,
            ),
            store,
            noCutovers,
            clock,
        )
    }

    /** E1 elects TX-N33 and TX-ESS from 1 October through the application class, in a committed transaction. */
    private fun recorded(): Pair<Seed, Seed> {
        val one = seed()
        val other = seed()
        val outcome =
            Db.bound(one.tenant, "svc:tenancy-svc") { dsl ->
                commands(dsl, one.tenant).recordExemptPurchase(one.e1, listOf("TX-N33", "TX-ESS"), basis, from)
            }
        (outcome is ExemptPurchaseOutcome.Done && !outcome.repeated) shouldBe true
        return one to other
    }

    private fun event(seed: Seed): tools.jackson.databind.JsonNode {
        val rows =
            Db.superuser {
                it.fetchValues(
                    "select data::text from tenancy.outbox where tenant_id = ? and type like '%EntityProfileChanged%'",
                    seed.tenant.value,
                )
            }
        rows.size shouldBe 1
        return mapper.readTree(rows.single() as String)
    }

    private fun eventProfile(seed: Seed): List<ProfileEntry> =
        event(seed)
            .get("node")
            .get("profile")
            .iterator()
            .asSequence()
            .map {
                ProfileEntry(
                    it.get("attribute").asString(),
                    it.get("value").asString(),
                    LocalDate.parse(it.get("effectiveDate").asString()),
                )
            }.toList()

    private fun structureProfile(
        seed: Seed,
        ref: String,
    ): List<ProfileEntry> =
        Db.bound(seed.tenant, "svc:tenancy-svc") { dsl ->
            val tenants = context(seed.tenant)
            val store = JooqExemptPurchaseElectionStore(dsl, tenants)
            val direct =
                object : TransactionRunner {
                    override fun <T> inTransaction(block: () -> T): T = block()
                }
            StructureQueries(
                direct,
                JooqStructureStore(dsl, tenants),
                idle(),
                JooqEntityStatusStore(dsl, tenants),
                ExemptPurchaseProfileExtras(store),
            ).node(ref)!!.node.profile
        }

    private fun replayProfile(
        seed: Seed,
        ref: String,
    ): List<ProfileEntry> =
        Db.bound(seed.tenant, "svc:tenancy-svc") { dsl ->
            JooqReplayReader(dsl, context(seed.tenant))
                .structure(null, null, 100)
                .single { it.ref == ref }
                .node.profile
        }

    private val expected =
        listOf(
            ProfileEntry("exemptPurchaseCodes", "TX-ESS,TX-N33", LocalDate.of(2026, 10, 1)),
            ProfileEntry("exemptPurchaseBasis", basis, LocalDate.of(2026, 10, 1)),
        )

    @Test
    fun `INV-AC-18133 the EntityProfileChanged event of E1 holds the codes and the basis from 1 October`() {
        val (one, _) = recorded()
        eventProfile(one).toSet() shouldBe expected.toSet()
        eventProfile(one).size shouldBe 2
        event(one).get("effectiveDate").asString() shouldBe "2026-10-01"
        event(
            one,
        ).get("changedAttributes").iterator().asSequence().map { it.get("attribute").asString() }.toSet() shouldBe
            setOf("exemptPurchaseCodes", "exemptPurchaseBasis")
    }

    @Test
    fun `INV-AC-18133 the structure read and the replay read of E1 return the same two entries`() {
        val (one, _) = recorded()
        structureProfile(one, one.e1).toSet() shouldBe expected.toSet()
        replayProfile(one, one.e1).toSet() shouldBe expected.toSet()
        structureProfile(one, one.e1).size shouldBe 2
        replayProfile(one, one.e1).size shouldBe 2
        structureProfile(one, one.e1).toSet() shouldBe eventProfile(one).toSet()
    }

    @Test
    fun `INV-AC-18133 E2 of the same tenant and the other tenant return neither entry`() {
        val (one, other) = recorded()
        structureProfile(one, one.e2) shouldBe emptyList()
        replayProfile(one, one.e2) shouldBe emptyList()
        structureProfile(other, other.e1) shouldBe emptyList()
        replayProfile(other, other.e1) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-18133 look-alike attribute names exemptPurchaseCode and ExemptPurchaseCodes are not published`() {
        val (one, _) = recorded()
        val names = eventProfile(one).map { it.attribute }
        names.contains("exemptPurchaseCode") shouldBe false
        names.contains("ExemptPurchaseCodes") shouldBe false
        names.contains("exemptPurchaseCodes") shouldBe true
        structureProfile(one, one.e1).map { it.attribute }.contains("exemptPurchaseCode") shouldBe false
        replayProfile(one, one.e1).map { it.attribute }.contains("ExemptPurchaseCodes") shouldBe false
    }

    @Test
    fun `INV-AC-18133 a record of E1 is not visible on E2 or on another tenant under forced row-level security`() {
        val (one, other) = recorded()
        Db.bound(one.tenant, "svc:tenancy-svc") { dsl ->
            val store = JooqExemptPurchaseElectionStore(dsl, context(one.tenant))
            store.history(one.e1).map { it.taxCodes } shouldBe listOf(listOf("TX-N33", "TX-ESS"))
            store.history(one.e1).single().recordedBy shouldBe "user-1"
            store.history(one.e2) shouldBe emptyList<ExemptPurchaseElectionRow>()
        }
        Db.bound(other.tenant, "svc:tenancy-svc") { dsl ->
            JooqExemptPurchaseElectionStore(dsl, context(other.tenant)).history(one.e1) shouldBe emptyList()
            dsl.fetchCount(EXEMPT_PURCHASE_ELECTION) shouldBe 0
        }
        Db.bound(one.tenant, "svc:tenancy-svc") { dsl -> dsl.fetchCount(EXEMPT_PURCHASE_ELECTION) shouldBe 1 }
    }

    private object Db {
        private const val RUNTIME_PASSWORD = "runtime-password-for-tests"
        private const val KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3")
                .withStartupTimeout(Duration.ofMinutes(PG_START_TIMEOUT_MINUTES))
                .withStartupAttempts(PG_START_ATTEMPTS)
                .withCommand(*pgFastCommand)
                .withLabel("inv.agent", "c-inv-t-18134")

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

// CI-FAST: a loaded CI host can need more than the 60 s Testcontainers default for initdb and its restart; a failed
// start is retried with a fresh container, and the throwaway test database skips fsync.
private const val PG_START_TIMEOUT_MINUTES = 3L
private const val PG_START_ATTEMPTS = 3
private val pgFastCommand =
    arrayOf("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
