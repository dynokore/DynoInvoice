package com.dynokore.gst.payload.adapters

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A claim locks only the row it takes, so an uncommitted claim hides neither another partition nor, inside one
 * partition, anything but the order (INV-REQ-549, INV-AC-14143).
 */
class ClaimDueOverlapTest {
    private val tenant = UUID.randomUUID()

    @BeforeEach
    fun before() = clear()

    @AfterEach
    fun after() = clear()

    private fun clear() {
        PayloadDb.superuser().use {
            it.createStatement().use { s ->
                s.executeUpdate("delete from payload.work_queue")
            }
        }
    }

    private fun enqueue(partition: String) {
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "insert into payload.work_queue (tenant_id, partition_key, lane, payload) " +
                        "values (?, ?, 'LIVE', '{}'::jsonb)",
                ).use {
                    it.setObject(1, tenant)
                    it.setString(2, partition)
                    it.executeUpdate()
                }
        }
    }

    private fun claim(connection: Connection): List<String> =
        connection.createStatement().use { s ->
            s
                .executeQuery(
                    "select claimed_partition from payload.claim_due('LIVE', null, now(), now() - interval '1 hour')",
                ).use { r ->
                    buildList { while (r.next()) add(r.getString(1)) }
                }
        }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `an uncommitted claim does not hide another partition`() {
        enqueue("A")
        enqueue("B")
        val one = PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD)
        val two = PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD)
        try {
            one.autoCommit = false
            two.autoCommit = true
            assertEquals(listOf("A"), claim(one))
            assertEquals(listOf("B"), claim(two))
            one.commit()
            assertEquals(emptyList<String>(), claim(two))
        } finally {
            runCatching { one.close() }
            runCatching { two.close() }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `an uncommitted claim keeps the order inside one partition`() {
        enqueue("C")
        enqueue("C")
        val one = PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD)
        val two = PayloadDb.connect(PayloadDb.RUNTIME, PayloadDb.RUNTIME_PASSWORD)
        try {
            one.autoCommit = false
            two.autoCommit = true
            assertEquals(listOf("C"), claim(one))
            assertEquals(emptyList<String>(), claim(two))
            one.commit()
            PayloadDb.superuser().use {
                it.createStatement().use { s ->
                    s.executeUpdate(
                        "delete from payload.work_queue where seq = (select min(seq) from payload.work_queue)",
                    )
                }
            }
            assertEquals(listOf("C"), claim(two))
        } finally {
            runCatching { one.close() }
            runCatching { two.close() }
        }
    }
}
