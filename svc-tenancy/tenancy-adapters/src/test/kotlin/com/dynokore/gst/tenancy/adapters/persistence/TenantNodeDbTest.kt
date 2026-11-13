package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.events.OrganisationStructureChangedOutbox
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.adapters.events.TenantNodePublisher
import com.dynokore.gst.tenancy.application.structure.ChangeType
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.StructureChange
import com.dynokore.gst.tenancy.application.structure.StructureNodeState
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-16723 over PostgreSQL with forced row-level security: the TENANT node of OrganisationStructureChanged is
 * written only through `tenancy.publish_tenant_node`, for the three permitted bindings and a row that equals the stored
 * tenant row (INV-Q-16720 A).
 */
class TenantNodeDbTest {
    private val mapper = JsonMapper.builder().build()
    private val platform = TenantId.PLATFORM

    private class Created(
        val id: UUID,
        val json: JsonNode,
    )

    private fun writer(dsl: DSLContext) =
        JdbcOutboxWriter(dsl, "tenancy", "tenancy-svc", "instance-1", TenancyEventCatalogue.load(), Clock.systemUTC())

    private fun publisher(dsl: DSLContext) =
        writer(dsl).let { TenantNodePublisher(it, OrganisationStructureChangedOutbox(it, mapper), dsl, mapper) }

    private fun create(
        dsl: DSLContext,
        paired: UUID?,
        workspace: String?,
    ): Created {
        val id = UUID.randomUUID()
        val tag = id.toString().take(8)
        val answer =
            dsl
                .fetchValue(
                    "select tenancy.create_tenant(?::uuid, ?, 'Legal', 'plan', ?, ?::uuid, 'SG', ?)::text",
                    id,
                    "T-$tag",
                    "c-$tag",
                    paired,
                    workspace,
                ) as String
        return Created(id, mapper.readTree(answer))
    }

    private fun change(
        json: JsonNode,
        type: ChangeType = ChangeType.CREATED,
    ) = StructureChange(
        UUID.fromString(json.get("tenantId").asString()),
        json.get("ref").asString(),
        NodeType.TENANT,
        json.get("ref").asString(),
        type,
        null,
        null,
        StructureNodeState(
            NodeType.TENANT,
            null,
            json.get("retired").asBoolean(),
            json.get("legalName").asString(),
            null,
            json.get("structureVersion").asLong(),
            environment = Environment.valueOf(json.get("environment").asString()),
        ),
    )

    private fun row(
        dsl: DSLContext,
        change: StructureChange,
    ): ObjectNode = publisher(dsl).stagedRow(change)

    private fun outboxCount(id: String): Int =
        Db.superuser { it.fetchValue("select count(*) from tenancy.outbox where id = ?", id) as Long }.toInt()

    private fun tenantCount(id: UUID): Int =
        Db.superuser { it.fetchValue("select count(*) from tenancy.tenant where tenant_id = ?", id) as Long }.toInt()

    /** A committed production tenant P and its committed workspace S. */
    private class Seed(
        val p: Created,
        val s: Created,
    )

    private fun seed(): Seed {
        val p = Db.bound(platform) { create(it, null, null) }
        val s = Db.bound(TenantId(p.id)) { create(it, p.id, "sandbox") }
        return Seed(p, s)
    }

    private fun callFunction(
        c: Connection,
        row: JsonNode,
    ) = c.prepareStatement("select tenancy.publish_tenant_node(?::jsonb)").use {
        it.setString(1, row.toString())
        it.execute()
    }

    /** Calls the function under [binding] with [row]: it must raise [state] and write nothing. */
    private fun refused(
        binding: TenantId,
        row: JsonNode,
        state: String,
    ) {
        val thrown =
            runCatching { Db.bound(binding) { dsl -> dsl.connection { callFunction(it, row) } } }
                .exceptionOrNull()
        val sql = generateSequence(thrown) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        (sql != null) shouldBe true
        sql!!.sqlState shouldBe state
        outboxCount(row.get("id").asString()) shouldBe 0
    }

    @Test
    fun `INV-AC-16723 platform binding publishes the new production tenant's CREATED row`() {
        val created =
            Db.bound(platform) { dsl ->
                val c = create(dsl, null, null)
                val change = change(c.json)
                publisher(dsl).publish(change)
                c
            }
        val rows =
            Db.superuser {
                it.fetch("select id, tenant_id, type, topic from tenancy.outbox where tenant_id = ?", created.id)
            }
        rows.size shouldBe 1
        rows[0].get("tenant_id", UUID::class.java) shouldBe created.id
        rows[0].get("type", String::class.java) shouldBe "com.dynokore.gst.tenancy.OrganisationStructureChanged.v1"
        rows[0].get("topic", String::class.java) shouldBe "gst.tenancy.organisation-structure-changed.v1"
        outboxCount(rows[0].get("id", String::class.java)) shouldBe 1
    }

