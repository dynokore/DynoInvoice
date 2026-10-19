package com.dynokore.platform.serviceruntime.persistence

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.dynokore.platform.outboxandqueue.SigningFixture
import com.dynokore.platform.serviceruntime.datasource.tpl18Service
import com.dynokore.platform.serviceruntime.datasource.withMountedLogin
import com.dynokore.platform.serviceruntime.persistence.jooq.Pers
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.pojos.Account
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.pojos.AuditEntry
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.pojos.Audited
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.pojos.Counter
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.pojos.DeclaredLog
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.pojos.HalfAudited
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.pojos.LedgerEntry
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.records.AccountRecord
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.records.AuditEntryRecord
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.records.AuditedRecord
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.records.CounterRecord
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.records.DeclaredLogRecord
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.records.HalfAuditedRecord
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.records.LedgerEntryRecord
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.references.ACCOUNT
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.references.AUDITED
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.references.AUDIT_ENTRY
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.references.COUNTER
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.references.DECLARED_LOG
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.references.HALF_AUDITED
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.references.LEDGER_ENTRY
import com.dynokore.platform.serviceruntime.testing.startPostgres
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancyjooq.TenantPredicateViolation
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * INV-T-4224 on PostgreSQL (INV-ADR-3968 items 4a and 4c, INV-REQ-854 b8): the jOOQ convenience layer through the full
 * TPL-18 composition with a two-tenant fixture. [rls] says whether the fixture tables enable and force row-level
 * security; with it off the framework's tenant predicate is the only thing separating the tenants, and every result must
 * be the same.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PersistenceLayerITBase(
    private val image: String,
    private val rls: Boolean,
) {
    private val a = TenantId(UUID(1L, 1L))
    private val b = TenantId(UUID(2L, 2L))
    private lateinit var db: PostgreSQLContainer<*>
    private lateinit var mountRoot: Path

    @Configuration(proxyBeanMethods = false)
    class ServiceSchema {
        @Bean
        fun persSchema(): Pers = Pers.PERS
    }

    /** What a test sees: the context, the layer, its store and the statements the layer's DSLContext ran. */
    inner class Fixture(
        val context: AssertableApplicationContext,
        private val clock: Clock,
    ) {
        val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        private val runner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val guards: PersistenceGuards = context.getBean(PersistenceGuards::class.java)

        // jOOQ's own statement log (LoggerListener, DEBUG), read as the statements the layer's DSLContext executes
        private val appender =
            object : AppenderBase<ILoggingEvent>() {
                override fun append(event: ILoggingEvent) {
                    STATEMENT.matchEntire(event.formattedMessage)?.let { statements += it.groupValues[1] }
                    // jOOQ logs these only after the server ran the statement (the statement line is logged at render time)
                    if (EXECUTED.containsMatchIn(event.formattedMessage)) executed += event.formattedMessage
                }
            }
        private val logger = LoggerFactory.getLogger("org.jooq.tools.LoggerListener") as Logger
        private val level = logger.level

        init {
            appender.start()
            logger.level = Level.DEBUG
            logger.addAppender(appender)
        }

        fun close() {
            logger.detachAppender(appender)
            logger.level = level
            appender.stop()
        }

        val layer = PersistenceLayer(dsl, guards)
        val accounts = layer.aggregates<AccountRecord, Account, UUID>(ACCOUNT, Account::class.java)

        fun <T> asTenant(
            tenant: TenantId,
            actor: String = "user:alice",
            block: () -> T,
        ): T = tenants.bind(tenant, actor) { runner.inTransaction { block() } }

        fun <T> inBoundNoTransaction(block: () -> T): T = tenants.bind(a, "user:alice") { block() }

        fun rowCount(
            tenant: TenantId,
            id: UUID,
        ): Int = asTenant(tenant) { dsl.fetchCount(ACCOUNT, ACCOUNT.ID.eq(id)) }

        val now: OffsetDateTime get() = OffsetDateTime.now(clock)
    }

    @BeforeAll
    fun start() {
        val migration =
            checkNotNull(javaClass.getResource("/db/migration/V1__persistence_fixture.sql")) { "fixture migration" }
                .readText()
        val tables =
            listOf("account", "audit_entry", "declared_log", "ledger_entry", "audited", "half_audited", "counter")
        val init =
            buildString {
                append("create role pers_rt login password 'runtime-pw';\n")
                append(
                    "create schema pers;\nset search_path to pers;\n",
                ).append(migration).append("\nreset search_path;\n")
                append("grant usage on schema pers to pers_rt;\n")
                append("create role pers_sys nologin;\n")
                append(SigningFixture.provisioning("pers", "pers_sys", "pers_rt")).append('\n')
                tables.forEach { table ->
                    append("grant select, insert, update, delete on pers.$table to pers_rt;\n")
                    if (rls) {
                        append("alter table pers.$table enable row level security;\n")
                        append("alter table pers.$table force row level security;\n")
                        append(
                            "create policy tenant_isolation on pers.$table " +
                                "using (tenant_id = (select pers.bound_tenant())) " +
                                "with check (tenant_id = (select pers.bound_tenant()));\n",
                        )
                    }
                }
            }
        db = startPostgres(image, init)
        mountRoot = Files.createTempDirectory("pers-mount")
    }

    @AfterAll
    fun stop() {
        db.stop()
        Files.walk(mountRoot).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun <T> withService(
        clock: Clock = Clock.systemUTC(),
        block: Fixture.() -> T,
    ): T {
        var result: Result<T>? = null
        tpl18Service()
            .withPropertyValues(
                "spring.datasource.url=${db.jdbcUrl}",
                "spring.datasource.username=pers_rt",
                "test.default-schema=false",
                "inv.persistence.append-only-tables=pers.declared_log",
            ).withMountedLogin(mountRoot, "runtime-pw")
            .withBean(Clock::class.java, { clock })
            .withUserConfiguration(ServiceSchema::class.java)
            .run { context ->
                context.startupFailure shouldBe null
                val fixture = Fixture(context, clock)
                result = runCatching { fixture.block() }
                fixture.close()
            }
        return result!!.getOrThrow()
    }

    private fun account(
        tenant: TenantId,
        name: String = "main",
        balance: Int = 10,
        version: Long = 0,
    ) = Account(UUID.randomUUID(), tenant.value, name, balance, version)

    @Test
    fun `insert then load returns the row and another tenant's id loads null`() =
        withService<Unit> {
            val row = account(a)
            asTenant(a) { accounts.insert(row) }
            asTenant(a) { accounts.load(row.id) } shouldBe row
            asTenant(b) { accounts.load(row.id) } shouldBe null
        }

    @Test
    fun `insertAll writes one insert statement per row in list order and an empty list runs none`() =
        withService<Unit> {
            val rows = (1..3).map { account(a, name = "n$it") }
            statements.clear()
            asTenant(a) { accounts.insertAll(rows) }
            asTenant(a) { rows.map { accounts.load(it.id) } } shouldBe rows
            statements.count { it.startsWith("insert into") } shouldBe rows.size
            statements.clear()
            asTenant(a) { accounts.insertAll(emptyList()) }
            statements.count { it.startsWith("insert into") } shouldBe 0
        }

    @Test
    fun `update writes only the changed columns and version plus one under the version predicate`() =
        withService<Unit> {
            val before = account(a)
            asTenant(a) { accounts.insert(before) }
            statements.clear()
            val outcome = asTenant(a) { accounts.update(before, before.copy(name = "renamed")) }
            outcome shouldBe UpdateOutcome.Updated(1)
            val update = statements.single { it.startsWith("update") }
            val setList = update.substringAfter(" set ").substringBefore(" where ")
            setList.split(", ").map { it.substringBefore(" =") }.toSet() shouldBe setOf("\"name\"", "\"version\"")
            update.substringAfter(" where ") shouldContain "\"version\" = ?"
            asTenant(a) { accounts.load(before.id) } shouldBe before.copy(name = "renamed", version = 1)
        }

    @Test
    fun `update with equal rows runs no statement`() =
        withService<Unit> {
            val row = account(a)
            asTenant(a) { accounts.insert(row) }
            statements.clear()
            asTenant(a) { accounts.update(row, row.copy()) } shouldBe UpdateOutcome.Unchanged
            statements.shouldBeEmpty()
        }

    @Test
    fun `update with a stale version returns the conflict outcome and changes nothing`() =
        withService<Unit> {
            val first = account(a)
            asTenant(a) { accounts.insert(first) }
            asTenant(a) { accounts.update(first, first.copy(name = "second")) } shouldBe UpdateOutcome.Updated(1)
            // a second writer still holds version 0
            asTenant(a) { accounts.update(first, first.copy(name = "third", balance = 99)) } shouldBe
                UpdateOutcome.Conflict
            asTenant(a) { accounts.load(first.id) } shouldBe first.copy(name = "second", version = 1)
        }

    @Test
    fun `two concurrent updates of one row let exactly one succeed`() =
        withService<Unit> {
            val row = account(a)
            asTenant(a) { accounts.insert(row) }
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val outcomes =
                    (1..2)
                        .map { n ->
                            pool.submit<UpdateOutcome> {
                                asTenant(a) {
                                    val loaded = accounts.load(row.id)!!
                                    barrier.await(30, TimeUnit.SECONDS)
                                    accounts.update(loaded, loaded.copy(name = "writer-$n"))
                                }
                            }
                        }.map { it.get(60, TimeUnit.SECONDS) }
                outcomes.count { it is UpdateOutcome.Updated } shouldBe 1
                outcomes.count { it == UpdateOutcome.Conflict } shouldBe 1
                asTenant(a) { accounts.load(row.id)!!.version } shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }

    @Test
    fun `delete removes the row at its version and a stale version is a conflict`() =
        withService<Unit> {
            val row = account(a)
            asTenant(a) { accounts.insert(row) }
            asTenant(a) { accounts.update(row, row.copy(name = "x")) } shouldBe UpdateOutcome.Updated(1)
            asTenant(a) { accounts.delete(row) } shouldBe DeleteOutcome.Conflict
            rowCount(a, row.id) shouldBe 1
            asTenant(a) { accounts.delete(row.copy(name = "x", version = 1)) } shouldBe DeleteOutcome.Deleted
            rowCount(a, row.id) shouldBe 0
        }

    @Test
    fun `update and delete of another tenant's row change nothing and return the conflict outcome`() =
        withService<Unit> {
            val theirs = account(b)
            asTenant(b) { accounts.insert(theirs) }
            asTenant(a) { accounts.update(theirs, theirs.copy(name = "taken")) } shouldBe UpdateOutcome.Conflict
            asTenant(a) { accounts.delete(theirs) } shouldBe DeleteOutcome.Conflict
            asTenant(b) { accounts.load(theirs.id) } shouldBe theirs
        }

    @Test
    fun `insert of a POJO carrying another tenant's tenant_id is refused before execution`() =
        withService<Unit> {
            statements.clear()
            shouldThrow<TenantPredicateViolation> { asTenant(a) { accounts.insert(account(b)) } }
            statements.shouldBeEmpty()
            // row by row: the row of tenant a runs, the row of tenant b stops the call, the transaction rolls back
            val mine = account(a)
            shouldThrow<TenantPredicateViolation> { asTenant(a) { accounts.insertAll(listOf(mine, account(b))) } }
            statements.count { it.startsWith("insert into") } shouldBe 1
            rowCount(a, mine.id) shouldBe 0
        }

    @Test
    fun `any call with no open transaction or no binding is refused before SQL`() =
        withService<Unit> {
            val row = account(a)
            executed.clear()
            shouldThrow<Exception> { inBoundNoTransaction { accounts.load(row.id) } }
            shouldThrow<Exception> { inBoundNoTransaction { accounts.insert(row) } }
            shouldThrow<Exception> { inBoundNoTransaction { accounts.update(row, row.copy(name = "x")) } }
            shouldThrow<Exception> { inBoundNoTransaction { accounts.delete(row) } }
            shouldThrow<Exception> { accounts.load(row.id) }
            shouldThrow<Exception> { accounts.insertAll(listOf(row)) }
            executed.shouldBeEmpty()
        }

    @Test
    fun `audit columns are set on insert and update from the clock and the bound actor`() {
        val at = Instant.parse("2026-03-04T05:06:07Z")
        val clock = Clock.fixed(at, ZoneOffset.UTC)
        withService(clock) {
            val audited = layer.aggregates<AuditedRecord, Audited, UUID>(AUDITED, Audited::class.java)
            val row = Audited(UUID.randomUUID(), a.value, "l", 0, null, null, null, null)
            asTenant(a, "user:alice") { audited.insert(row) }
            val inserted = asTenant(a) { audited.load(row.id)!! }
            inserted.createdAt?.toInstant() shouldBe at
            inserted.updatedAt?.toInstant() shouldBe at
            inserted.createdBy shouldBe "user:alice"
            inserted.updatedBy shouldBe "user:alice"
            asTenant(a, "user:bob") { audited.update(inserted, inserted.copy(label = "m")) } shouldBe
                UpdateOutcome.Updated(inserted.version + 1)
            val updated = asTenant(a) { audited.load(row.id)!! }
            updated.createdBy shouldBe "user:alice"
            updated.createdAt?.toInstant() shouldBe at
            updated.updatedBy shouldBe "user:bob"
        }
    }

    @Test
    fun `audit columns are set on every row of insertAll and appendAll`() {
        val at = Instant.parse("2026-03-04T05:06:07Z")
        withService<Unit>(Clock.fixed(at, ZoneOffset.UTC)) {
            val audited = layer.aggregates<AuditedRecord, Audited, UUID>(AUDITED, Audited::class.java)
            val appender = layer.ledger<AuditedRecord, Audited>(AUDITED, Audited::class.java)
            val viaStore = listOf(freshAudited(), freshAudited())
            val viaAppender = listOf(freshAudited(), freshAudited())
            asTenant(a, "user:alice") { audited.insertAll(viaStore) }
            asTenant(a, "user:alice") { appender.appendAll(viaAppender) }
            (viaStore + viaAppender).forEach { row ->
                val stored = asTenant(a) { audited.load(row.id)!! }
                stored.createdAt?.toInstant() shouldBe at
                stored.updatedAt?.toInstant() shouldBe at
                stored.createdBy shouldBe "user:alice"
                stored.updatedBy shouldBe "user:alice"
            }
        }
    }

    @Test
    fun `a row with a caller-set audit actor stops insertAll and appendAll and the transaction keeps nothing`() =
        withService<Unit> {
            val audited = layer.aggregates<AuditedRecord, Audited, UUID>(AUDITED, Audited::class.java)
            val appender = layer.ledger<AuditedRecord, Audited>(AUDITED, Audited::class.java)
            val label = "t-${UUID.randomUUID()}"
            val forged = freshAudited(label = label).copy(createdBy = "user:mallory")
            statements.clear()
            shouldThrow<AuditColumnViolation> {
                asTenant(a, "user:alice") { audited.insertAll(listOf(freshAudited(label = label), forged)) }
            }
            statements.count { it.startsWith("insert into") } shouldBe 1
            asTenant(a) { dsl.fetchCount(AUDITED, AUDITED.LABEL.eq(label)) } shouldBe 0
            statements.clear()
            shouldThrow<AuditColumnViolation> {
                asTenant(a, "user:alice") { appender.appendAll(listOf(freshAudited(label = label), forged)) }
            }
            statements.count { it.startsWith("insert into") } shouldBe 1
            asTenant(a) { dsl.fetchCount(AUDITED, AUDITED.LABEL.eq(label)) } shouldBe 0
        }

    @Test
    fun `a row of another tenant stops insertAll and appendAll before its statement and nothing is kept`() =
        withService<Unit> {
            val audited = layer.aggregates<AuditedRecord, Audited, UUID>(AUDITED, Audited::class.java)
            val appender = layer.ledger<AuditedRecord, Audited>(AUDITED, Audited::class.java)
            val label = "t-${UUID.randomUUID()}"
            val theirs = freshAudited(b, label)
            statements.clear()
            shouldThrow<TenantPredicateViolation> {
                asTenant(a, "user:alice") { audited.insertAll(listOf(freshAudited(label = label), theirs)) }
            }
            statements.count { it.startsWith("insert into") } shouldBe 1
            asTenant(a) { dsl.fetchCount(AUDITED, AUDITED.LABEL.eq(label)) } shouldBe 0
            statements.clear()
            shouldThrow<TenantPredicateViolation> {
                asTenant(a, "user:alice") { appender.appendAll(listOf(freshAudited(label = label), theirs)) }
            }
            statements.count { it.startsWith("insert into") } shouldBe 1
            asTenant(a) { dsl.fetchCount(AUDITED, AUDITED.LABEL.eq(label)) } shouldBe 0
        }

    @Test
    fun `insertAll and appendAll under a binding without a usable actor insert nothing`() =
        withService<Unit> {
            val audited = layer.aggregates<AuditedRecord, Audited, UUID>(AUDITED, Audited::class.java)
            val appender = layer.ledger<AuditedRecord, Audited>(AUDITED, Audited::class.java)
            val label = "t-${UUID.randomUUID()}"
            // TenantContext.bind refuses a blank actor itself, so no binding without an actor exists here; the
            // listener's own refusal of a null or blank actor is covered by AuditColumnRecordListenerTest.
            shouldThrow<IllegalArgumentException> {
                asTenant(
                    a,
                    " ",
                ) { audited.insertAll(listOf(freshAudited(label = label))) }
            }
            shouldThrow<IllegalArgumentException> {
                asTenant(
                    a,
                    " ",
                ) { appender.appendAll(listOf(freshAudited(label = label))) }
            }
            asTenant(a) { dsl.fetchCount(AUDITED, AUDITED.LABEL.eq(label)) } shouldBe 0
        }

    private fun freshAudited(
        tenant: TenantId = a,
        label: String = "l",
    ) = Audited(UUID.randomUUID(), tenant.value, label, 0, null, null, null, null)

    @Test
    fun `a table without audit columns is untouched`() =
        withService<Unit> {
            val row = account(a)
            asTenant(a) { accounts.insert(row) }
            statements.single { it.startsWith("insert into") } shouldBe
                "insert into \"pers\".\"account\" (\"id\", \"tenant_id\", \"name\", \"balance\", \"version\") " +
                "values (cast(? as uuid), cast(? as uuid), ?, ?, ?) returning \"pers\".\"account\".\"id\""
        }

    @Test
    fun `caller-set audit values that break the rules are refused`() =
        withService<Unit> {
            val audited = layer.aggregates<AuditedRecord, Audited, UUID>(AUDITED, Audited::class.java)
            val base = Audited(UUID.randomUUID(), a.value, "l", 0, null, null, null, null)
            shouldThrow<AuditColumnViolation> {
                asTenant(a, "user:alice") { audited.insert(base.copy(createdBy = "user:mallory")) }
            }
            shouldThrow<AuditColumnViolation> {
                asTenant(a, "user:alice") { audited.insert(base.copy(updatedBy = "user:mallory")) }
            }
            asTenant(a, "user:alice") { audited.insert(base.copy(createdBy = "user:alice")) }
            val stored = asTenant(a) { audited.load(base.id)!! }
            shouldThrow<AuditColumnViolation> {
                asTenant(a, "user:alice") { audited.update(stored, stored.copy(createdBy = "user:mallory")) }
            }
            shouldThrow<AuditColumnViolation> {
                asTenant(a, "user:alice") { audited.update(stored, stored.copy(createdAt = now.minusDays(1))) }
            }
            shouldThrow<AuditColumnViolation> {
                asTenant(
                    a,
                    "user:alice",
                ) { audited.update(stored, stored.copy(label = "m", updatedBy = "user:mallory")) }
            }
            asTenant(a) { audited.load(base.id) } shouldBe stored
        }

    @Test
    fun `the store and appender refuse unsuitable tables at construction`() =
        withService<Unit> {
            shouldThrow<IllegalArgumentException> {
                layer.aggregates<LedgerEntryRecord, LedgerEntry, UUID>(LEDGER_ENTRY, LedgerEntry::class.java)
            }.message shouldContain "no numeric 'version' column"
            shouldThrow<IllegalArgumentException> {
                layer.aggregates<AuditEntryRecord, AuditEntry, UUID>(AUDIT_ENTRY, AuditEntry::class.java)
            }.message shouldContain "protected"
            shouldThrow<IllegalArgumentException> {
                layer.aggregates<DeclaredLogRecord, DeclaredLog, UUID>(DECLARED_LOG, DeclaredLog::class.java)
            }.message shouldContain "append-only"
            shouldThrow<IllegalArgumentException> {
                layer.aggregates<HalfAuditedRecord, HalfAudited, UUID>(HALF_AUDITED, HalfAudited::class.java)
            }.message shouldContain "audit columns"
            shouldThrow<IllegalArgumentException> {
                layer.ledger<HalfAuditedRecord, HalfAudited>(
                    HALF_AUDITED,
                    HalfAudited::class.java,
                )
            }.message shouldContain "audit columns"
        }

    @Test
    fun `the layer refuses a DSLContext without the tenancy listeners or the layer's configuration`() =
        withService<Unit> {
            shouldThrow<IllegalArgumentException> { PersistenceLayer(DSL.using(SQLDialect.POSTGRES), guards) }
                .message shouldContain "not tenancy-jooq's"
            val tenancyOnly =
                TenancyJooq
                    .configure(
                        DefaultConfiguration().set(SQLDialect.POSTGRES),
                        context.getBean(TenancyTables::class.java),
                        tenants,
                        context.getBean(TransactionRunner::class.java),
                    ).dsl()
            shouldThrow<IllegalArgumentException> { PersistenceLayer(tenancyOnly, guards) }
                .message shouldContain "OptimisticLocking"
        }

    @Test
    fun `the appender appends and offers nothing that changes an existing row`() =
        withService<Unit> {
            val appender = layer.ledger<LedgerEntryRecord, LedgerEntry>(LEDGER_ENTRY, LedgerEntry::class.java)
            val one = LedgerEntry(UUID.randomUUID(), a.value, 5)
            val others = (1..3).map { LedgerEntry(UUID.randomUUID(), a.value, it) }
            asTenant(a) { appender.append(one) }
            statements.clear()
            asTenant(a) { appender.appendAll(others) }
            statements.count { it.startsWith("insert into") } shouldBe others.size
            statements.clear()
            asTenant(a) { appender.appendAll(emptyList()) }
            statements.count { it.startsWith("insert into") } shouldBe 0
            val ids = (others + one).map { it.id }
            asTenant(a) { dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.ID.`in`(ids)) } shouldBe 4
            asTenant(b) { dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.ID.`in`(ids)) } shouldBe 0
            shouldThrow<TenantPredicateViolation> { asTenant(b) { appender.append(one) } }
            LedgerAppender::class.java.methods
                .map { it.name }
                .filter { it !in OBJECT_METHODS }
                .toSet() shouldBe
                setOf("append", "appendAll")
        }

    @Test
    fun `the appender over a declared append-only table constructs and appends`() =
        withService<Unit> {
            val appender = layer.ledger<DeclaredLogRecord, DeclaredLog>(DECLARED_LOG, DeclaredLog::class.java)
            val row = DeclaredLog(UUID.randomUUID(), a.value, "entry", 0)
            asTenant(a) { appender.append(row) }
            asTenant(a) { dsl.fetchCount(DECLARED_LOG, DECLARED_LOG.ID.eq(row.id)) } shouldBe 1
        }

    // VT1: the row stores the version it is given (INV-Q-6770)
    @Test
    fun `insert stores the version it is given`() =
        withService<Unit> {
            val rows = listOf(0L, 1L, 7L).map { account(a, version = it) }
            rows.forEach { row -> asTenant(a) { accounts.insert(row) } }
            rows.forEach { row -> asTenant(a) { accounts.load(row.id) } shouldBe row }
        }

    // VT2
    @Test
    fun `insertAll stores the versions it is given`() =
        withService<Unit> {
            val rows = listOf(0L, 1L, 7L).map { account(a, version = it) }
            asTenant(a) { accounts.insertAll(rows) }
            rows.forEach { row -> asTenant(a) { accounts.load(row.id) } shouldBe row }
        }

    // VT3
    @Test
    fun `append and appendAll store the versions they are given and an unversioned table is untouched`() =
        withService<Unit> {
            val appender = layer.ledger<DeclaredLogRecord, DeclaredLog>(DECLARED_LOG, DeclaredLog::class.java)
            val rows = listOf(0L, 7L, 1L, 3L).map { DeclaredLog(UUID.randomUUID(), a.value, "e$it", it) }
            asTenant(a) {
                appender.append(rows[0])
                appender.append(rows[1])
                appender.appendAll(rows.drop(2))
            }
            rows.forEach { row ->
                asTenant(
                    a,
                ) {
                    dsl
                        .selectFrom(DECLARED_LOG)
                        .where(DECLARED_LOG.ID.eq(row.id))
                        .fetchOne()
                        ?.version
                } shouldBe
                    row.version
            }
            val plain = layer.ledger<LedgerEntryRecord, LedgerEntry>(LEDGER_ENTRY, LedgerEntry::class.java)
            val entries = (1..2).map { LedgerEntry(UUID.randomUUID(), a.value, it) }
            asTenant(a) {
                plain.append(entries[0])
                plain.appendAll(entries.drop(1))
            }
            asTenant(a) { dsl.fetchCount(LEDGER_ENTRY, LEDGER_ENTRY.ID.`in`(entries.map { it.id })) } shouldBe 2
        }

    // VT4: no reload between updates
    @Test
    fun `the caller's own POJO is the before of its next update`() =
        withService<Unit> {
            val row = account(a)
            asTenant(a) { accounts.insert(row) }
            asTenant(a) { accounts.update(row, row.copy(name = "b")) } shouldBe UpdateOutcome.Updated(1)
            asTenant(a) { accounts.load(row.id)!!.version } shouldBe 1
            asTenant(a) { accounts.update(row.copy(name = "b", version = 1), row.copy(name = "c")) } shouldBe
                UpdateOutcome.Updated(2)
            asTenant(a) { accounts.update(row, row.copy(name = "d")) } shouldBe UpdateOutcome.Conflict
            asTenant(a) { accounts.load(row.id) } shouldBe row.copy(name = "c", version = 2)
        }

    // VT5
    @Test
    fun `update ignores the version of after`() =
        withService<Unit> {
            val row = account(a)
            asTenant(a) { accounts.insert(row) }
            asTenant(a) { accounts.update(row, row.copy(name = "x", version = 9)) } shouldBe UpdateOutcome.Updated(1)
            asTenant(a) { accounts.load(row.id)!!.version } shouldBe 1
        }

    // VT6
    @Test
    fun `a negative version is refused before any statement`() =
        withService<Unit> {
            val appender = layer.ledger<DeclaredLogRecord, DeclaredLog>(DECLARED_LOG, DeclaredLog::class.java)
            val good = account(a)
            val bad = account(a, version = -1)
            val goodLog = DeclaredLog(UUID.randomUUID(), a.value, "good", 0)
            val badLog = DeclaredLog(UUID.randomUUID(), a.value, "bad", -1)
            statements.clear()
            val calls =
                listOf<() -> Unit>(
                    { accounts.insert(bad) },
                    { accounts.insertAll(listOf(good, bad)) },
                    { appender.append(badLog) },
                    { appender.appendAll(listOf(goodLog, badLog)) },
                )
            calls.forEach { call ->
                shouldThrow<IllegalArgumentException> { asTenant(a) { call() } }.message shouldContain "version"
            }
            statements.count { it.startsWith("insert into") } shouldBe 0
            asTenant(a) { dsl.fetchCount(ACCOUNT, ACCOUNT.ID.`in`(good.id, bad.id)) } shouldBe 0
            asTenant(a) { dsl.fetchCount(DECLARED_LOG, DECLARED_LOG.ID.`in`(goodLog.id, badLog.id)) } shouldBe 0
        }

    // VT7: an Int version
    @Test
    fun `an Int version column follows the same rule`() =
        withService<Unit> {
            val counters = layer.aggregates<CounterRecord, Counter, UUID>(COUNTER, Counter::class.java)
            val rows = listOf(0, 7).map { Counter(UUID.randomUUID(), a.value, "c$it", it) }
            rows.forEach { row -> asTenant(a) { counters.insert(row) } }
            rows.forEach { row -> asTenant(a) { counters.load(row.id) } shouldBe row }
            val first = rows[0]
            asTenant(a) { counters.update(first, first.copy(name = "z")) } shouldBe UpdateOutcome.Updated(1)
            asTenant(a) { counters.load(first.id)!!.version } shouldBe 1
            shouldThrow<IllegalArgumentException> {
                asTenant(a) { counters.insert(Counter(UUID.randomUUID(), a.value, "neg", -1)) }
            }.message shouldContain "version"
        }

    private companion object {
        val STATEMENT = Regex("Executing query\\s*:\\s*(.*)", RegexOption.DOT_MATCHES_ALL)
        val EXECUTED = Regex("^(Fetched row\\(s\\)|Affected row\\(s\\))")
        val OBJECT_METHODS = setOf("equals", "hashCode", "toString")
    }
}
