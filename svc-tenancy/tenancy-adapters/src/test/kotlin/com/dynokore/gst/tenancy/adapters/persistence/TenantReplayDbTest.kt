package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.events.OrganisationStructureReplaySource
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.BUSINESS_UNIT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.tenant.WorkspaceArchival
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.ReplayScope
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.longs.shouldBeGreaterThan
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-15381 over PostgreSQL with forced row-level security (INV-Q-15377 A): the tenant's own node in the replay,
 * its three scopes, the cursor over four kinds, the look-alike pair `Acme` and `Acme-sandbox`, and the new structure
 * version `archive_workspace` stores.
 */
class TenantReplayDbTest {
    private val actor = "svc:tenancy-svc"
    private val mapper = JsonMapper.builder().build()

    private class Tenants(
        val production: TenantId,
        val sandbox: TenantId,
        val prodRef: String,
        val sandboxRef: String,
    )

    private fun unbound(id: TenantId) =
        object : TenantContext {
            override fun current(): TenantId = id

            override fun currentActor(): String = "actor"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    /** `Acme` (PRODUCTION) with a group, an entity and a unit, and its paired workspace `Acme-sandbox`. */
    private fun seed(): Tenants {
        val tag = UUID.randomUUID().toString().take(8)
        val t = Tenants(TenantId(UUID.randomUUID()), TenantId(UUID.randomUUID()), "Acme-$tag", "Acme-$tag-sandbox")
        Db.superuser { dsl ->
            fun version() = dsl.fetchValue("select nextval('tenancy.structure_version_seq')") as Long
            dsl
                .insertInto(TENANT)
                .set(TENANT.TENANT_ID, t.production.value)
                .set(TENANT.REF, t.prodRef)
                .set(TENANT.LEGAL_NAME, "Acme Legal")
                .set(TENANT.PLAN_REF, "plan")
                .set(TENANT.CONTRACT_REFERENCE, "c-$tag")
                .set(TENANT.STRUCTURE_VERSION, version())
                .set(TENANT.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
            dsl
                .insertInto(TENANT)
                .set(TENANT.TENANT_ID, t.sandbox.value)
                .set(TENANT.REF, t.sandboxRef)
                .set(TENANT.LEGAL_NAME, "Acme Legal")
                .set(TENANT.PLAN_REF, "plan")
                .set(TENANT.CONTRACT_REFERENCE, "c-$tag-sandbox")
                .set(TENANT.STRUCTURE_VERSION, version())
                .set(TENANT.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .set(TENANT.ENVIRONMENT, "SANDBOX")
                .set(TENANT.PAIRED_PRODUCTION_TENANT_ID, t.production.value)
                .set(TENANT.WORKSPACE_NAME, "sandbox")
                .execute()
            seedStructure(dsl, t, tag, ::version)
        }
        return t
    }

    private fun seedStructure(
        dsl: DSLContext,
        t: Tenants,
        tag: String,
        version: () -> Long,
    ) {
        run {
            val group = UUID.randomUUID()
            val entity = UUID.randomUUID()
            dsl
                .insertInto(TENANT_GROUP)
                .set(TENANT_GROUP.ID, group)
                .set(TENANT_GROUP.TENANT_ID, t.production.value)
                .set(TENANT_GROUP.REF, "g-$tag")
                .set(TENANT_GROUP.NAME, "Group")
                .set(TENANT_GROUP.KIND, "DIVISIONAL")
                .set(TENANT_GROUP.STRUCTURE_VERSION, version())
                .set(TENANT_GROUP.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
            dsl
                .insertInto(ENTITY)
                .set(ENTITY.ID, entity)
                .set(ENTITY.TENANT_ID, t.production.value)
                .set(ENTITY.REF, "e-$tag")
                .set(ENTITY.GROUP_ID, group)
                .set(ENTITY.LEGAL_NAME, "Entity")
                .set(ENTITY.CONSTITUTION, "COMPANY")
                .set(ENTITY.CODE, "E$tag".uppercase())
                .set(ENTITY.LIFECYCLE_STATE, "DRAFT")
                .set(ENTITY.STRUCTURE_VERSION, version())
                .set(ENTITY.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
            dsl
                .insertInto(BUSINESS_UNIT)
                .set(BUSINESS_UNIT.ID, UUID.randomUUID())
                .set(BUSINESS_UNIT.TENANT_ID, t.production.value)
                .set(BUSINESS_UNIT.REF, "u-$tag")
                .set(BUSINESS_UNIT.ENTITY_ID, entity)
                .set(BUSINESS_UNIT.NAME, "Unit")
                .set(BUSINESS_UNIT.CODE, "U$tag".uppercase())
                .set(BUSINESS_UNIT.STRUCTURE_VERSION, version())
                .set(BUSINESS_UNIT.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute()
        }
    }

    private fun <T> reading(
        tenant: TenantId,
        block: (JooqReplayReader) -> T,
    ): T = Db.bound(tenant, actor) { dsl -> block(JooqReplayReader(dsl, unbound(tenant))) }

    private fun changedAt(tenant: TenantId): Instant =
        Db.superuser { dsl ->
            dsl
                .select(TENANT.CHANGED_AT)
                .from(TENANT)
                .where(TENANT.TENANT_ID.eq(tenant.value))
                .fetchSingle()
                .value1()!!
                .toInstant()
        }

    @Test
    fun `INV-AC-15381 a page limit of 1 returns TENANT, GROUP, ENTITY and BUSINESS_UNIT over four pages`() {
        val t = seed()
        val seen = ArrayList<Pair<NodeType, String>>()
        var cursor: ReplayCursor? = null
        var pages = 0
        do {
            val page = reading(t.production) { it.structure(null, cursor, 2) }
            pages++
            val taken = page.take(1)
            taken.forEach { seen += it.type to it.ref }
            cursor = if (page.size > 1) ReplayCursor(taken.last().type, taken.last().ref) else null
        } while (cursor != null)
        pages shouldBe 4
        seen.map { it.first } shouldBe listOf(NodeType.TENANT, NodeType.GROUP, NodeType.ENTITY, NodeType.BUSINESS_UNIT)
        seen.map { it.second }.distinct().size shouldBe 4
        seen.first().second shouldBe t.prodRef
        val resumed = reading(t.production) { it.structure(null, ReplayCursor.decode("TENANT|${t.prodRef}"), 10) }
        resumed.map { it.type } shouldBe listOf(NodeType.GROUP, NodeType.ENTITY, NodeType.BUSINESS_UNIT)
    }

    @Test
    fun `INV-AC-15381 the first replayed state equals the tenant row and the shared node`() {
        val t = seed()
        val first =
            Db
                .bound(t.production, actor) { dsl ->
                    OrganisationStructureReplaySource(JooqReplayReader(dsl, unbound(t.production)), mapper)
                        .page(null, null, 10)
                }.items
                .first()
        first.entity shouldBe EntityRef(t.prodRef)
        first.aggregateKey shouldBe t.prodRef
        first.docSequence shouldBe null
        val data = mapper.readTree(first.dataJson)
        data["nodeType"].asString() shouldBe "TENANT"
        data["nodeRef"].asString() shouldBe t.prodRef
        data["changeType"].asString() shouldBe "UPDATED"
        data["node"]["environment"].asString() shouldBe "PRODUCTION"
        data["node"]["name"].asString() shouldBe "Acme Legal"
        data.has("parentRef") shouldBe false
    }

    @Test
    fun `INV-AC-15381 the three scopes against a tenant changed before, inside and after a period`() {
        val t = seed()
        val at = changedAt(t.production)
        val tenant = ReplayScope.Tenant(t.production)
        reading(t.production) { it.structure(tenant, null, 10) }.first().type shouldBe NodeType.TENANT
        val entity = ReplayScope.Entity(t.production, EntityRef("e-${t.prodRef}"))
        reading(t.production) { it.structure(entity, null, 10) }.none { it.type == NodeType.TENANT } shouldBe true

        fun period(
            from: Instant,
            to: Instant,
        ) = reading(t.production) { it.structure(ReplayScope.Period(from, to, t.production), null, 10) }
            .any { it.type == NodeType.TENANT }
        period(at, at.plusSeconds(1)) shouldBe true
        period(at.minusSeconds(3600), at) shouldBe false
        period(at.plusSeconds(1), at.plusSeconds(3600)) shouldBe false
        period(at.minusSeconds(3600), at.plusSeconds(3600)) shouldBe true
        Db.superuser {
            it.execute(
                "update tenancy.tenant set legal_name = 'Renamed' where tenant_id = ?",
                t.production.value,
            )
        }
        (changedAt(t.production) > at) shouldBe true
    }

    @Test
    fun `INV-AC-15381 Acme and Acme-sandbox each replay only their own tenant state`() {
        val t = seed()
        val own = reading(t.sandbox) { it.structure(null, null, 10) }
        own.map { it.ref } shouldBe listOf(t.sandboxRef)
        own.single().node.environment shouldBe Environment.SANDBOX
        val prod = reading(t.production) { it.structure(null, null, 10) }.filter { it.type == NodeType.TENANT }
        prod.map { it.ref } shouldBe listOf(t.prodRef)
        prod.single().node.environment shouldBe Environment.PRODUCTION
    }

    @Test
    fun `INV-AC-15381 archiving draws a new version once, repeats keep it and a PRODUCTION ref is missing`() {
        val t = seed()

        fun version(ref: String) =
            Db.superuser { it.fetchValue("select structure_version from tenancy.tenant where ref = ?", ref) as Long }
        val created = version(t.sandboxRef)
        val first = Db.bound(t.production, actor) { JooqTenantStore(it, mapper).archiveWorkspace(t.sandboxRef) }
        first.outcome shouldBe WorkspaceArchival.ARCHIVED
        val archived = first.tenant!!
        archived.structureVersion shouldBeGreaterThan created
        archived.environment shouldBe Environment.SANDBOX
        archived.retired shouldBe true
        version(t.sandboxRef) shouldBe archived.structureVersion
        val again = Db.bound(t.production, actor) { JooqTenantStore(it, mapper).archiveWorkspace(t.sandboxRef) }
        again.outcome shouldBe WorkspaceArchival.ALREADY_ARCHIVED
        again.tenant!!.structureVersion shouldBe archived.structureVersion
        version(t.sandboxRef) shouldBe archived.structureVersion
        val before = version(t.prodRef)
        val production = Db.bound(t.production, actor) { JooqTenantStore(it, mapper).archiveWorkspace(t.prodRef) }
        production.outcome shouldBe WorkspaceArchival.MISSING
        production.tenant shouldBe null
        version(t.prodRef) shouldBe before
        Db.superuser {
            it.fetchValue(
                "select retired from tenancy.tenant where ref = ?",
                t.prodRef,
            ) as Boolean
        } shouldBe
            false
    }

    /** One PostgreSQL with the service schema migrated as the installation does, and the two kinds of session. */
    private object Db {
        private const val RUNTIME_PASSWORD = "runtime-password-for-tests"
        private const val KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:17").withLabel("inv.agent", "c-inv-t-15382")

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
