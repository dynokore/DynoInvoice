package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.PgTestImages
import com.dynokore.gst.tenancy.adapters.events.EntityProfileChangedOutbox
import com.dynokore.gst.tenancy.adapters.events.RecordingOutbox
import com.dynokore.gst.tenancy.adapters.events.SchemaFixture
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.application.status.ChangedAttribute
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
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
 * INV-AC-21526 over PostgreSQL with forced row-level security: EntityProfileChanged carries `entityId`, the id of the
 * row in tenancy.entity named by entityRef, for an ENTITY node whose entity exists.
 */
class EntityProfileChangedEntityIdDbTest {
    private val mapper = JsonMapper.builder().build()
    private val schema = SchemaFixture.schema("entity-profile-changed-payload.schema.json")
    private val effective = LocalDate.of(2026, 7, 1)

    private class Seed(
        val t1: TenantId,
        val t2: TenantId,
        val i1: UUID,
        val i2: UUID,
        val tag: String,
    ) {
        val e1 = "E-001-$tag"
        val e2 = "E-001-t2-$tag"
        val g1 = "G-1-$tag"
        val missing = "E-404-$tag"
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

    private fun newCode() =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(CODE_LENGTH)
            .uppercase()

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
            .set(ENTITY.CODE, newCode())
            .set(ENTITY.LIFECYCLE_STATE, "DRAFT")
            .set(ENTITY.STRUCTURE_VERSION, version(dsl))
            .set(ENTITY.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    /** T1: entity E-001 (I1), group G-1; T2: entity E-001 (I2) with its own ref (entity.ref is globally unique). */
    private fun seed(): Seed {
        val tag = UUID.randomUUID().toString().take(8)
        val s =
            Seed(
                TenantId(UUID.randomUUID()),
                TenantId(UUID.randomUUID()),
                UUID.randomUUID(),
                UUID.randomUUID(),
                tag,
            )
        Db.superuser { dsl ->
            insertTenant(dsl, s.t1, "1$tag")
            insertTenant(dsl, s.t2, "2$tag")
            val group1 = UUID.randomUUID()
            val group2 = UUID.randomUUID()
            insertGroup(dsl, s.t1, group1, s.g1)
            insertGroup(dsl, s.t2, group2, "G-2-$tag")
            insertEntity(dsl, s.t1, group1, s.i1, s.e1)
            insertEntity(dsl, s.t2, group2, s.i2, s.e2)
            // An entity whose ref equals the group's ref: only the node kind keeps the GROUP change free of an id.
            insertEntity(dsl, s.t1, group1, UUID.randomUUID(), s.g1)
        }
        return s
    }

    private fun change(
        tenant: TenantId,
        ref: String,
        kind: NodeType,
    ) = EntityProfileChange(
        tenant = tenant.value,
        entityRef = ref,
        changedAttributes = listOf(ChangedAttribute("partiallyExempt", "false", "true")),
        effectiveDate = effective,
        node =
            StructureNodeState(
                kind,
                "PARENT",
                false,
                "Name $ref",
                "CODE",
                7,
                profile = listOf(ProfileEntry("partiallyExempt", "true", effective)),
            ),
    )

    /** The payload as it was before this change: no entityId member. */
    private fun expectedWithoutId(
        ref: String,
        kind: NodeType,
    ): ObjectNode =
        mapper.readTree(
            """
            {"entityRef":"$ref",
             "changedAttributes":[{"attribute":"partiallyExempt","oldValue":"false","newValue":"true"}],
             "effectiveDate":"$effective",
             "node":{"kind":"${kind.name}","parentRef":"PARENT","retired":false,"name":"Name $ref","code":"CODE",
              "profile":[{"attribute":"partiallyExempt","value":"true","effectiveDate":"$effective"}],
              "structureVersion":7}}
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

    /** Publishes [change] through the outbox over a lookup on [dsl], returns the one appended payload. */
    private fun publish(
        dsl: DSLContext,
        bound: TenantId,
        change: EntityProfileChange,
        withLookup: Boolean = true,
    ): ObjectNode {
        val recording = RecordingOutbox()
        val outbox =
            if (withLookup) {
                EntityProfileChangedOutbox(recording, mapper, JooqEntityIdLookup(dsl, context(bound))::lookup)
            } else {
                EntityProfileChangedOutbox(recording, mapper)
            }
        outbox.publish(change)
        recording.events.size shouldBe 1
        val payload = mapper.readTree(recording.events.single().dataJson) as ObjectNode
        schema.validate(payload.toString(), com.networknt.schema.InputFormat.JSON).shouldBeEmpty()
        return payload
    }

    @Test
    fun `INV-AC-21526 L1 an ENTITY change of T1 E-001 carries the row id I1 as lower-case string`() {
        val s = seed()
        val payload = Db.bound(s.t1) { publish(it, s.t1, change(s.t1, s.e1, NodeType.ENTITY)) }
        payload shouldBe withId(expectedWithoutId(s.e1, NodeType.ENTITY), s.i1.toString())
        payload.get("entityId").asString() shouldBe s.i1.toString().lowercase()
    }

    @Test
    fun `INV-AC-21526 L2 the E-001 of T2 carries the id I2 and not I1`() {
        val s = seed()
        val payload = Db.bound(s.t2) { publish(it, s.t2, change(s.t2, s.e2, NodeType.ENTITY)) }
        payload shouldBe withId(expectedWithoutId(s.e2, NodeType.ENTITY), s.i2.toString())
        (payload.get("entityId").asString() == s.i1.toString()) shouldBe false
    }

    @Test
    fun `INV-AC-21526 L2 without row-level security the lookup answers by the tenant given and never with I1 for T2`() {
        val s = seed()
        val own = Db.superuser { publish(it, s.t1, change(s.t2, s.e2, NodeType.ENTITY)) }
        own shouldBe withId(expectedWithoutId(s.e2, NodeType.ENTITY), s.i2.toString())
        val foreign = Db.superuser { publish(it, s.t1, change(s.t2, s.e1, NodeType.ENTITY)) }
        foreign shouldBe expectedWithoutId(s.e1, NodeType.ENTITY)
    }

    @Test
    fun `INV-AC-21526 L3 a GROUP change carries no entityId member`() {
        val s = seed()
        val payload = Db.bound(s.t1) { publish(it, s.t1, change(s.t1, s.g1, NodeType.GROUP)) }
        payload shouldBe expectedWithoutId(s.g1, NodeType.GROUP)
        payload.has("entityId") shouldBe false
    }

    @Test
    fun `INV-AC-21526 L4 an ENTITY change of an entity that does not exist carries no entityId member`() {
        val s = seed()
        val payload = Db.bound(s.t1) { publish(it, s.t1, change(s.t1, s.missing, NodeType.ENTITY)) }
        payload shouldBe expectedWithoutId(s.missing, NodeType.ENTITY)
        payload.has("entityId") shouldBe false
    }

    @Test
    fun `INV-AC-21526 L5 an outbox built with two arguments carries no entityId member`() {
        val s = seed()
        val payload = Db.bound(s.t1) { publish(it, s.t1, change(s.t1, s.e1, NodeType.ENTITY), withLookup = false) }
        payload shouldBe expectedWithoutId(s.e1, NodeType.ENTITY)
        payload.has("entityId") shouldBe false
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
                .withLabel("inv.agent", "c-inv-t-21527")

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

private const val CODE_LENGTH = 10
private const val PG_START_TIMEOUT_MINUTES = 3L
private const val PG_START_ATTEMPTS = 3
private val pgFastCommand =
    arrayOf("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
