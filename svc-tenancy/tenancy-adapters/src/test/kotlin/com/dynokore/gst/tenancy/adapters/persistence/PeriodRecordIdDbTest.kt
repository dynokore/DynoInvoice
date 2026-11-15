package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.PgTestImages
import com.dynokore.gst.tenancy.adapters.events.PeriodRecordsChangedOutbox
import com.dynokore.gst.tenancy.adapters.events.RecordingOutbox
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.FILING_FREQUENCY_HISTORY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.PERIOD_RECORD
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.PROPOSED_PERIOD
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.application.period.PeriodRecordsChangePublisher
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
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-21523 over PostgreSQL with forced row-level security: PeriodRecordsChanged carries on each period the id of
 * the row in `tenancy.period_record` that is in force, and no id for a value that is not a record.
 */
class PeriodRecordIdDbTest {
    private val mapper = JsonMapper.builder().build()

    private class Seed(
        val tenant: TenantId,
        val entity: UUID,
        val history: UUID,
        val ids: List<UUID>,
    ) {
        val i0 get() = ids[0]
        val i1 get() = ids[1]
        val i2 get() = ids[2]
        val i3 get() = ids[3]
    }

    private class Rec(
        val id: UUID,
        val ref: String,
        val start: LocalDate,
        val state: String,
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

    private fun version(dsl: DSLContext) = dsl.fetchValue("select nextval('tenancy.structure_version_seq')") as Long

    private fun insertRecord(
        dsl: DSLContext,
        tenant: TenantId,
        entity: UUID,
        rec: Rec,
    ) {
        dsl
            .insertInto(PERIOD_RECORD)
            .set(PERIOD_RECORD.ID, rec.id)
            .set(PERIOD_RECORD.TENANT_ID, tenant.value)
            .set(PERIOD_RECORD.ENTITY_ID, entity)
            .set(PERIOD_RECORD.PERIOD_REF, rec.ref)
            .set(PERIOD_RECORD.PERIOD_START, rec.start)
            .set(PERIOD_RECORD.PERIOD_END, rec.start.plusMonths(3).minusDays(1))
            .set(PERIOD_RECORD.RETURN_FORM, "F5")
            .set(PERIOD_RECORD.DUE_DATE, rec.start.plusMonths(4))
            .set(PERIOD_RECORD.KINDS, "")
            .set(PERIOD_RECORD.IRREGULAR, false)
            .set(PERIOD_RECORD.CONFIRMATION_STATE, "UNCONFIRMED")
            .set(PERIOD_RECORD.RECORD_STATE, rec.state)
            .set(PERIOD_RECORD.RECORDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    /** Entity E-001 with IN_FORCE P1 (I1) and P2 (I2), SUPERSEDED P1 (I0) and a proposed period P3 (I3). */
    private fun seed(): Seed {
        val tag = UUID.randomUUID().toString().take(8)
        val tenant = TenantId(UUID.randomUUID())
        val s = Seed(tenant, UUID.randomUUID(), UUID.randomUUID(), List(4) { UUID.randomUUID() })
        Db.superuser { dsl ->
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
            val group = UUID.randomUUID()
            dsl
                .insertInto(TENANT_GROUP)
                .set(TENANT_GROUP.ID, group)
                .set(TENANT_GROUP.TENANT_ID, tenant.value)
                .set(TENANT_GROUP.REF, "g-$tag")
                .set(TENANT_GROUP.NAME, "Group")
                .set(TENANT_GROUP.KIND, "DIVISIONAL")
                .set(TENANT_GROUP.STRUCTURE_VERSION, version(dsl))
                .set(TENANT_GROUP.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
            insertHistory(dsl, s, group, tag)
            insertRows(dsl, s)
        }
        return s
    }

    private fun insertHistory(
        dsl: DSLContext,
        s: Seed,
        group: UUID,
        tag: String,
    ) {
        val tenant = s.tenant
        dsl
            .insertInto(ENTITY)
            .set(ENTITY.ID, s.entity)
            .set(ENTITY.TENANT_ID, tenant.value)
            .set(ENTITY.REF, "e-$tag")
            .set(ENTITY.GROUP_ID, group)
            .set(ENTITY.LEGAL_NAME, "Entity")
            .set(ENTITY.CONSTITUTION, "COMPANY")
            .set(ENTITY.CODE, "E$tag".uppercase())
            .set(ENTITY.LIFECYCLE_STATE, "DRAFT")
            .set(ENTITY.STRUCTURE_VERSION, version(dsl))
            .set(ENTITY.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
        dsl
            .insertInto(FILING_FREQUENCY_HISTORY)
            .set(FILING_FREQUENCY_HISTORY.ID, s.history)
            .set(FILING_FREQUENCY_HISTORY.TENANT_ID, tenant.value)
            .set(FILING_FREQUENCY_HISTORY.ENTITY_ID, s.entity)
            .set(FILING_FREQUENCY_HISTORY.EFFECTIVE_FROM, P3_START)
            .set(FILING_FREQUENCY_HISTORY.FREQUENCY, "QUARTERLY")
            .set(FILING_FREQUENCY_HISTORY.IRAS_APPROVAL_REFERENCE, "IRAS-1")
            .set(FILING_FREQUENCY_HISTORY.STATE, "PENDING")
            .set(FILING_FREQUENCY_HISTORY.RECORDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    private fun insertRows(
        dsl: DSLContext,
        s: Seed,
    ) {
        val tenant = s.tenant
        insertRecord(dsl, tenant, s.entity, Rec(s.i0, "P1", P1_START, "SUPERSEDED"))
        insertRecord(dsl, tenant, s.entity, Rec(s.i1, "P1", P1_START, "IN_FORCE"))
        insertRecord(dsl, tenant, s.entity, Rec(s.i2, "P2", P2_START, "IN_FORCE"))
        dsl
            .insertInto(PROPOSED_PERIOD)
            .set(PROPOSED_PERIOD.ID, s.i3)
            .set(PROPOSED_PERIOD.TENANT_ID, tenant.value)
            .set(PROPOSED_PERIOD.ENTITY_ID, s.entity)
            .set(PROPOSED_PERIOD.HISTORY_ID, s.history)
            .set(PROPOSED_PERIOD.PERIOD_REF, "P3")
            .set(PROPOSED_PERIOD.PERIOD_START, P3_START)
            .set(PROPOSED_PERIOD.PERIOD_END, P3_START.plusMonths(3).minusDays(1))
            .set(PROPOSED_PERIOD.RETURN_FORM, "F5")
            .set(PROPOSED_PERIOD.DUE_DATE, P3_START.plusMonths(4))
            .set(PROPOSED_PERIOD.KINDS, "")
            .set(PROPOSED_PERIOD.IRREGULAR, false)
            .execute()
    }

    private fun publish(
        s: Seed,
        periods: List<StoredPeriod>,
    ): tools.jackson.databind.JsonNode {
        val recording = RecordingOutbox()
        PeriodRecordsChangedOutbox(recording, mapper, context(s.tenant))
            .publish(PeriodRecordsChangePublisher.changeOf("E-001", 7L, periods))
        return mapper.readTree(recording.events.single().dataJson)
    }

    private fun elements(array: tools.jackson.databind.JsonNode) = (0 until array.size()).map { array.get(it) }

    @Test
    fun `INV-AC-21523 P1 inForce holds the ids of the two records in force and the payload publishes them in order`() {
        val s = seed()
        val inForce = Db.bound(s.tenant) { JooqPeriodRecordStore(it, context(s.tenant)).inForce(s.entity) }
        inForce.map { it.periodRef } shouldBe listOf("P1", "P2")
        inForce.map { it.periodId } shouldBe listOf(s.i1, s.i2)
        val node = publish(s, inForce)
        node.get("entityRef").asString() shouldBe "E-001"
        node.get("version").asLong() shouldBe 7L
        val items = elements(node.get("periods"))
        items.size shouldBe 2
        items.map { it.get("periodId").asString() } shouldBe listOf(s.i1.toString(), s.i2.toString())
        items.map { it.get("periodId").asString() }.toSet().size shouldBe 2
        items.map { it.get("periodId").asString() }.contains(s.i0.toString()) shouldBe false
        items.map { it.get("periodId").asString() }.contains(s.i3.toString()) shouldBe false
        items.map { it.get("periodRef").asString() } shouldBe listOf("P1", "P2")
        items.map { it.get("periodStart").asString() } shouldBe listOf(P1_START.toString(), P2_START.toString())
        items.map { it.get("periodEnd").asString() } shouldBe
            listOf(P1_START.plusMonths(3).minusDays(1).toString(), P2_START.plusMonths(3).minusDays(1).toString())
        items.map { it.get("filingDueDate").asString() } shouldBe
            listOf(P1_START.plusMonths(4).toString(), P2_START.plusMonths(4).toString())
        items.forEach {
            it.propertyNames().toSet() shouldBe
                setOf("periodRef", "periodStart", "periodEnd", "filingDueDate", "periodId")
        }
    }

    @Test
    fun `INV-AC-21523 P2 the stored period of a proposal row holds no periodId`() {
        val s = seed()
        val proposed =
            Db.bound(s.tenant) { JooqProposalStore(it, context(s.tenant)).proposedPeriods(s.entity, s.history) }
        proposed.map { it.periodRef } shouldBe listOf("P3")
        proposed.single().periodId shouldBe null
    }

    @Test
    fun `INV-AC-21523 P3 a list of stored periods without an id publishes items without the member periodId`() {
        val s = seed()
        val plan =
            StoredPeriod("P3", P3_START, P3_START.plusMonths(3).minusDays(1), "F5", P3_START.plusMonths(4), emptySet())
        val items = publish(s, listOf(plan, plan.copy(periodRef = "P4"))).get("periods").let { elements(it) }
        items.size shouldBe 2
        items.forEach {
            it.has("periodId") shouldBe false
            it.propertyNames().toSet() shouldBe setOf("periodRef", "periodStart", "periodEnd", "filingDueDate")
        }
        items.map { it.get("periodRef").asString() } shouldBe listOf("P3", "P4")
        items.first().get("periodStart").asString() shouldBe P3_START.toString()
        items.first().get("filingDueDate").asString() shouldBe P3_START.plusMonths(4).toString()
    }

    private companion object {
        val P1_START: LocalDate = LocalDate.of(2026, 1, 1)
        val P2_START: LocalDate = LocalDate.of(2026, 4, 1)
        val P3_START: LocalDate = LocalDate.of(2026, 7, 1)
    }

    private object Db {
        private val RUNTIME_PASSWORD = randomHex(16)
        private const val ACTOR = "actor"
        private val KEY = randomHex(32)
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer(PgTestImages.postgres(PgTestImages.PG17))
                .withStartupTimeout(Duration.ofMinutes(PG_START_TIMEOUT_MINUTES))
                .withStartupAttempts(PG_START_ATTEMPTS)
                .withCommand(*pgFastCommand)
                .withLabel("inv.agent", "c-inv-t-21524")

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

private fun randomHex(bytes: Int): String =
    ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) }.let {
        java.util.HexFormat
            .of()
            .formatHex(it)
    }

// CI-FAST: a loaded CI host can need more than the 60 s Testcontainers default for initdb and its restart; a failed
// start is retried with a fresh container, and the throwaway test database skips fsync.
private const val PG_START_TIMEOUT_MINUTES = 3L
private const val PG_START_ATTEMPTS = 3
private val pgFastCommand =
    arrayOf("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
