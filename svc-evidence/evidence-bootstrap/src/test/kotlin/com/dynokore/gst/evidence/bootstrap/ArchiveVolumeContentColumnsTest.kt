package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection

/**
 * The content-column gate of the archive volume (INV-Q-12681 A): the columns of schema `evidence` whose comment is
 * exactly `class: content` (the data classification is the source of truth, NIST SP 800-53 RA-2) equal, in both
 * directions, the covered list the volume sum reads plus the excluded list. A new marked column fails; so does a
 * vanished one.
 */
class ArchiveVolumeContentColumnsTest {
    private val covered =
        setOf(
            "decision_trace.content",
            "evidence_payload.content",
            "acknowledgement.content",
            "audit_log.content",
            "pending_record.event_json",
        )

    /** Excluded from the retained volume, each with its reason. */
    private val excluded =
        mapOf(
            "outbox.data" to
                "transport copy of an event whose content is held by its owner and is deleted once published",
            "event_dead_letter.raw" to "a transient failed delivery, not retained evidence",
        )

    private fun marked(connection: Connection): Set<String> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    """
                    select c.relname || '.' || a.attname
                    from pg_description d
                    join pg_class c on c.oid = d.objoid
                    join pg_namespace n on n.oid = c.relnamespace
                    join pg_attribute a on a.attrelid = c.oid and a.attnum = d.objsubid
                    where d.classoid = 'pg_class'::regclass and d.objsubid > 0 and n.nspname = 'evidence'
                      and d.description = 'class: content'
                    """.trimIndent(),
                ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toSet() }
        }

    /** The names that differ between the catalogue and the lists, each side tagged. */
    private fun drift(
        connection: Connection,
        coveredList: Set<String> = covered,
        excludedList: Set<String> = excluded.keys,
    ): Set<String> {
        val expected = coveredList + excludedList
        val actual = marked(connection)
        return (actual - expected).map { "unlisted:$it" }.toSet() + (expected - actual).map { "vanished:$it" }.toSet()
    }

    private fun <T> rolledBack(block: (Connection) -> T): T =
        EvidenceDb.superuser().use { connection ->
            connection.autoCommit = false
            try {
                block(connection)
            } finally {
                connection.rollback()
            }
        }

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    @Test
    fun `the class content columns equal the covered and excluded lists`() {
        EvidenceDb.superuser().use { drift(it).shouldBeEmpty() }
    }

    @Test
    fun `every excluded entry names its reason`() {
        excluded.values.forEach { it.isNotBlank() shouldBe true }
    }

    @Test
    fun `a marked extra column fails the gate`() {
        rolledBack { connection ->
            connection.run("comment on column evidence.inbox.event_id is 'class: content'")
            drift(connection) shouldBe setOf("unlisted:inbox.event_id")
        }
    }

    @Test
    fun `a comment with a trailing space is not the marker and the covered column vanishes`() {
        rolledBack { connection ->
            connection.run("comment on column evidence.decision_trace.content is 'class: content '")
            drift(connection) shouldBe setOf("vanished:decision_trace.content")
        }
    }

    @Test
    fun `a covered list without pending_record event_json fails the gate`() {
        EvidenceDb.superuser().use { connection ->
            drift(connection, coveredList = covered - "pending_record.event_json") shouldBe
                setOf("unlisted:pending_record.event_json")
        }
    }

    @Test
    fun `an excluded entry with a look-alike name fails the gate`() {
        EvidenceDb.superuser().use { connection ->
            val drifted = drift(connection, excludedList = setOf("outbox.dat", "event_dead_letter.raw"))
            drifted shouldContain "unlisted:outbox.data"
            drifted shouldContain "vanished:outbox.dat"
        }
    }

    @Test
    fun `a vanished marker fails the gate`() {
        rolledBack { connection ->
            connection.run("comment on column evidence.outbox.data is null")
            drift(connection) shouldBe setOf("vanished:outbox.data")
        }
    }
}
