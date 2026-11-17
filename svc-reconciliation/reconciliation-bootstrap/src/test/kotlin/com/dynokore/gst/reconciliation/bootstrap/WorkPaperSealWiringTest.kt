package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.gst.reconciliation.adapters.ReconciliationDb
import com.dynokore.gst.reconciliation.application.workpaper.ArtefactRecordedPublisher
import com.dynokore.gst.reconciliation.application.workpaper.SealOutcome
import com.dynokore.gst.reconciliation.application.workpaper.SealWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.SealedWorkPapers
import com.dynokore.gst.reconciliation.application.workpaper.SourceWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperSource
import com.dynokore.gst.reconciliation.domain.workpaper.WorkPaperReference
import com.dynokore.gst.reconciliation.domain.workpaper.WorkPaperSeal
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-24183 through the composed service (TPL-18, P109): the seal and the publish run in one transaction over the
 * real outbox, the retention is configuration with no default, and an unconfigured store fails closed.
 */
class WorkPaperSealWiringTest {
    private val actor = "svc:reconciliation-svc"
    private val clock = Clock.fixed(Instant.parse("2026-11-18T01:00:00Z"), ZoneOffset.UTC)
    private val b1 = "{\"box\":1}".toByteArray()
    private val h1 = WorkPaperSeal.of(b1).sha256Hex

    private class FakeStore : ObjectStore {
        class Put(
            val key: String,
            val bytes: ByteArray,
            val retainUntil: Instant,
        )

        val puts = mutableListOf<Put>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            puts += Put(key.value, bytes, retainUntil)
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray = throw UnsupportedOperationException()
    }

    private fun runner(
        dir: Path,
        store: FakeStore?,
        retentionDays: String? = ReconciliationContext.RETENTION_DAYS,
    ): ApplicationContextRunner {
        val base = ReconciliationContext.online(dir, retentionDays).withBean(Clock::class.java, { clock })
        return if (store == null) base else base.withBean(ObjectStore::class.java, { store })
    }

    private fun ApplicationContext.sealAndPublish(
        tenant: UUID,
        entity: String,
        failAfter: Boolean,
    ): String =
        getBean(TenantScope::class.java).bind(TenantId(tenant), actor) {
            getBean(TransactionRunner::class.java).inTransaction {
                val reference = getBean(SealedWorkPapers::class.java).put(tenant.toString(), entity, "R1", 1, b1)
                getBean(ArtefactRecordedPublisher::class.java)
                    .publish(tenant.toString(), entity, SealWorkPaper.ARTEFACT_TYPE, reference, h1)
                check(!failAfter) { "forced failure after the publish" }
                reference
            }
        }

