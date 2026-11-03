package com.dynokore.gst.notification.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A claim of `notification.claim_due` locks only the row it takes (PostgreSQL explicit locking, the
 * `FOR UPDATE SKIP LOCKED` queue pattern): an uncommitted claim must not hide the rows of other partitions, and must
 * keep the order inside its own partition. Real database through [NotificationDb].
 */
class ClaimDueOverlapTest {
    private val tenant = UUID.randomUUID()

    @BeforeEach
    @AfterEach
    fun clean() {
        NotificationDb.superuser().use {
            it.createStatement().use { s ->
                s.executeUpdate("delete from notification.work_queue")
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `an uncommitted claim does not hide another partition`() {
        insert("A")
        insert("B")
        val first = runtime(autoCommit = false)
        val second = runtime(autoCommit = true)
        try {
            claim(first) shouldBe listOf("A")
            claim(second) shouldBe listOf("B")
            first.commit()
            claim(second) shouldBe emptyList()
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `an uncommitted claim keeps the order inside one partition`() {
        val c1 = insert("C")
        insert("C")
        val first = runtime(autoCommit = false)
        val second = runtime(autoCommit = true)
        try {
            claim(first) shouldBe listOf("C")
            claim(second) shouldBe emptyList()
            first.commit()
            NotificationDb.superuser().use {
                it.prepareStatement("delete from notification.work_queue where seq = ?").use { s ->
                    s.setLong(1, c1)
                    s.executeUpdate()
                }
            }
            val rows = claimSeqs(second)
            rows.size shouldBe 1
            (rows.single() > c1) shouldBe true
        } finally {
            first.close()
            second.close()
        }
    }

    private fun runtime(autoCommit: Boolean): Connection =
        NotificationDb.connect(NotificationDb.RUNTIME, NotificationDb.RUNTIME_PASSWORD).also {
            it.autoCommit =
                autoCommit
        }

    private fun insert(partition: String): Long =
        NotificationDb.superuser().use { c ->
            c
                .prepareStatement(
                    "insert into notification.work_queue (tenant_id, partition_key, lane, payload) " +
                        "values (?, ?, 'LIVE', '{}'::jsonb) returning seq",
                ).use { s ->
                    s.setObject(1, tenant)
                    s.setString(2, partition)
                    s.executeQuery().use { r ->
                        r.next()
                        r.getLong(1)
                    }
                }
        }

    private fun claim(c: Connection): List<String> =
        c.createStatement().use { s ->
            s
                .executeQuery(
                    "select claimed_partition from $CLAIM",
                ).use { r ->
                    buildList { while (r.next()) add(r.getString(1)) }
                }
        }

    private fun claimSeqs(c: Connection): List<Long> =
        c.createStatement().use { s ->
            s
                .executeQuery(
                    "select claimed_seq from $CLAIM",
                ).use { r ->
                    buildList { while (r.next()) add(r.getLong(1)) }
                }
        }
}

private const val CLAIM = "notification.claim_due('LIVE', null, now(), now() - interval '1 hour')"
