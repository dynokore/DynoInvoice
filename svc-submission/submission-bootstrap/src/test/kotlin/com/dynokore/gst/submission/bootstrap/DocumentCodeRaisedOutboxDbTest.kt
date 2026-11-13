package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.records.OutboxRecord
import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.gst.submission.application.arrival.CodeSignals
import com.dynokore.gst.submission.application.arrival.RaisedCodes
import com.dynokore.gst.submission.application.queue.ErrorCatalogue
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** `DocumentCodeRaised` through the real catalogue, code store and outbox, in the caller's transaction (INV-AC-21088). */
class DocumentCodeRaisedOutboxDbTest {
    private val type = "com.dynokore.gst.submission.DocumentCodeRaised.v1"
    private val topic = "gst.submission.document-code-raised.v1"
    private val instant = Instant.parse("2026-11-12T03:00:00Z")

    private inner class Fixture(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val a: UUID = UUID.randomUUID()
        val b: UUID = UUID.randomUUID()
        val raising =
            CodeRaising(
                context.getBean(ErrorCatalogue::class.java),
                context.getBean(RaisedCodes::class.java),
                context.getBean(CodeSignals::class.java),
                Clock.fixed(instant, ZoneOffset.UTC),
            )

        fun <T> asTenant(
            tenant: UUID,
            block: () -> T,
        ): T = tenants.bind(TenantId(tenant), "svc:submission-svc") { runner.inTransaction(block) }

        fun raise(
            doc: String,
            version: Int,
            vararg codes: String,
        ) = raising.raise(TenantId(a), doc, version, "E-001", "2026-Q1", codes.toList())

        fun rows(
            tenant: UUID,
            doc: String,
        ): List<Pair<String, Instant>> =
            asTenant(tenant) {
                dsl
                    .fetch("select code, raised_at from submission.raised_code where doc_uuid = ? order by seq", doc)
                    .map { it.get(0, String::class.java) to it.get(1, OffsetDateTime::class.java).toInstant() }
            }

        fun events(
            tenant: UUID,
            doc: String,
        ): List<OutboxRecord> =
            asTenant(tenant) {
                dsl
                    .selectFrom(Outbox.OUTBOX)
                    .where(Outbox.OUTBOX.DOC_UUID.eq(UUID.fromString(doc)))
                    .and(Outbox.OUTBOX.TYPE.eq(type))
                    .orderBy(Outbox.OUTBOX.ID)
                    .fetch()
                    .toList()
            }
    }

    private fun run(
        dir: Path,
        block: Fixture.() -> Unit,
    ) = SubmissionContext.online(dir).run { context -> Fixture(context).block() }

    private fun payload(row: OutboxRecord): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val json = JsonMapper.builder().build()
        return json.readValue(checkNotNull(row.data).data(), Map::class.java) as Map<String, Any?>
    }

    @Test
    fun `INV-AC-21088 the topic map and the bean of the bootstrap`(
        @TempDir dir: Path,
    ) = SubmissionContext.online(dir).run { context ->
        SubmissionEvents.topicByType[type] shouldBe topic
        context.getBean(CodeRaising::class.java)
    }

    @Test
    fun `INV-AC-21088 the first call stores two rows and stages two events with the catalogue members`(
        @TempDir dir: Path,
    ) = run(dir) {
        val doc = UUID.randomUUID().toString()
        asTenant(a) { raise(doc, 1, "SUB-010", "PER-007") } shouldBe 2

        rows(a, doc) shouldBe listOf("SUB-010" to instant, "PER-007" to instant)
        val staged = events(a, doc)
        staged.map { it.type to it.topic } shouldBe listOf(type to topic, type to topic)
        staged.map { it.subject } shouldBe listOf(doc, doc)
        listOf(ErrorCode.SUB_010, ErrorCode.PER_007).zip(staged.map { payload(it) }).forEach { (entry, payload) ->
            val expected =
                linkedMapOf<String, Any?>(
                    "docUuid" to doc,
                    "version" to 1,
                    "entityRef" to "E-001",
                    "periodRef" to "2026-Q1",
                    "code" to entry.code,
                )
            expected["remediationOwner"] = entry.owner
            entry.disposition?.let { expected["disposition"] = it.name }
            payload shouldBe expected
        }
    }

    @Test
    fun `INV-AC-21088 a repeat adds nothing and a new version adds one row and one event`(
        @TempDir dir: Path,
    ) = run(dir) {
        val doc = UUID.randomUUID().toString()
        asTenant(a) { raise(doc, 1, "SUB-010", "PER-007") } shouldBe 2

        asTenant(a) { raise(doc, 1, "SUB-010", "PER-007") } shouldBe 0
        rows(a, doc).size shouldBe 2
        events(a, doc).size shouldBe 2

        asTenant(a) { raise(doc, 2, "SUB-010") } shouldBe 1
        rows(a, doc).size shouldBe 3
        events(a, doc).size shouldBe 3
    }

    @Test
    fun `INV-AC-21088 a rolled back call leaves no row and no event and tenant B reads none`(
        @TempDir dir: Path,
    ) = run(dir) {
        val kept = UUID.randomUUID().toString()
        val lost = UUID.randomUUID().toString()
        asTenant(a) { raise(kept, 1, "SUB-010") } shouldBe 1

        runCatching { asTenant(a) { raise(lost, 1, "SUB-010").also { error("rolled back") } } }
            .isFailure shouldBe true
        rows(a, lost) shouldBe emptyList()
        events(a, lost) shouldBe emptyList()

        rows(b, kept) shouldBe emptyList()
        events(b, kept) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21088 update and delete of a raised code are refused to the runtime role`(
        @TempDir dir: Path,
    ) = run(dir) {
        val doc = UUID.randomUUID().toString()
        asTenant(a) { raise(doc, 1, "SUB-010") } shouldBe 1

        runCatching { asTenant(a) { dsl.execute("update submission.raised_code set code = 'X-1'") } }
            .isFailure shouldBe true
        runCatching { asTenant(a) { dsl.execute("delete from submission.raised_code") } }.isFailure shouldBe true
        rows(a, doc).size shouldBe 1
    }
}