    private fun count(
        sql: String,
        vararg args: Any,
    ): Int =
        ReconciliationDb.superuser().use { connection ->
            connection.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun outboxRows(
        tenant: UUID,
        entity: String,
        reference: String,
    ) = count(
        "select count(*) from reconciliation.outbox " +
            "where tenant_id = ? and type = ? and topic = ? and entity_ref = ? " +
            "and data = jsonb_build_object('artefactType', ?::text, 'reference', ?::text, 'hash', ?::text)",
        tenant,
        "com.dynokore.gst.platform.EvidenceArtefactRecorded.v1",
        "gst.platform.evidence-artefact-recorded.v1",
        entity,
        "RECONCILIATION_WORK_PAPER",
        reference,
        h1,
    )

    private fun sealRows(tenant: UUID) =
        count("select count(*) from reconciliation.work_paper_seal where tenant_id = ?", tenant)

    private fun anyOutboxRows(tenant: UUID) =
        count("select count(*) from reconciliation.outbox where tenant_id = ?", tenant)

    @Test
    fun `INV-AC-24183 the seal and the publish commit together with one object put and one outbox row`(
        @TempDir dir: Path,
    ) {
        val tenant = UUID.randomUUID()
        val store = FakeStore()
        runner(dir, store).run { context ->
            val reference = context.sealAndPublish(tenant, "E1", failAfter = false)
            reference shouldBe WorkPaperReference.of(tenant.toString(), "R1", 1)
            store.puts.size shouldBe 1
            store.puts[0].key shouldBe reference
            store.puts[0].bytes.contentEquals(b1) shouldBe true
            store.puts[0].retainUntil shouldBe clock.instant().plus(Duration.ofDays(30))
            sealRows(tenant) shouldBe 1
            outboxRows(tenant, "E1", reference) shouldBe 1
            anyOutboxRows(tenant) shouldBe 1
            count(
                "select count(*) from reconciliation.work_paper_seal " +
                    "where tenant_id = ? and reference = ? and sha256 = ?",
                tenant,
                reference,
                h1,
            ) shouldBe 1
        }
    }

    @Test
    fun `INV-AC-24183 SealWorkPaper over the real adapters records the entity E1 and never the run id R1`(
        @TempDir dir: Path,
    ) {
        val tenant = UUID.randomUUID()
        val store = FakeStore()
        runner(dir, store).run { context ->
            val source = WorkPaperSource { _, runId -> if (runId == "R1") SourceWorkPaper("E1", b1) else null }
            val outcome =
                context.getBean(TenantScope::class.java).bind(TenantId(tenant), actor) {
                    context.getBean(TransactionRunner::class.java).inTransaction {
                        SealWorkPaper(
                            source,
                            context.getBean(SealedWorkPapers::class.java),
                            context.getBean(ArtefactRecordedPublisher::class.java),
                        ).execute(tenant.toString(), "R1")
                    }
                }
            outcome shouldBe SealOutcome.SEALED
            val reference = WorkPaperReference.of(tenant.toString(), "R1", 1)
            store.puts.single().key shouldBe reference
            count(
                "select count(*) from reconciliation.work_paper_seal " +
                    "where tenant_id = ? and run_id = ? and entity_ref = ?",
                tenant,
                "R1",
                "E1",
            ) shouldBe 1
            count(
                "select count(*) from reconciliation.work_paper_seal where tenant_id = ? and entity_ref = ?",
                tenant,
                "R1",
            ) shouldBe 0
            outboxRows(tenant, "E1", reference) shouldBe 1
            anyOutboxRows(tenant) shouldBe 1
        }
    }

    @Test
    fun `INV-AC-24183 a rolled-back transaction leaves neither a seal row nor an outbox row`(
        @TempDir dir: Path,
    ) {
        val tenant = UUID.randomUUID()
        runner(dir, FakeStore()).run { context ->
            shouldThrow<IllegalStateException> { context.sealAndPublish(tenant, "E1", failAfter = true) }
            sealRows(tenant) shouldBe 0
            anyOutboxRows(tenant) shouldBe 0
        }
    }

    @Test
    fun `INV-AC-24183 the context refuses to start when the retention property is missing`(
        @TempDir dir: Path,
    ) {
        runner(dir, FakeStore(), retentionDays = null).run { context ->
            context.startupFailure.shouldNotBeNull()
        }
    }

    @Test
    fun `INV-AC-24183 the context refuses to start when the retention is not positive`(
        @TempDir dir: Path,
    ) {
        listOf("0", "-5").forEach { value ->
            runner(dir, FakeStore(), retentionDays = value).run { context ->
                context.startupFailure.shouldNotBeNull()
            }
        }
    }

    @Test
    fun `INV-AC-24183 with the unconfigured object store a put throws and stores nothing`(
        @TempDir dir: Path,
    ) {
        val tenant = UUID.randomUUID()
        runner(dir, null).run { context ->
            context.startupFailure shouldBe null
            shouldThrow<IllegalStateException> { context.sealAndPublish(tenant, "E1", failAfter = false) }
            sealRows(tenant) shouldBe 0
            anyOutboxRows(tenant) shouldBe 0
        }
    }

    @Test
    fun `INV-AC-24183 the catalogue lets reconciliation emit EvidenceArtefactRecorded on its platform topic`(
        @TempDir dir: Path,
    ) {
        runner(dir, FakeStore()).run { context ->
            val entry =
                context
                    .getBean(EventCatalogue::class.java)
                    .entries()
                    .filter { it.eventType == "com.dynokore.gst.platform.EvidenceArtefactRecorded.v1" }
            entry.map { it.topic } shouldContainExactly listOf("gst.platform.evidence-artefact-recorded.v1")
            entry.single().producers shouldBe setOf("reconciliation-svc")
        }
    }
}
