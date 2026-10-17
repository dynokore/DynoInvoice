package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The sys-owned offset routines (coordinator ruling INV-Q-5873) called inside a tenant transaction, and the triage page
 * function (INV-Q-5875) read by a session bound to one tenant.
 */
@Tag("integration")
class OffsetRoutinesIT {
    private val tenant = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val topic = "gst.canonical.canonical-version-created.v1"

    private fun routine(
        name: String,
        type: org.jooq.DataType<*>,
        vararg args: Any,
    ) = DSL.function(
        DSL.name("submission", name),
        @Suppress("UNCHECKED_CAST")
        (type as org.jooq.DataType<Any>),
        *args.map { DSL.`val`(it) }.toTypedArray(),
    )

    private fun lock(kit: ServiceKit): Long =
        kit.dsl
            .select(routine(OutboxSchema.CONSUMER_OFFSET_LOCK, SQLDataType.BIGINT, "c", topic))
            .fetchSingle()
            .value1() as Long

    private fun advance(
        kit: ServiceKit,
        next: Long,
    ) {
        kit.dsl.select(routine(OutboxSchema.CONSUMER_OFFSET_ADVANCE, SQLDataType.OTHER, "c", topic, next)).fetch()
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `two deliveries of one consumer and topic serialise on the offset row`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.submission
            val firstHolds = CountDownLatch(1)
            val release = CountDownLatch(1)
            val releasedAt = AtomicLong()
            val secondReturnedAt = AtomicLong()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first =
                    pool.submit {
                        kit.inTx(tenant) {
                            assertEquals(1L, lock(kit))
                            firstHolds.countDown()
                            assertTrue(release.await(30, TimeUnit.SECONDS))
                            releasedAt.set(System.nanoTime())
                        }
                    }
                assertTrue(firstHolds.await(30, TimeUnit.SECONDS))
                val second =
                    pool.submit {
                        kit.inTx(tenant) {
                            lock(kit)
                            secondReturnedAt.set(System.nanoTime())
                        }
                    }
                Thread.sleep(SETTLE_MILLIS)
                assertFalse(second.isDone, "the second delivery waits for the row lock")
                release.countDown()
                first.get(30, TimeUnit.SECONDS)
                second.get(30, TimeUnit.SECONDS)
                assertTrue(
                    secondReturnedAt.get() >= releasedAt.get(),
                    "the second returned after the first transaction ended",
                )
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an offset advanced in a rolled-back transaction is unchanged`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.submission
            kit.inTx(tenant) {
                lock(kit)
                advance(kit, 4)
            }
            runCatching {
                kit.inTx(tenant) {
                    lock(kit)
                    advance(kit, 9)
                    error("killed before commit")
                }
            }
            assertEquals(4L, kit.inTx(tenant) { lock(kit) })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a session bound to one tenant reads the letters of another tenant and of the platform tenant`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.submission
            val triage = JdbcDeadLetterTriage(kit.scope, kit.dsl, "submission", ServiceKit.CLOCK)
            val ids =
                listOf(tenant, other, TenantId.PLATFORM.value).map { owner ->
                    kit.inPlatformTx {
                        val record =
                            DSL.function(
                                DSL.name("submission", OutboxSchema.DEAD_LETTER_RECORD),
                                SQLDataType.BIGINT,
                                DSL.`val`("submission-svc", SQLDataType.CLOB),
                                DSL.`val`(topic, SQLDataType.CLOB),
                                DSL.`val`(1L, SQLDataType.BIGINT),
                                DSL.`val`(owner, SQLDataType.UUID),
                                DSL.`val`("handler schema mismatch", SQLDataType.CLOB),
                                DSL.`val`("{}", SQLDataType.CLOB),
                            )
                        kit.dsl
                            .select(record)
                            .fetchSingle()
                            .value1()
                    }
                }
            val page =
                kit.tenants.bind(TenantId(tenant), "test-actor") {
                    triage.list(DeadLetterScope(), DeadLetterStatus.OPEN, 100)
                }
            assertEquals(ids, page.map { it.id })
        }
    }

    private companion object {
        const val SETTLE_MILLIS = 700L
    }
}