    @Test
    fun `INV-AC-16723 the production tenant publishes its new sandbox and the RETIRED row after archive`() {
        val p = Db.bound(platform) { create(it, null, null) }
        val s =
            Db.bound(TenantId(p.id)) { dsl ->
                val c = create(dsl, p.id, "sandbox")
                publisher(dsl).publish(change(c.json))
                c
            }
        Db.superuser { it.fetchValue("select count(*) from tenancy.outbox where tenant_id = ?", s.id) } shouldBe 1L
        Db.bound(TenantId(p.id)) { dsl ->
            val archived =
                mapper.readTree(
                    dsl.fetchValue("select tenancy.archive_workspace(?)::text", s.json.get("ref").asString()) as String,
                )
            archived.get("retired").asBoolean() shouldBe true
            publisher(dsl).publish(change(archived, ChangeType.RETIRED))
        }
        val types =
            Db.superuser {
                it.fetchValues("select data->>'changeType' from tenancy.outbox where tenant_id = ?", s.id)
            }
        types.toSet() shouldBe setOf("CREATED", "RETIRED")
        types.size shouldBe 2
    }

    @Test
    fun `INV-AC-16723 the tenant itself publishes its own node`() {
        val seed = seed()
        Db.bound(TenantId(seed.s.id)) { dsl -> publisher(dsl).publish(change(seed.s.json)) }
        Db.superuser { it.fetchValue("select count(*) from tenancy.outbox where tenant_id = ?", seed.s.id) } shouldBe 1L
    }

    @Test
    fun `INV-AC-16723 refused bindings write nothing`() {
        val seed = seed()
        val other = seed()
        val sandboxRow = Db.bound(TenantId(seed.p.id)) { row(it, change(seed.s.json)) }
        val productionRow = Db.bound(TenantId(seed.p.id)) { row(it, change(seed.p.json)) }
        refused(platform, sandboxRow, "42501")
        refused(TenantId(other.p.id), sandboxRow, "42501")
        refused(TenantId(other.s.id), sandboxRow, "42501")
        refused(TenantId(seed.s.id), productionRow, "42501")
        refused(TenantId(other.p.id), productionRow, "42501")
    }

    /** The unaltered row of the workspace, each time with one member altered (INV-AC-16723 d). */
    private fun alteredRows(
        base: ObjectNode,
        foreignRef: String,
        version: Long,
    ): Map<String, JsonNode> {
        fun mutated(edit: (ObjectNode) -> Unit): JsonNode = base.deepCopy().also { edit(it) }

        fun data(n: ObjectNode) = n.get("data") as ObjectNode

        fun node(n: ObjectNode) = data(n).get("node") as ObjectNode
        return mapOf(
            "type v2" to mutated { it.put("type", "com.dynokore.gst.tenancy.OrganisationStructureChanged.v2") },
            "topic" to mutated { it.put("topic", "gst.tenancy.other.v1") },
            "nodeRef" to mutated { data(it).put("nodeRef", "T-other") },
            "structureVersion" to mutated { node(it).put("structureVersion", version + 1) },
            "environment" to mutated { node(it).put("environment", "PRODUCTION") },
            "changeType RETIRED" to mutated { data(it).put("changeType", "RETIRED") },
            "unknown tenant" to mutated { it.put("tenant_id", UUID.randomUUID().toString()) },
            "foreign entity_ref" to mutated { it.put("entity_ref", foreignRef) },
            "extra member" to mutated { it.put("extra", "x") },
            "non-v7 id" to mutated { it.put("id", UUID.randomUUID().toString()) },
            "parentRef" to mutated { data(it).put("parentRef", "G-1") },
        )
    }

    @Test
    fun `INV-AC-16723 a row that differs from the stored tenant row is refused`() {
        val seed = seed()
        val base = Db.bound(TenantId(seed.p.id)) { row(it, change(seed.s.json)) }
        val binding = TenantId(seed.p.id)

        val cases =
            alteredRows(
                base,
                seed.p.json
                    .get("ref")
                    .asString(),
                seed.s.json
                    .get("structureVersion")
                    .asLong(),
            )
        cases.forEach { (name, bad) ->
            runCatching { refused(binding, bad, "22023") }.onFailure { throw AssertionError("case '$name': $it", it) }
        }
        outboxCount(base.get("id").asString()) shouldBe 0
        // The unaltered row is accepted by the same binding: the refusals above are the alterations only.
        Db.bound(binding) { dsl -> dsl.connection { callFunction(it, base) } }
        outboxCount(base.get("id").asString()) shouldBe 1
    }

