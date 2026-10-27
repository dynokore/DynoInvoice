package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.SHORT_CODE
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.TENANT
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-2779, the handler half: `ShortCodeUsed` sets `used_in_aggregate_number` once, on the tenant-bound
 * [DSLContext] of a PostgreSQL with forced row-level security and the signed binding of tenancy-kernel.
 */
class ShortCodeUsedHandlerTest {
    private val actor = "svc:tenancy-svc"

    private fun tenant(): TenantId = TenantId(UUID.randomUUID())

    /** A tenant with the given (code, kind) rows, written as the superuser. */
    private fun seed(
        tenant: TenantId,
        vararg rows: Pair<String, String>,
    ) = Db.superuser { dsl ->
        dsl
            .insertInto(TENANT)
            .set(TENANT.TENANT_ID, tenant.value)
            .set(TENANT.REF, "t-${tenant.value}")
            .set(TENANT.LEGAL_NAME, "Tenant")
            .set(TENANT.PLAN_REF, "plan")
            .set(TENANT.CONTRACT_REFERENCE, "c-${tenant.value}")
            .set(TENANT.STRUCTURE_VERSION, 1L)
            .set(TENANT.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
        rows.forEach { (code, kind) ->
            dsl
                .insertInto(SHORT_CODE)
                .set(SHORT_CODE.TENANT_ID, tenant.value)
                .set(SHORT_CODE.CODE, code)
                .set(SHORT_CODE.NODE_KIND, kind)
                .set(SHORT_CODE.NODE_ID, UUID.randomUUID())
                .execute()
        }
    }

    private fun flag(
        tenant: TenantId,
        code: String,
    ): Boolean =
        Db.superuser { dsl ->
            dsl
                .select(SHORT_CODE.USED_IN_AGGREGATE_NUMBER)
                .from(SHORT_CODE)
                .where(SHORT_CODE.TENANT_ID.eq(tenant.value).and(SHORT_CODE.CODE.eq(code)))
                .fetchSingle(SHORT_CODE.USED_IN_AGGREGATE_NUMBER)!!
        }

    private fun handle(
        tenant: TenantId,
        data: String,
    ): HandleOutcome = Db.bound(tenant, actor) { dsl -> ShortCodeUsedHandler(dsl).handle(event(tenant, data)) }

    private fun used(
        code: String,
        kind: String = "ENTITY",
    ) = """{"code":"$code","nodeKind":"$kind","nodeRef":"node-1","aggregateId":"agg-1","documentNumber":"AGG-1"}"""

    private fun event(
        tenant: TenantId,
        data: String,
    ) = Envelope(
        id = EventId("ev-${UUID.randomUUID()}"),
        source = "/gst/aggregation-svc",
        type = TenancyEventCatalogue.SHORT_CODE_USED_TYPE,
        subject = "agg-1",
        time = Instant.parse("2026-10-27T10:00:00Z"),
        tenant = tenant,
        entity = EventEntityRef("ENT-1"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "aggregation-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    @Test
    fun `INV-AC-2779 an event for an unused entity code sets that row's flag and no other row's`() {
        val tenant = tenant()
        seed(tenant, "ACME" to "ENTITY", "OTHER" to "ENTITY")
        handle(tenant, used("ACME")) shouldBe HandleOutcome.APPLIED
        flag(tenant, "ACME") shouldBe true
        flag(tenant, "OTHER") shouldBe false
    }

    @Test
    fun `INV-AC-2779 the same event delivered twice leaves the flag set and the second call is ignored`() {
        val tenant = tenant()
        seed(tenant, "ACME" to "ENTITY")
        handle(tenant, used("ACME")) shouldBe HandleOutcome.APPLIED
        handle(tenant, used("ACME")) shouldBe HandleOutcome.IGNORED
        flag(tenant, "ACME") shouldBe true
    }

    @Test
    fun `INV-AC-2779 an event for a business unit code sets only the row of that kind`() {
        val tenant = tenant()
        seed(tenant, "ACME" to "ENTITY", "OUTLET" to "BUSINESS_UNIT")
        handle(tenant, used("OUTLET", "BUSINESS_UNIT")) shouldBe HandleOutcome.APPLIED
        flag(tenant, "OUTLET") shouldBe true
        flag(tenant, "ACME") shouldBe false
    }

    @Test
    fun `INV-AC-2779 an event whose code exists under the other kind changes nothing`() {
        val tenant = tenant()
        seed(tenant, "ACME" to "ENTITY")
        handle(tenant, used("ACME", "BUSINESS_UNIT")) shouldBe HandleOutcome.IGNORED
        flag(tenant, "ACME") shouldBe false
    }

    @Test
    fun `INV-AC-2779 an event of another tenant with the same code changes nothing`() {
        val owner = tenant()
        val other = tenant()
        seed(owner, "ACME" to "ENTITY")
        seed(other)
        // the superuser session sees both tenants' rows, so only the handler's tenant condition keeps the owner's row
        val outcome = Db.superuser { dsl -> ShortCodeUsedHandler(dsl).handle(event(other, used("ACME"))) }
        outcome shouldBe HandleOutcome.IGNORED
        flag(owner, "ACME") shouldBe false
    }

    @Test
    fun `INV-AC-2779 an unknown code logs one warning with its identifiers and changes nothing`() {
        val tenant = tenant()
        seed(tenant, "ACME" to "ENTITY")
        val captured = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(captured, true))
        val outcome =
            try {
                handle(tenant, used("GONE"))
            } finally {
                System.setErr(original)
            }
        outcome shouldBe HandleOutcome.IGNORED
        val lines = captured.toString().lines().filter { "WARN" in it && "ShortCodeUsedHandler" in it }
        lines.size shouldBe 1
        lines.single() shouldContain "tenant=${tenant.value}"
        lines.single() shouldContain "code=GONE"
        lines.single() shouldContain "nodeKind=ENTITY"
        lines.single() shouldContain "aggregateId=agg-1"
        flag(tenant, "ACME") shouldBe false
    }

    @Test
    fun `INV-AC-2779 a malformed payload is a contract violation`() {
        val tenant = tenant()
        seed(tenant, "ACME" to "ENTITY")
        listOf(
            used("ab1"),
            used("ACME").replace(""","aggregateId":"agg-1"""", ""),
            used("ACME", "TENANT"),
            used("ACME").replace("\"agg-1\"", "7"),
            "not json",
        ).forEach { data -> shouldThrow<ContractViolation> { handle(tenant, data) } }
        flag(tenant, "ACME") shouldBe false
    }

    /** One PostgreSQL with the service schema migrated as the installation does, and the two kinds of session. */
    private object Db {
        private const val RUNTIME_PASSWORD = "runtime-password-for-tests"
        private const val KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:17").withLabel("inv.agent", "c-inv-t-11279")

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

        /** A runtime-role transaction bound to [tenant] and [actor] as TransactionRunnerImpl binds it, then committed. */
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
