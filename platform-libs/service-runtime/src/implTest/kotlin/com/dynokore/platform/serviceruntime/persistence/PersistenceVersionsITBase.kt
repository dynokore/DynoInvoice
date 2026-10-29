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

/** The appender, version and construction tests of the persistence layer (INV-T-4224). */
abstract class PersistenceVersionsITBase : PersistenceAuditITBase() {
    @Test
    fun `the appender appends and offers nothing that changes an existing row`() =
        withService {
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
        withService {
            val appender = layer.ledger<DeclaredLogRecord, DeclaredLog>(DECLARED_LOG, DeclaredLog::class.java)
            val row = DeclaredLog(UUID.randomUUID(), a.value, "entry", 0)
            asTenant(a) { appender.append(row) }
            asTenant(a) { dsl.fetchCount(DECLARED_LOG, DECLARED_LOG.ID.eq(row.id)) } shouldBe 1
        }

    // VT1: the row stores the version it is given (INV-Q-6770)
    @Test
    fun `insert stores the version it is given`() =
        withService {
            val rows = listOf(0L, 1L, 7L).map { account(a, version = it) }
            rows.forEach { row -> asTenant(a) { accounts.insert(row) } }
            rows.forEach { row -> asTenant(a) { accounts.load(row.id) } shouldBe row }
        }

    // VT2
    @Test
    fun `insertAll stores the versions it is given`() =
        withService {
            val rows = listOf(0L, 1L, 7L).map { account(a, version = it) }
            asTenant(a) { accounts.insertAll(rows) }
            rows.forEach { row -> asTenant(a) { accounts.load(row.id) } shouldBe row }
        }

    // VT3
    @Test
    fun `append and appendAll store the versions they are given and an unversioned table is untouched`() =
        withService {
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
        withService {
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
        withService {
            val row = account(a)
            asTenant(a) { accounts.insert(row) }
            asTenant(a) { accounts.update(row, row.copy(name = "x", version = 9)) } shouldBe UpdateOutcome.Updated(1)
            asTenant(a) { accounts.load(row.id)!!.version } shouldBe 1
        }

    // VT6
    @Test
    fun `a negative version is refused before any statement`() =
        withService {
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
        withService {
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
        val OBJECT_METHODS = setOf("equals", "hashCode", "toString")
    }
}
