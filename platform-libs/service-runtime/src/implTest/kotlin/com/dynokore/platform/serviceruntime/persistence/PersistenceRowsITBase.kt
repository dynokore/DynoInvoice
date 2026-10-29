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

/** The insert, update, delete and tenant-guard tests of the persistence layer (INV-T-4224). */
abstract class PersistenceRowsITBase : PersistenceLayerITBase() {
    @Test
    fun `insert then load returns the row and another tenant's id loads null`() =
        withService {
            val row = account(a)
            asTenant(a) { accounts.insert(row) }
            asTenant(a) { accounts.load(row.id) } shouldBe row
            asTenant(b) { accounts.load(row.id) } shouldBe null
        }

    @Test
    fun `insertAll writes one insert statement per row in list order and an empty list runs none`() =
        withService {
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
        withService {
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
        withService {
            val row = account(a)
            asTenant(a) { accounts.insert(row) }
            statements.clear()
            asTenant(a) { accounts.update(row, row.copy()) } shouldBe UpdateOutcome.Unchanged
            statements.shouldBeEmpty()
        }

    @Test
    fun `update with a stale version returns the conflict outcome and changes nothing`() =
        withService {
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
        withService {
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
        withService {
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
        withService {
            val theirs = account(b)
            asTenant(b) { accounts.insert(theirs) }
            asTenant(a) { accounts.update(theirs, theirs.copy(name = "taken")) } shouldBe UpdateOutcome.Conflict
            asTenant(a) { accounts.delete(theirs) } shouldBe DeleteOutcome.Conflict
            asTenant(b) { accounts.load(theirs.id) } shouldBe theirs
        }

    @Test
    fun `insert of a POJO carrying another tenant's tenant_id is refused before execution`() =
        withService {
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
        withService {
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
}
