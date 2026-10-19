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
 * security; with it off the framework's tenant predicate is the only thing separating the tenants, and every result
 * must
 * be the same.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PersistenceLayerITBase {
    protected abstract val image: String
    protected abstract val rls: Boolean

    protected val a = TenantId(UUID(1L, 1L))
    protected val b = TenantId(UUID(2L, 2L))
    private lateinit var db: PostgreSQLContainer<*>
    private lateinit var mountRoot: Path

    @Configuration(proxyBeanMethods = false)
    class ServiceSchema {
        @Bean
        fun persSchema(): Pers = Pers.PERS
    }

    @BeforeAll
    fun start() {
        val init = persistenceInitSql(javaClass, rls)
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

    protected fun <T> withService(
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
                val fixture = Fixture(context, clock, a)
                result = runCatching { fixture.block() }
                fixture.close()
            }
        return result!!.getOrThrow()
    }

    protected fun account(
        tenant: TenantId,
        name: String = "main",
        balance: Int = 10,
        version: Long = 0,
    ) = Account(UUID.randomUUID(), tenant.value, name, balance, version)
}
