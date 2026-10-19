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

/** What a persistence-layer test sees: the context, the layer, its store and the statements its DSLContext ran. */
class Fixture(
    val context: AssertableApplicationContext,
    private val clock: Clock,
    private val a: TenantId,
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
                // jOOQ logs these only after the server ran the statement (the statement line is logged at render
                // time)
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

/** The fixture database's initial SQL: the role, the schema and its tables, and (with [rls]) the row-level security. */
fun persistenceInitSql(
    owner: Class<*>,
    rls: Boolean,
): String {
    val migration =
        checkNotNull(owner.getResource("/db/migration/V1__persistence_fixture.sql")) { "fixture migration" }
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
    return init
}

private val STATEMENT = Regex("Executing query\\s*:\\s*(.*)", RegexOption.DOT_MATCHES_ALL)
private val EXECUTED = Regex("^(Fetched row\\(s\\)|Affected row\\(s\\))")
