package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.persistence.JooqStoredContentBytes
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID

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

    // ---- pending record attribution (INV-Q-12744 A): a pending record counts for the entity of its locator ----

    private fun seedLocator(
        tenant: UUID,
        doc: UUID,
        version: Int,
        entity: String,
    ) = EvidenceDb.superuser().use {
        it.run(
            "insert into evidence.document_locator (tenant_id, doc_uuid, version, entity_ref, period_ref, " +
                "transaction_date, trace_reference, source_event_id, recorded_at) values ('$tenant', '$doc', " +
                "$version, '$entity', '2026-Q3', current_date, 't', 'loc-$doc-$version', now())",
        )
    }

    private fun seedPendingRecord(
        tenant: UUID,
        doc: UUID,
    ) = EvidenceDb.superuser().use {
        it.run(
            "insert into evidence.pending_record (tenant_id, source_event_id, kind, doc_uuid, version, event_json, " +
                "arrived_at) values ('$tenant', 'pend-$doc', 'DECISION_TRACE', '$doc', 1, repeat('b', 50), now())",
        )
    }

    private fun seedApplied(
        tenant: UUID,
        doc: UUID,
        entity: String,
    ) = EvidenceDb.superuser().use {
        it.run(
            "insert into evidence.applied_record (tenant_id, source_event_id, kind, entity_ref, period_ref, seq, " +
                "applied_at) values ('$tenant', 'pend-$doc', 'DECISION_TRACE', '$entity', '2026-Q3', 1, now())",
        )
    }

    private fun bytesOf(
        dir: Path,
        tenant: UUID,
        vararg entities: String,
    ): List<Long> {
        val result = mutableListOf<Long>()
        EvidenceContext.online(dir).run { context ->
            val scope = context.getBean(TenantScope::class.java)
            val runner = context.getBean(TransactionRunner::class.java)
            val sum = JooqStoredContentBytes(context.getBean(DSLContext::class.java))
            entities.mapTo(result) { e ->
                scope.bind(TenantId(tenant), "svc:evidence-svc") {
                    runner.inTransaction { sum.bytes(TenantId(tenant), e) }
                }
            }
        }
        return result
    }

    private fun listed(tenant: UUID): Set<String> =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("select tenant_id, entity_ref from evidence.archive_volume_entities()").use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }
                        .filter { it.first == tenant.toString() }
                        .map { it.second }
                        .toSet()
                }
            }
        }

    @Test
    fun `a pending record with no locator counts for no entity`(
        @TempDir dir: Path,
    ) {
        val tenant = UUID.randomUUID()
        seedPendingRecord(tenant, UUID.randomUUID())
        bytesOf(dir, tenant, "E-1", "E-2", "") shouldBe listOf(0L, 0L, 0L)
        listed(tenant) shouldBe emptySet()
    }

    @Test
    fun `a pending record counts for the entity of its locator only`(
        @TempDir dir: Path,
    ) {
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        seedLocator(tenant, doc, 1, "E-1")
        seedPendingRecord(tenant, doc)
        bytesOf(dir, tenant, "E-1", "E-2") shouldBe listOf(50L, 0L)
        listed(tenant) shouldBe setOf("E-1")
    }

    @Test
    fun `a locator only at another version counts for nobody`(
        @TempDir dir: Path,
    ) {
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        seedLocator(tenant, doc, 2, "E-1")
        seedPendingRecord(tenant, doc)
        bytesOf(dir, tenant, "E-1") shouldBe listOf(0L)
        listed(tenant) shouldBe emptySet()
    }

    @Test
    fun `a locator of another tenant with the same document and version gives the record no pair`(
        @TempDir dir: Path,
    ) {
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        seedLocator(UUID.randomUUID(), doc, 1, "E-1")
        seedPendingRecord(tenant, doc)
        listed(tenant) shouldBe emptySet()
        bytesOf(dir, tenant, "E-1") shouldBe listOf(0L)
    }

    @Test
    fun `an applied record of the pending event does not change the sum`(
        @TempDir dir: Path,
    ) {
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        seedLocator(tenant, doc, 1, "E-1")
        seedPendingRecord(tenant, doc)
        seedApplied(tenant, doc, "E-1")
        bytesOf(dir, tenant, "E-1") shouldBe listOf(50L)
    }
}
