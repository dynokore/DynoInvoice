package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.PgTestImages
import com.dynokore.gst.tenancy.adapters.events.EntityProfileReplaySource
import com.dynokore.gst.tenancy.adapters.events.SchemaFixture
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY_STATUS
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.platform.outboxandqueue.ReplayedState
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
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
 * INV-AC-21529 over PostgreSQL with forced row-level security: the replay of EntityProfileChanged carries `entityId`,
 * the id of the row in tenancy.entity, on the state of each entity.
 */
class EntityProfileReplayEntityIdDbTest {
    private val mapper = JsonMapper.builder().build()
    private val schema = SchemaFixture.schema("entity-profile-changed-payload.schema.json")
    private val effective = LocalDate.of(2026, 7, 1)

    private class Seed(
        val t1: TenantId,
        val t2: TenantId,
        val ids: List<UUID>,
        val tag: String,
    ) {
        val i1 = ids[0]
        val i2 = ids[1]
        val i3 = ids[2]
        val group = ids[3]
        val g1 = "G-1-$tag"
        val g2 = "G-2-$tag"
        val e1 = "E-001-$tag"
        val e2 = "E-002-$tag"
        val e3 = "E-001-t2-$tag"
    }

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

    private fun source(
        dsl: DSLContext,
        tenant: TenantId,
    ) = EntityProfileReplaySource(JooqReplayReader(dsl, context(tenant)), mapper)

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

