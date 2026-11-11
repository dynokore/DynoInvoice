package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT_GROUP
import com.dynokore.gst.tenancy.application.rendition.RenditionAttestation
import com.dynokore.gst.tenancy.application.rendition.RenditionAttestationRecord
import com.dynokore.gst.tenancy.application.rendition.RenditionRecordKind
import com.dynokore.gst.tenancy.domain.AttestationState
import com.dynokore.gst.tenancy.domain.Renderer
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-20308 over PostgreSQL with forced row-level security: every attestation and state change stays in the
 * append-only history (INV-REQ-1036 behavior 1).
 */
class JooqRenditionHistoryTest {
    private val d1 = Instant.parse("2026-11-01T00:00:00Z")
    private val d2 = Instant.parse("2026-11-05T00:00:00Z")
    private val d3 = Instant.parse("2026-11-09T00:00:00Z")
    private val docClass = "TAX_INVOICE"

    private class Seed(
        val tenant: TenantId,
        val ref: String,
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

    private fun <T> inTenant(
        seed: Seed,
        at: Instant,
        block: (JooqRenditionStore) -> T,
    ): T = Db.bound(seed.tenant, "svc:tenancy-svc") { block(JooqRenditionStore(it, context(seed.tenant)) { at }) }

    private fun seed(): Seed {
        val tag = UUID.randomUUID().toString().take(8)
        val tenant = TenantId(UUID.randomUUID())
        val ref = "e-$tag"
        Db.superuser { dsl ->
            fun version() = dsl.fetchValue("select nextval('tenancy.structure_version_seq')") as Long
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            dsl
                .insertInto(TENANT)
                .set(TENANT.TENANT_ID, tenant.value)
                .set(TENANT.REF, "T-$tag")
                .set(TENANT.LEGAL_NAME, "Legal")
                .set(TENANT.PLAN_REF, "plan")
                .set(TENANT.CONTRACT_REFERENCE, "c-$tag")
                .set(TENANT.STRUCTURE_VERSION, version())
                .set(TENANT.CREATED_AT, now)
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
                .set(TENANT_GROUP.CREATED_AT, now)
                .execute()
            dsl
                .insertInto(ENTITY)
                .set(ENTITY.ID, UUID.randomUUID())
                .set(ENTITY.TENANT_ID, tenant.value)
                .set(ENTITY.REF, ref)
                .set(ENTITY.GROUP_ID, group)
                .set(ENTITY.LEGAL_NAME, "Entity")
                .set(ENTITY.CONSTITUTION, "COMPANY")
                .set(ENTITY.CODE, "E$tag".uppercase())
                .set(ENTITY.LIFECYCLE_STATE, "DRAFT")
                .set(ENTITY.STRUCTURE_VERSION, version())
                .set(ENTITY.CREATED_AT, now)
                .execute()
        }
        return Seed(tenant, ref)
    }

    private fun attest(
        seed: Seed,
        user: String,
        at: Instant,
        renderer: Renderer = Renderer.SELF,
        documentClass: String = docClass,
    ) = inTenant(seed, at) {
        it.put(RenditionAttestation(seed.ref, documentClass, renderer, user, AttestationState.CURRENT))
    }

    private fun history(
        seed: Seed,
        documentClass: String = docClass,
    ): List<RenditionAttestationRecord> = inTenant(seed, d3) { it.list(seed.ref, documentClass) }

    @Test
    fun `INV-AC-20308 a second attestation keeps the first with its attester and date`() {
        val s = seed()
        attest(s, "U1", d1, Renderer.SELF)
        attest(s, "U2", d2, Renderer.AGENT)
        val rows = history(s)
        rows.map { it.attestedBy } shouldBe listOf("U1", "U2")
        rows.map { it.attestedAt } shouldBe listOf(d1, d2)
        rows.map { it.renderer } shouldBe listOf(Renderer.SELF, Renderer.AGENT)
        rows.map { it.kind } shouldBe listOf(RenditionRecordKind.ATTESTED, RenditionRecordKind.ATTESTED)
        (rows[0].seq < rows[1].seq) shouldBe true
        val current = inTenant(s, d3) { it.find(s.ref, docClass) }
        current?.attestedBy shouldBe "U2"
        current?.renderer shouldBe Renderer.AGENT
    }

    @Test
    fun `INV-AC-20308 a state change adds one STATE_CHANGED row and rewrites none`() {
        val s = seed()
        attest(s, "U1", d1)
        val before = history(s)
        inTenant(s, d3) { it.setStateForEntity(s.ref, AttestationState.REQUESTED_AGAIN) } shouldBe 1
        val after = history(s)
        after.size shouldBe 2
        after.first() shouldBe before.first()
        after[1].kind shouldBe RenditionRecordKind.STATE_CHANGED
        after[1].state shouldBe AttestationState.REQUESTED_AGAIN
        after[1].attestedBy shouldBe "U1"
        after[1].attestedAt shouldBe d1
        after[1].renderer shouldBe Renderer.SELF
    }

    @Test
    fun `INV-AC-20308 setting the state it already has adds nothing`() {
        val s = seed()
        attest(s, "U1", d1)
        inTenant(s, d3) { it.setStateForEntity(s.ref, AttestationState.CURRENT) }
        history(s).size shouldBe 1
        inTenant(s, d3) { it.setStateForEntity(s.ref, AttestationState.REQUESTED_AGAIN) }
        inTenant(s, d3) { it.setStateForEntity(s.ref, AttestationState.REQUESTED_AGAIN) }
        history(s).size shouldBe 2
    }

    @Test
    fun `INV-AC-20308 UPDATE DELETE and TRUNCATE of a history row as tenancy_runtime are refused`() {
        val s = seed()
        attest(s, "U1", d1)
        listOf(
            "update tenancy.rendition_attestation_history set attested_by = 'X'",
            "delete from tenancy.rendition_attestation_history",
            "truncate tenancy.rendition_attestation_history",
        ).forEach { sql ->
            assertThrows(DataAccessException::class.java) { Db.bound(s.tenant, "svc:tenancy-svc") { it.execute(sql) } }
        }
        history(s).map { it.attestedBy } shouldBe listOf("U1")
    }

    @Test
    fun `INV-AC-20308 the trigger refuses UPDATE DELETE and TRUNCATE even for the owner`() {
        val s = seed()
        attest(s, "U1", d1)
        listOf(
            "update tenancy.rendition_attestation_history set attested_by = 'X'",
            "delete from tenancy.rendition_attestation_history",
            "truncate tenancy.rendition_attestation_history",
        ).forEach { sql ->
            val failure = assertThrows(DataAccessException::class.java) { Db.superuser { it.execute(sql) } }
            (failure.message.orEmpty().contains("append-only")) shouldBe true
        }
        history(s).size shouldBe 1
    }

    @Test
    fun `INV-AC-20308 the history of another class and of another tenant is not returned`() {
        val s = seed()
        val other = seed()
        attest(s, "U1", d1)
        attest(s, "U2", d2, documentClass = "CREDIT_NOTE")
        attest(other, "U9", d2)
        history(s).map { it.attestedBy } shouldBe listOf("U1")
        history(s, "CREDIT_NOTE").map { it.attestedBy } shouldBe listOf("U2")
        history(other).map { it.attestedBy } shouldBe listOf("U9")
        // the same entity reference read under another tenant's binding sees nothing
        inTenant(Seed(other.tenant, s.ref), d3) { it.list(s.ref, docClass) }.shouldBeEmpty()
    }

    private object Db {
        private const val RUNTIME_PASSWORD = "runtime-password-for-tests"
        private const val KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:17")
                .withStartupTimeout(Duration.ofMinutes(3))
                .withStartupAttempts(3)
                .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
                .withLabel("inv.agent", "c-inv-t-20312")

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
