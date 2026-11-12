package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [OutboxWriter.appendSequenced] on PostgreSQL 17 and 18 (INV-AC-20996): the payload is built from the `docsequence`
 * reserved in the caller's transaction, so the column, the envelope and the data member agree; numbers are gap-free
 * under concurrency; a failure or rollback leaves nothing.
 */
@Tag("integration")
class OutboxSequencedAppendIT {
    private class Fn {
        val args = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val calls = AtomicInteger()

        fun call(sequence: Long): String {
            calls.incrementAndGet()
            args += sequence
            return """{"docsequence":$sequence}"""
        }
    }

    private class Row(
        val column: Long,
        val data: Long,
    )

    private val type = TestCatalogue.CANONICAL_VERSION_CREATED

    private fun rowsOf(
        harness: OutboxHarness,
        key: String,
    ): List<Row> =
        harness.fixture.adminDataSource().connection.use { c ->
            c
                .prepareStatement(
                    "select docsequence, (data->>'docsequence')::numeric " +
                        "from canonical.outbox where subject = ? order by seq",
                ).use { st ->
                    st.setString(1, key)
                    st.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(Row(rs.getLong(1), rs.getBigDecimal(2).toLong())) }
                    }
                }
        }

    private fun counter(
        harness: OutboxHarness,
        key: String,
    ): Long =
        harness.count("select coalesce(max(last_value), 0) from canonical.outbox_sequence where aggregate_key = ?", key)

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20996 two calls on one key give 1 and 2 and the column, envelope and data member agree`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.canonical
            val context = kit.docContext(ServiceKit.TENANT, UUID.randomUUID())
            val fn = Fn()
            val envelopes =
                (1..2).map {
                    kit.inTx(
                        ServiceKit.TENANT,
                    ) { kit.writer.appendSequenced(type, context, fn::call) }
                }
            assertEquals(listOf(1L, 2L), fn.args)
            assertEquals(listOf(1L, 2L), envelopes.map { it.docSequence })
            assertEquals(listOf(1L, 2L), rowsOf(harness, context.aggregateKey).map { it.column })
            assertEquals(listOf(1L, 2L), rowsOf(harness, context.aggregateKey).map { it.data })
            assertEquals(2, fn.calls.get())
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20996 a key with aggregateVersion 5 gives 5`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.canonical
            val context = kit.docContext(ServiceKit.TENANT, UUID.randomUUID()).copy(aggregateVersion = 5)
            val fn = Fn()
            kit.inTx(ServiceKit.TENANT) { kit.writer.appendSequenced(type, context, fn::call) }
            assertEquals(listOf(5L), fn.args)
            assertEquals(5L, rowsOf(harness, context.aggregateKey).single().column)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20996 eight threads of four transactions give exactly 1 to 32 in seq order`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.canonical
            val context = kit.docContext(ServiceKit.TENANT, UUID.randomUUID())
            val fn = Fn()
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            val futures =
                (1..8).map {
                    pool.submit {
                        start.await()
                        repeat(
                            4,
                        ) { kit.inTx(ServiceKit.TENANT) { kit.writer.appendSequenced(type, context, fn::call) } }
                    }
                }
            start.countDown()
            futures.forEach { it.get(120, TimeUnit.SECONDS) }
            pool.shutdown()
            val rows = rowsOf(harness, context.aggregateKey)
            assertEquals((1L..32L).toList(), rows.map { it.column }.sorted())
            assertEquals((1L..32L).toList(), rows.map { it.column }) // increasing in seq order
            rows.forEach { assertEquals(it.column, it.data) }
            assertEquals(32, fn.calls.get())
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20996 a rolled-back call leaves no row and the next call gives 3`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.canonical
            val context = kit.docContext(ServiceKit.TENANT, UUID.randomUUID())
            val fn = Fn()
            repeat(2) { kit.inTx(ServiceKit.TENANT) { kit.writer.appendSequenced(type, context, fn::call) } }
            val boom = IllegalStateException("rollback")
            val thrown =
                assertThrows(IllegalStateException::class.java) {
                    kit.inTx(ServiceKit.TENANT) {
                        kit.writer.appendSequenced(type, context, fn::call)
                        throw boom
                    }
                }
            assertSame(boom, thrown)
            assertEquals(2L, counter(harness, context.aggregateKey))
            assertEquals(2, rowsOf(harness, context.aggregateKey).size)
            kit.inTx(ServiceKit.TENANT) { kit.writer.appendSequenced(type, context, fn::call) }
            assertEquals(listOf(1L, 2L, 3L, 3L), fn.args)
            assertEquals(listOf(1L, 2L, 3L), rowsOf(harness, context.aggregateKey).map { it.column })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20996 a throwing payload function reaches the caller and leaves no row and no counter change`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val kit = harness.canonical
            val context = kit.docContext(ServiceKit.TENANT, UUID.randomUUID())
            kit.inTx(ServiceKit.TENANT) { kit.writer.appendSequenced(type, context, Fn()::call) }
            val boom = IllegalStateException("payload failed")
            val thrown =
                assertThrows(IllegalStateException::class.java) {
                    kit.inTx(ServiceKit.TENANT) { kit.writer.appendSequenced(type, context) { throw boom } }
                }
            assertSame(boom, thrown)
            assertEquals(1, rowsOf(harness, context.aggregateKey).size)
            assertEquals(1L, counter(harness, context.aggregateKey))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20996 an uncatalogued type and an unsequenced context are refused before the function and the counter`(
        image: String,
    ) {
        OutboxHarness(image).use { harness ->
            val kit = harness.canonical
            val context = kit.docContext(ServiceKit.TENANT, UUID.randomUUID())
            val fn = Fn()
            assertThrows(EmissionNotPermitted::class.java) {
                kit.inTx(ServiceKit.TENANT) { kit.writer.appendSequenced("NotCatalogued", context, fn::call) }
            }
            val bare = context.copy(docUuid = null, aggregateVersion = null)
            assertThrows(IllegalArgumentException::class.java) {
                kit.inTx(ServiceKit.TENANT) { kit.writer.appendSequenced(type, bare, fn::call) }
            }
            assertEquals(0, fn.calls.get())
            assertEquals(0, rowsOf(harness, context.aggregateKey).size)
            assertEquals(0L, counter(harness, context.aggregateKey))
            assertEquals(0L, counter(harness, bare.aggregateKey))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-20996 append for a fresh key still stamps and writes`(image: String) {
        OutboxHarness(image).use { harness ->
            val kit = harness.canonical
            val context = kit.docContext(ServiceKit.TENANT, UUID.randomUUID())
            val written =
                kit
                    .inTx(ServiceKit.TENANT) {
                        kit.writer.append(listOf(OutboundEvent(type, """{"docsequence":1}""")), context)
                    }.single()
            assertEquals(1L, written.docSequence)
            assertEquals(1L, rowsOf(harness, context.aggregateKey).single().column)
            assertNotEquals(0, written.id.value.length)
            assertTrue(counter(harness, context.aggregateKey) == 1L)
        }
    }
}