    @Test
    fun `INV-AC-16723 a rollback after the call leaves no outbox row and no tenant row`() {
        var tenant: UUID? = null
        var rowId = ""
        Db.bound(platform, commit = false) { dsl ->
            val c = create(dsl, null, null)
            tenant = c.id
            val r = row(dsl, change(c.json))
            rowId = r.get("id").asString()
            dsl.connection { callFunction(it, r) }
        }
        tenantCount(tenant!!) shouldBe 0
        outboxCount(rowId) shouldBe 0
    }

    @Test
    fun `INV-AC-16723 a direct outbox insert by the runtime login is refused and the sys policy admits one type`() {
        val seed = seed()
        val base = Db.bound(TenantId(seed.p.id)) { row(it, change(seed.s.json)) }
        val insert =
            "insert into tenancy.outbox (id, topic, type, source, subject, event_time, tenant_id, entity_ref, " +
                "instance_id, correlation_id, causation_id, data) " +
                "values (?, ?, ?, '/gst/tenancy', 's', now(), ?, 'e', " +
                "'i', 'c', 'c', '{}'::jsonb)"

        fun direct(
            c: Connection,
            type: String,
        ) = c.prepareStatement(insert).use {
            it.setString(1, UUID.randomUUID().toString())
            it.setString(2, base.get("topic").asString())
            it.setString(3, type)
            it.setObject(4, seed.s.id)
            it.executeUpdate()
        }
        val typeOk = base.get("type").asString()
        listOf(TenantId(seed.p.id), platform).forEach { binding ->
            val thrown =
                runCatching { Db.bound(binding) { dsl -> dsl.connection { direct(it, typeOk) } } }.exceptionOrNull()
            (thrown != null) shouldBe true
            generateSequence(thrown) { it.cause }.filterIsInstance<SQLException>().first().sqlState shouldBe "42501"
        }
        Db.asSys { direct(it, typeOk) } shouldBe 1
        val bad =
            runCatching { Db.asSys { direct(it, typeOk.replace(".v1", ".v2")) } }.exceptionOrNull()
        (bad as? SQLException)?.sqlState shouldBe "42501"
    }

    @Test
    fun `INV-AC-16723 the function ACL lists only the sys and runtime roles`() {
        val grantees =
            Db.superuser {
                it.fetchValues(
                    "select a.grantee::regrole::text from pg_proc p, aclexplode(p.proacl) a " +
                        "where p.oid = 'tenancy.publish_tenant_node(jsonb)'::regprocedure",
                )
            }
        grantees.map { it.toString() }.toSet() shouldBe setOf("tenancy_sys", "tenancy_runtime")
    }

    /** One PostgreSQL with the service schema migrated as the installation does, and the two kinds of session. */
    private object Db {
        private const val RUNTIME_PASSWORD = "runtime-password-for-tests"
        private const val KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        private const val PG_START_TIMEOUT_MINUTES = 3L
        private const val PG_START_ATTEMPTS = 3
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3")
                .withStartupTimeout(Duration.ofMinutes(PG_START_TIMEOUT_MINUTES))
                .withStartupAttempts(PG_START_ATTEMPTS)
                .withCommand(
                    "postgres",
                    "-c",
                    "fsync=off",
                    "-c",
                    "synchronous_commit=off",
                    "-c",
                    "full_page_writes=off",
                ).withLabel("inv.agent", "c-inv-t-16725")

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

        /** A session of the sys role (the function owner), as a superuser connection that switched role. */
        fun <T> asSys(block: (Connection) -> T): T =
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
                c.createStatement().use { it.execute("SET ROLE tenancy_sys") }
                block(c)
            }

        /** A runtime-role transaction bound to [tenant] as TransactionRunnerImpl does, committed or rolled back. */
        fun <T> bound(
            tenant: TenantId,
            commit: Boolean = true,
            block: (DSLContext) -> T,
        ): T =
            DriverManager.getConnection(container.jdbcUrl, "tenancy_runtime", RUNTIME_PASSWORD).use { c ->
                c.autoCommit = false
                val expiry = BindingSignature.expiryFor(Instant.now(), Duration.ofSeconds(30))
                val actor = "svc:tenancy-svc"
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