    /** An entity with ref [ref] and one PARTIAL_EXEMPTION status entry. */
    private fun insertEntity(
        dsl: DSLContext,
        tenant: TenantId,
        group: UUID,
        id: UUID,
        ref: String,
    ) {
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
        dsl
            .insertInto(ENTITY_STATUS)
            .set(ENTITY_STATUS.ID, UUID.randomUUID())
            .set(ENTITY_STATUS.TENANT_ID, tenant.value)
            .set(ENTITY_STATUS.ENTITY_ID, id)
            .set(ENTITY_STATUS.STATUS_KIND, "PARTIAL_EXEMPTION")
            .set(ENTITY_STATUS.EFFECTIVE_FROM, effective)
            .set(ENTITY_STATUS.PARTIALLY_EXEMPT, true)
            .set(ENTITY_STATUS.BASIS, "basis")
            .set(ENTITY_STATUS.RECORDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    /** T1: E-001 (I1), E-002 (I2), group G-1; T2: E-001 (I3), its own group. Refs carry a tag to stay unique. */
    private fun seed(): Seed {
        val tag = UUID.randomUUID().toString().take(8)
        val s = Seed(TenantId(UUID.randomUUID()), TenantId(UUID.randomUUID()), List(4) { id() }, tag)
        Db.superuser { dsl ->
            insertTenant(dsl, s.t1, "1$tag")
            insertTenant(dsl, s.t2, "2$tag")
            insertGroup(dsl, s.t1, s.group, s.g1)
            val group2 = id()
            insertGroup(dsl, s.t2, group2, s.g2)
            insertEntity(dsl, s.t1, s.group, s.i1, s.e1)
            insertEntity(dsl, s.t1, s.group, s.i2, s.e2)
            insertEntity(dsl, s.t2, group2, s.i3, s.e3)
        }
        return s
    }

    private fun id() = UUID.randomUUID()

    /** The entity code (at most 10 of A-Z, 0-9): the ref without its dash, then the last four of the ref. */
    private fun codeOf(ref: String) = ref.take(5).replace("-", "") + ref.takeLast(4).uppercase()

    /** The replay of [tenant] read page by page with limit 1 until the cursor is empty. */
    private fun replay(tenant: TenantId): List<ReplayedState> {
        val out = ArrayList<ReplayedState>()
        var cursor: String? = null
        do {
            val page = Db.bound(tenant) { source(it, tenant).page(null, cursor, 1) }
            val (items, next) = page
            out += items
            cursor = next
        } while (cursor != null)
        return out
    }

    private fun json(state: ReplayedState) = mapper.readTree(state.dataJson) as ObjectNode

    private fun structureVersion(
        tenant: TenantId,
        ref: String,
    ): Long =
        Db.superuser { dsl ->
            dsl
                .select(ENTITY.STRUCTURE_VERSION)
                .from(ENTITY)
                .where(ENTITY.TENANT_ID.eq(tenant.value).and(ENTITY.REF.eq(ref)))
                .fetchOne(ENTITY.STRUCTURE_VERSION)
        } ?: error("no entity $ref")

    /** The payload as it was before this change: no entityId member. */
    private fun expectedWithoutId(
        tenant: TenantId,
        ref: String,
        group: String,
    ): ObjectNode =
        mapper.readTree(
            """
            {"entityRef":"$ref","changedAttributes":[{"attribute":"partiallyExempt","newValue":"true"}],
             "effectiveDate":"$effective","constitution":"PRIVATE_LIMITED",
             "node":{"kind":"ENTITY","parentRef":"$group","retired":false,"name":"Entity $ref","code":"${codeOf(ref)}",
              "profile":[{"attribute":"partiallyExempt","value":"true","effectiveDate":"$effective"}],
              "structureVersion":${structureVersion(tenant, ref)}}}
            """.trimIndent(),
        ) as ObjectNode

    private fun withId(
        base: ObjectNode,
        id: String,
    ): ObjectNode {
        val out = mapper.createObjectNode()
        out.setAll(base)
        out.put("entityId", id)
        return out
    }

    @Test
    fun `INV-AC-21529 R1 the replay of T1 holds one state per entity carrying the row id of its entity`() {
        val s = seed()
        val states = replay(s.t1)
        states.size shouldBe 2
        states.map { it.aggregateKey } shouldBe listOf(s.e1, s.e2)
        json(states[0]) shouldBe withId(expectedWithoutId(s.t1, s.e1, s.g1), s.i1.toString())
        json(states[1]) shouldBe withId(expectedWithoutId(s.t1, s.e2, s.g1), s.i2.toString())
        json(states[0]).get("entityId").asString() shouldBe s.i1.toString().lowercase()
        states.forEach { schema.validate(it.dataJson, com.networknt.schema.InputFormat.JSON).shouldBeEmpty() }
    }

    @Test
    fun `INV-AC-21529 R1 the state of a non-entity node holds no entityId`() {
        val s = seed()
        val group =
            Db.bound(s.t1) { dsl ->
                JooqReplayReader(dsl, context(s.t1)).structure(null, null, 100).first { it.type.name == "GROUP" }
            }
        group.entityId shouldBe null
        val payload = Db.bound(s.t1) { source(it, s.t1).payload(group.copy(profileEffectiveDate = effective)) }
        payload.has("entityId") shouldBe false
    }

    @Test
    fun `INV-AC-21529 R2 the replay of T2 carries the id of its own E-001 and not that of T1`() {
        val s = seed()
        val states = replay(s.t2)
        states.size shouldBe 1
        json(states[0]) shouldBe withId(expectedWithoutId(s.t2, s.e3, s.g2), s.i3.toString())
        (json(states[0]).get("entityId").asString() == s.i1.toString()) shouldBe false
        schema.validate(states[0].dataJson, com.networknt.schema.InputFormat.JSON).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-21529 R3 a ReplayNode built with five arguments yields a payload with no entityId member`() {
        val s = seed()
        val node =
            Db
                .bound(s.t1) { dsl -> JooqReplayReader(dsl, context(s.t1)).profiles(null, null, 1) }
                .single()
        val five = ReplayNode(node.type, node.ref, node.entityRef, node.node, node.profileEffectiveDate)
        val payload = Db.bound(s.t1) { source(it, s.t1).payload(five) }
        payload.has("entityId") shouldBe false
        mapper.readTree(payload.toString()) shouldBe expectedWithoutId(s.t1, s.e1, s.g1)
        schema.validate(payload.toString(), com.networknt.schema.InputFormat.JSON).shouldBeEmpty()
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
                .withLabel("inv.agent", "c-inv-t-21530")

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
