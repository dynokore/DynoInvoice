package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.domain.ordering.InFlight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** The in-flight partial unique index `submission_in_flight_uq` (INV-T-7319, INV-REQ-571). */
class InFlightIndexMigrationTest {
    private class Meta(
        val table: String,
        val unique: Boolean,
        val columns: List<String>,
        val predicate: String,
    )

    private fun meta(c: Connection): Meta =
        c.createStatement().use { st ->
            st
                .executeQuery(
                    "select t.relname, i.indisunique, pg_get_expr(i.indpred, i.indrelid), " +
                        "(select string_agg(a.attname, ',' order by k.ord) from unnest(i.indkey) with ordinality " +
                        "as k(attnum, ord) join pg_attribute a on a.attrelid = i.indrelid and a.attnum = k.attnum) " +
                        "from pg_index i join pg_class x on x.oid = i.indexrelid " +
                        "join pg_class t on t.oid = i.indrelid " +
                        "where x.relname = '${InFlight.INDEX_NAME}'",
                ).use { rs ->
                    check(rs.next()) { "index ${InFlight.INDEX_NAME} does not exist" }
                    Meta(rs.getString(1), rs.getBoolean(2), rs.getString(4).split(","), rs.getString(3))
                }
        }

    private fun insertPair(
        first: String,
        second: String,
    ): List<String?> {
        val doc = UUID.randomUUID()
        val tenant = UUID.randomUUID()
        return SubmissionDb.superuser().use { c ->
            try {
                listOf(first to 1, second to 2).map { (state, version) ->
                    sqlState {
                        c.createStatement().use {
                            it.execute(
                                "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                                    "values ('$tenant', '$doc', $version, '$state', 'E-001')",
                            )
                        }
                    }
                }
            } finally {
                c.createStatement().use { it.execute("delete from submission.submission where doc_uuid = '$doc'") }
            }
        }
    }

    private fun sqlState(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? SQLException)?.sqlState

    private fun constraintOf(
        first: String,
        second: String,
    ): String? {
        val doc = UUID.randomUUID()
        val tenant = UUID.randomUUID()
        return SubmissionDb.superuser().use { c ->
            try {
                c.createStatement().use {
                    it.execute(
                        "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                            "values ('$tenant', '$doc', 1, '$first', 'E-001')",
                    )
                }
                val failure =
                    runCatching {
                        c.createStatement().use {
                            it.execute(
                                "insert into submission.submission (tenant_id, doc_uuid, version, state, entity_ref) " +
                                    "values ('$tenant', '$doc', 2, '$second', 'E-001')",
                            )
                        }
                    }.exceptionOrNull() as? SQLException
                failure?.let { Regex("unique constraint \"([^\"]+)\"").find(it.message.orEmpty())?.groupValues?.get(1) }
            } finally {
                c.createStatement().use { it.execute("delete from submission.submission where doc_uuid = '$doc'") }
            }
        }
    }

    @Test
    fun `the index is unique, on the single column doc_uuid of submission`() {
        SubmissionDb.superuser().use { c ->
            val m = meta(c)
            m.table shouldBe "submission"
            m.unique shouldBe true
            m.columns shouldBe listOf("doc_uuid")
        }
    }

    @Test
    fun `the predicate holds exactly the four in-flight state names`() {
        SubmissionDb.superuser().use { c ->
            val names = Regex("'([A-Z_]+)'").findAll(meta(c).predicate).map { it.groupValues[1] }.toList()
            names.size shouldBe InFlight.NAMES.size
            names.toSet() shouldBe InFlight.NAMES
            InFlight.NAMES shouldBe setOf("QUEUED", "SENT", "SENT_UNCONFIRMED", "RETRY")
        }
    }

    @Test
    fun `two in-flight versions of one Doc UUID fail with 23505 and the index name`() {
        insertPair("QUEUED", "RETRY") shouldBe listOf(null, "23505")
        insertPair("SENT", "SENT_UNCONFIRMED") shouldBe listOf(null, "23505")
        constraintOf("QUEUED", "RETRY") shouldBe InFlight.INDEX_NAME
        constraintOf("SENT", "SENT_UNCONFIRMED") shouldBe InFlight.INDEX_NAME
    }

    @Test
    fun `one in-flight version beside a version outside flight succeeds`() {
        insertPair("READY", "QUEUED") shouldBe listOf(null, null)
        insertPair("ACKNOWLEDGED", "QUEUED") shouldBe listOf(null, null)
        insertPair("READY", "READY") shouldBe listOf(null, null)
        insertPair("CANCELLED", "SENT") shouldBe listOf(null, null)
    }
}
