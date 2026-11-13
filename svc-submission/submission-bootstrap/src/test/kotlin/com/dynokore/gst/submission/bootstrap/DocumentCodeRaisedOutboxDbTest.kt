package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Outbox
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
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** `DocumentCodeRaised` through the real catalogue, code store and outbox, in the caller's transaction (INV-AC-21088). */
class DocumentCodeRaisedOutboxDbTest {
    private val mapper = JsonMapper.builder().build()
    private val type = "com.dynokore.gst.submission.DocumentCodeRaised.v1"
    private val topic = "gst.submission.document-code-raised.v1"
    private val instant = Instant.parse("2026-11-12T03:00:00Z")

    @Test
    fun `INV-AC-21088 the topic map and the raised rows, outbox rows and rollback`(
        @TempDir dir: Path,
    ) = SubmissionContext.online(dir).run { context ->
        SubmissionEvents.topicByType[type] shouldBe topic
        context.getBean(CodeRaising::class.java) // the bootstrap builds it (ArrivalConfiguration)

        val tenants = context.getBean(TenantContext::class.java)
        val runner = context.getBean(TransactionRunner::class.java)
        val dsl = context.getBean(DSLContext::class.java)
        val raising =
            CodeRaising(
                context.getBean(ErrorCatalogue::class.java),
                context.getBean(RaisedCodes::class.java),
                context.getBean(CodeSignals::class.java),
                Clock.fixed(instant, ZoneOffset.UTC),
            )
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val d1 = UUID.randomUUID().toString()
        val d2 = UUID.randomUUID().toString()

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
        ) = asTenant(tenant) {
            dsl
                .fetch("select code, raised_at, version from submission.raised_code where doc_uuid = ? order by seq", doc)
                .map { Triple(it.get(0, String::class.java), it.get(1, java.time.OffsetDateTime::class.java), it.get(2)) }
        }

        fun events(
            tenant: UUID,
            doc: String,
        ) = asTenant(tenant) {
            dsl
                .selectFrom(Outbox.OUTBOX)
                .where(Outbox.OUTBOX.DOC_UUID.eq(UUID.fromString(doc)))
                .and(Outbox.OUTBOX.TYPE.eq(type))
                .orderBy(Outbox.OUTBOX.ID)
                .fetch()
                .toList()
        }

        asTenant(a) { raise(d1, 1, "SUB-010", "PER-007") } shouldBe 2
        rows(a, d1).map { it.first } shouldBe listOf("SUB-010", "PER-007")
        rows(a, d1).map { it.second.toInstant() }.toSet() shouldBe setOf(instant)
        val first = events(a, d1)
        first.size shouldBe 2
        first.forEach {
            it.type shouldBe type
            it.topic shouldBe topic
            it.subject shouldBe d1
        }
        val payloads =
            first.map {
                @Suppress("UNCHECKED_CAST")
                mapper.readValue(checkNotNull(it.data).data(), Map::class.java) as Map<String, Any?>
            }
        listOf(ErrorCode.SUB_010, ErrorCode.PER_007).zip(payloads).forEach { (entry, payload) ->
            val expected =
                linkedMapOf<String, Any?>(
                    "docUuid" to d1,
                    "version" to 1,
                    "entityRef" to "E-001",
                    "periodRef" to "2026-Q1",
                    "code" to entry.code,
                )
            entry.owner?.let { expected["remediationOwner"] = it }
            entry.disposition?.let { expected["disposition"] = it.name }
            payload shouldBe expected
        }

        asTenant(a) { raise(d1, 1, "SUB-010", "PER-007") } shouldBe 0
        rows(a, d1).size shouldBe 2
        events(a, d1).size shouldBe 2

        asTenant(a) { raise(d1, 2, "SUB-010") } shouldBe 1
        rows(a, d1).size shouldBe 3
        events(a, d1).size shouldBe 3

        runCatching { asTenant(a) { raise(d2, 1, "SUB-010").also { error("rolled back") } } }.isFailure shouldBe true
        rows(a, d2) shouldBe emptyList()
        events(a, d2) shouldBe emptyList()

        rows(b, d1) shouldBe emptyList()
        events(b, d1) shouldBe emptyList()

        runCatching { asTenant(a) { dsl.execute("update submission.raised_code set code = 'X-1'") } }
            .isFailure shouldBe true
        runCatching { asTenant(a) { dsl.execute("delete from submission.raised_code") } }.isFailure shouldBe true
        rows(a, d1).size shouldBe 3
    }
}
