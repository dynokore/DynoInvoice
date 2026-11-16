package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.RetentionPartitionFixture.CHAIN_TABLES
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID

/**
 * INV-AC-23796: the retention-partition fixture is a no-op on an unpartitioned chain table and, on a partitioned one,
 * creates the unit's partition through `evidence.create_retention_partition` (INV-T-22969).
 */
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

    /** A scratch schema whose four chain tables are plain tables again (the state before the partitioning). */
    private val plain =
        CHAIN_TABLES.joinToString(" ") {
            "drop table evidence.$it cascade; create table evidence.$it (id bigint not null, seq bigint not null);"
        }

    private fun <T> onPlainTables(block: (Connection) -> T): T {
        var result: T? = null
        EvidenceDb.withScratchDatabase("select 1", plain) { url ->
            EvidenceDb.superuserOf(url).use { c -> result = block(c) }
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun `INV-AC-23796 ensureRetentionPartition changes nothing on each unpartitioned chain table`() {
        onPlainTables { c ->
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
        onPlainTables { c ->
            val before = classRows(c)
            RetentionPartitionFixture.ensureRetentionPartitions(c, tenant, "E1", "P1")
            classRows(c) shouldBe before
        }
    }

    @Test
    fun `INV-AC-23796 the DSLContext overload is a no-op on an unpartitioned table`() {
        onPlainTables { c ->
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
    fun `INV-AC-23796 a partitioned table gets the partition of the unit once and a second call creates none`() {
        EvidenceDb.superuser().use { c ->
            val unit = UUID.randomUUID()
            CHAIN_TABLES.forEach { table ->
                partitionedRows(c, table) shouldBe 1L
                RetentionPartitionFixture.ensureRetentionPartition(c, table, unit, "E-fixture", "P-fixture")
                val after = classRows(c)
                RetentionPartitionFixture.ensureRetentionPartition(c, table, unit, "E-fixture", "P-fixture")
                classRows(c) shouldBe after
                count(
                    c,
                    "select count(*) from evidence.retention_partition_member m where m.tenant_id = '$unit' " +
                        "and m.chain_table = '$table'",
                ) shouldBe 1L
            }
        }
    }
}
