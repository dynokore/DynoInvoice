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

/** The audit-column tests of the persistence layer (INV-T-4224). */
abstract class PersistenceAuditITBase : PersistenceRowsITBase() {
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
}
