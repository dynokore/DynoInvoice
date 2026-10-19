package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SCHEMA
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.SERVICE
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import net.javacrumbs.shedlock.core.LockConfiguration
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * ShedLock's storage on jOOQ (owner answer to INV-Q-5210, option A). Before and after: the tests of `LeaderElectionIT`
 * and `JobContractIT` keep their assertions; the new tests below prove the four operations of
 * `ShedLockJooqAccessor`: `insertRecord` false for an existing name and true for a new one; `updateRecord` takes a
 * lapsed lock and not a held one; `unlock` keeps the lock until locked_at + lockAtLeastFor; `extend` works for the
 * holder only; `locked_at` is the UTC database clock; and each operation is one transaction.
 */
class ShedLockJooqAccessorIT {
    private class CountingRunner(
        private val delegate: TransactionRunner,
    ) : TransactionRunner by delegate {
        val transactions = AtomicInteger()

        override fun <T> inTransaction(block: () -> T): T {
            transactions.incrementAndGet()
            return delegate.inTransaction(block)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the four operations take, keep, hold and extend a lock, each in one platform transaction`(image: String) {
        JobsHarness(image).use { h ->
            val replica = h.replica("svc-1", MutableClock(Instant.now()))
            val counting = CountingRunner(replica.tx)
            val scope = TenantScope(replica.tenants, counting, replica.tx, WorkloadPrincipal(SERVICE).value)
            val accessor = ShedLockJooqAccessor(scope, replica.dsl, SCHEMA)

            fun config(
                name: String,
                atMost: Duration = Duration.ofMinutes(5),
                atLeast: Duration = Duration.ZERO,
            ) = LockConfiguration(Instant.now(), name, atMost, atLeast)

            // insertRecord: true for a new name, false for an existing one; one transaction each
            accessor.insertRecord(config("lock-a")) shouldBe true
            accessor.insertRecord(config("lock-a")) shouldBe false
            counting.transactions.get() shouldBe 2

            // updateRecord: a held lock is not taken, a lapsed one is
            accessor.updateRecord(config("lock-a")) shouldBe false
            accessor.insertRecord(config("lock-b", atMost = Duration.ofSeconds(1))) shouldBe true
            Thread.sleep(1_500)
            accessor.updateRecord(config("lock-b")) shouldBe true

            // extend: the holder only (host), and only while the lock is held
            accessor.extend(config("lock-a")) shouldBe true
            accessor.extend(config("no-such-lock")) shouldBe false

            // unlock keeps the lock until locked_at + lockAtLeastFor
            accessor.insertRecord(
                config("lock-c", atMost = Duration.ofMinutes(20), atLeast = Duration.ofMinutes(10)),
            ) shouldBe
                true
            accessor.unlock(config("lock-c", atMost = Duration.ofMinutes(20), atLeast = Duration.ofMinutes(10)))
            val untilC = lockUntil(replica, "lock-c")
            (untilC.isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(9))) shouldBe true
            accessor.unlock(config("lock-a"))
            (lockUntil(replica, "lock-a").isBefore(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(10))) shouldBe true

            // locked_at is the UTC database clock
            val lockedAt = lockedAt(replica, "lock-c")
            Duration
                .between(lockedAt, LocalDateTime.now(ZoneOffset.UTC))
                .abs()
                .seconds
                .let { it < 30 } shouldBe true
        }
    }

    private fun lockUntil(
        replica: JobsHarness.Replica,
        name: String,
    ): LocalDateTime = column(replica, name, "lock_until")

    private fun lockedAt(
        replica: JobsHarness.Replica,
        name: String,
    ): LocalDateTime = column(replica, name, "locked_at")

    private fun column(
        replica: JobsHarness.Replica,
        name: String,
        column: String,
    ): LocalDateTime =
        replica.tenants.bindPlatform("test-operator") {
            replica.tx.inTransaction {
                replica.dsl
                    .select(DSL.field(DSL.name(column), SQLDataType.LOCALDATETIME))
                    .from(DSL.table(DSL.name(SCHEMA, "shedlock")))
                    .where(DSL.field(DSL.name("name"), SQLDataType.VARCHAR).eq(name))
                    .fetchOne(0, LocalDateTime::class.java)!!
            }
        }
}
