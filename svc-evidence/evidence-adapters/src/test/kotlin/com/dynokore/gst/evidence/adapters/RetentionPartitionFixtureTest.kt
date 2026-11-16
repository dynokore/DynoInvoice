package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.RetentionPartitionFixture.CHAIN_TABLES
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/** INV-AC-23796: the retention-partition fixture is a no-op on an unpartitioned chain table and fails closed. */
class RetentionPartitionFixtureTest {
    private val tenant = UUID.fromString("0b1f2c3d-0000-4000-8000-00000000f001")

    private fun count(
        c: Connection,
        sql: String,
    ): Long =
        c.createStatement().use { s ->
            s.executeQuery(sql).use {
                it.next()
                it.getLong(1)
            }
        }

    private fun classRows(c: Connection) =
        count(
            c,
            "select count(*) from pg_class where relnamespace = 'evidence'::regnamespace",
        )

    private fun partitionedRows(
        c: Connection,
        table: String,
    ) = count(c, "select count(*) from pg_partitioned_table where partrelid = 'evidence.$table'::regclass")

    @Test
    fun `INV-AC-23796 the chain tables are decision_trace, evidence_payload, acknowledgement, audit_log in order`() {
        CHAIN_TABLES shouldBe listOf("decision_trace", "evidence_payload", "acknowledgement", "audit_log")
    }

    @Test
    fun `INV-AC-23796 ensureRetentionPartition changes nothing on each unpartitioned chain table`() {
        EvidenceDb.superuser().use { c ->
            CHAIN_TABLES.forEach { table ->
                val before = classRows(c)
                RetentionPartitionFixture.ensureRetentionPartition(c, table, tenant, "E1", "P1")
                classRows(c) shouldBe before
                partitionedRows(c, table) shouldBe 0L
            }
        }
    }

    @Test
    fun `INV-AC-23796 ensureRetentionPartitions covers the four tables and changes nothing`() {
        EvidenceDb.superuser().use { c ->
            val before = classRows(c)
            RetentionPartitionFixture.ensureRetentionPartitions(c, tenant, "E1", "P1")
            classRows(c) shouldBe before
        }
    }

    @Test
    fun `INV-AC-23796 the DSLContext overload is a no-op on an unpartitioned table`() {
        EvidenceDb.superuser().use { c ->
            val before = classRows(c)
            val dsl =
                org.jooq.impl.DSL
                    .using(c, org.jooq.SQLDialect.POSTGRES)
            RetentionPartitionFixture.ensureRetentionPartition(dsl, "audit_log", tenant, "E1", "P1")
            classRows(c) shouldBe before
        }
    }

    @Test
    fun `INV-AC-23796 a name that is not exactly a chain table is refused and nothing changes`() {
        EvidenceDb.superuser().use { c ->
            listOf("audit_log_x", "evidence.audit_log", "AUDIT_LOG", "audit_log ", "").forEach { name ->
                val before = classRows(c)
                shouldThrow<IllegalArgumentException> {
                    RetentionPartitionFixture.ensureRetentionPartition(c, name, tenant, "E1", "P1")
                }
                classRows(c) shouldBe before
            }
        }
    }

    @Test
    fun `INV-AC-23796 a partitioned audit_log makes the call throw IllegalStateException naming the table`() {
        val probe =
            "drop table evidence.audit_log cascade; " +
                "create table evidence.audit_log (id bigint not null, seq bigint not null) partition by range (seq);"
        EvidenceDb.withScratchDatabase("select 1", probe) { url ->
            EvidenceDb.superuserOf(url).use { c ->
                partitionedRows(c, "audit_log") shouldBe 1L
                val before = classRows(c)
                val e =
                    shouldThrow<IllegalStateException> {
                        RetentionPartitionFixture.ensureRetentionPartition(c, "audit_log", tenant, "E1", "P1")
                    }
                e.message!! shouldContain "audit_log"
                classRows(c) shouldBe before
                // the other three tables of the scratch database are still not partitioned
                RetentionPartitionFixture.ensureRetentionPartition(c, "decision_trace", tenant, "E1", "P1")
            }
        }
    }
}
