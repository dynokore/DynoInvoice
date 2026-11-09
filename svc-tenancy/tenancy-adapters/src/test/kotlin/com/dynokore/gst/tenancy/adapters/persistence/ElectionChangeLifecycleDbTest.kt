package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.application.election.ElectionChangeStore
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The lifecycle of an election change over PostgreSQL with forced row-level security (INV-AC-13527, INV-AC-1511):
 * withdraw and decline close a PENDING change once, the list holds the PENDING changes of one entity and tenant in
 * order, a partial unique index allows one PENDING change per entity and variation point, and two threads submitting
 * at once store exactly one.
 */
class ElectionChangeLifecycleDbTest {
    private val day = LocalDate.of(2026, 12, 1)

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
        tenant: TenantId,
        block: (ElectionChangeStore) -> T,
    ): T = Db.bound(tenant, "svc:tenancy-svc") { block(JooqElectionChangeStore(it, context(tenant))) }

    private fun ElectionChangeStore.ask(
        entity: String,
        kind: ElectionKind = ElectionKind.PURCHASE_DATE_BASIS,
        value: String = "POSTING_DATE",
    ) = request(entity, kind, value, null, day, "identity:USR-001")

    private fun states(entity: String) =
        Db.superuser {
            it
                .fetch("select state from tenancy.election_change where entity_ref = ? order by requested_at", entity)
                .map { r -> r.get(0, String::class.java) }
        }

    @Test
    fun `INV-AC-13527 a second PENDING change for one entity and variation point is stored as nothing`() {
        val t = TenantId(UUID.randomUUID())
        val e = "e-${UUID.randomUUID()}"
        inTenant(t) { it.ask(e).shouldNotBeNull() }
        inTenant(t) { it.ask(e, value = "INVOICE_DATE").shouldBeNull() }
        states(e) shouldContainExactly listOf("PENDING")
        // another variation point and another entity are stored
        inTenant(t) { it.ask(e, ElectionKind.APPORTIONMENT_MODE, "ENTIRE_INVOICE").shouldNotBeNull() }
        inTenant(t) { it.ask("other-$e").shouldNotBeNull() }
    }

    @Test
    fun `INV-AC-13527 an insert of a second PENDING row by SQL fails on the unique index`() {
        val t = TenantId(UUID.randomUUID())
        val e = "e-${UUID.randomUUID()}"
        inTenant(t) { it.ask(e).shouldNotBeNull() }
        val failure =
            assertThrows(DataAccessException::class.java) {
                Db.superuser {
                    it.execute(
                        "insert into tenancy.election_change (tenant_id, change_ref, entity_ref, variation_point, " +
                            "requested_value, effective_from, requested_by, requested_at, state) " +
                            "values (?, 'CHG-SQL', ?, 'VP-22', 'INVOICE_DATE', date '2026-12-01', 'u', now(), " +
                            "'PENDING')",
                        t.value,
                        e,
                    )
                }
            }
        (failure.message.orEmpty().contains("election_change_one_pending")) shouldBe true
    }

    @Test
    fun `INV-AC-13527 two threads submitting a change at once store exactly one PENDING row`() {
        val t = TenantId(UUID.randomUUID())
        val e = "e-${UUID.randomUUID()}"
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                (1..2)
                    .map {
                        pool.submit<Boolean> {
                            gate.await()
                            inTenant(t) { store -> store.ask(e) != null }
                        }
                    }.also { gate.countDown() }
                    .map { it.get() }
            results.count { it } shouldBe 1
            results.count { !it } shouldBe 1
        } finally {
            pool.shutdownNow()
        }
        states(e) shouldContainExactly listOf("PENDING")
    }

    @Test
    fun `INV-AC-1511 withdraw and decline close the change once with the closer and the time`() {
        val t = TenantId(UUID.randomUUID())
        val e = "e-${UUID.randomUUID()}"
        val first = inTenant(t) { it.ask(e) }.shouldNotBeNull()
        val closed = inTenant(t) { it.withdraw(e, first.changeRef, "identity:USR-001") }.shouldNotBeNull()
        closed.closedBy shouldBe "identity:USR-001"
        inTenant(t) { it.withdraw(e, first.changeRef, "identity:USR-001") }.shouldBeNull()
        inTenant(t) { it.decline(e, first.changeRef, "identity:USR-002") }.shouldBeNull()
        val second = inTenant(t) { it.ask(e) }.shouldNotBeNull()
        inTenant(t) { it.decline(e, second.changeRef, "identity:USR-002") }.shouldNotBeNull().closedBy shouldBe
            "identity:USR-002"
        states(e) shouldContainExactly listOf("WITHDRAWN", "DECLINED")
        Db.superuser {
            it
                .fetch(
                    "select closed_by, closed_at is not null from tenancy.election_change " +
                        "where entity_ref = ? order by requested_at",
                    e,
                ).map { r -> r.get(0, String::class.java) to r.get(1, Boolean::class.java) }
        } shouldContainExactly listOf("identity:USR-001" to true, "identity:USR-002" to true)
    }

    @Test
    fun `INV-AC-1511 a change of another tenant or entity cannot be closed or seen`() {
        val t = TenantId(UUID.randomUUID())
        val other = TenantId(UUID.randomUUID())
        val e = "e-${UUID.randomUUID()}"
        val change = inTenant(t) { it.ask(e) }.shouldNotBeNull()
        inTenant(other) { it.withdraw(e, change.changeRef, "identity:USR-001") }.shouldBeNull()
        inTenant(other) { it.decline(e, change.changeRef, "identity:USR-002") }.shouldBeNull()
        inTenant(other) { it.list(e, 10, null).items }.shouldContainExactly(emptyList())
        inTenant(t) { it.withdraw("other-$e", change.changeRef, "identity:USR-001") }.shouldBeNull()
        states(e) shouldContainExactly listOf("PENDING")
    }

    @Test
    fun `INV-AC-13527 the closure check refuses a closed state without closer and a closer on an open one`() {
        val t = TenantId(UUID.randomUUID())
        val e = "e-${UUID.randomUUID()}"
        val change = inTenant(t) { it.ask(e) }.shouldNotBeNull()
        assertThrows(DataAccessException::class.java) {
            Db.superuser {
                it.execute(
                    "update tenancy.election_change set state = 'WITHDRAWN' where change_ref = ?",
                    change.changeRef,
                )
            }
        }
        assertThrows(DataAccessException::class.java) {
            Db.superuser {
                it.execute(
                    "update tenancy.election_change set closed_by = 'u', closed_at = now() where change_ref = ?",
                    change.changeRef,
                )
            }
        }
        assertThrows(DataAccessException::class.java) {
            Db.superuser {
                it.execute(
                    "update tenancy.election_change set state = 'EXPIRED' where change_ref = ?",
                    change.changeRef,
                )
            }
        }
    }

    @Test
    fun `INV-AC-13527 the list holds PENDING changes only, in order, with a next-page token`() {
        val t = TenantId(UUID.randomUUID())
        val other = TenantId(UUID.randomUUID())
        val e = "e-${UUID.randomUUID()}"
        val a = inTenant(t) { it.ask(e) }.shouldNotBeNull()
        inTenant(t) { it.withdraw(e, a.changeRef, "identity:USR-001") }
        val b = inTenant(t) { it.ask(e) }.shouldNotBeNull()
        val c = inTenant(t) { it.ask(e, ElectionKind.APPORTIONMENT_MODE, "ENTIRE_INVOICE") }.shouldNotBeNull()
        val d =
            inTenant(t) {
                it.request(e, ElectionKind.EXCHANGE_RATE_SOURCE, "MAS", "DAILY", day, "identity:USR-001")
            }.shouldNotBeNull()
        inTenant(t) { it.ask("other-$e") }
        inTenant(other) { it.ask(e) }
        val first = inTenant(t) { it.list(e, 2, null) }
        first.items.map { it.changeRef } shouldContainExactly listOf(b.changeRef, c.changeRef)
        first.next.shouldNotBeNull()
        val second = inTenant(t) { it.list(e, 2, first.next) }
        second.items.map { it.changeRef } shouldContainExactly listOf(d.changeRef)
        second.next.shouldBeNull()
        assertThrows(IllegalArgumentException::class.java) { inTenant(t) { it.list(e, 2, "garbage") } }
        assertThrows(IllegalArgumentException::class.java) { inTenant(t) { it.list(e, 2, "bm90fGEK") } }
    }

    private object Db {
        private const val RUNTIME_PASSWORD = "runtime-password-for-tests"
        private const val KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        private val container: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:17")
                .withStartupTimeout(Duration.ofMinutes(PG_START_TIMEOUT_MINUTES))
                .withStartupAttempts(PG_START_ATTEMPTS)
                .withCommand(*pgFastCommand)
                .withLabel("inv.agent", "c-inv-t-13532")

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
