package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.persistence.JooqSealedWorkPapers
import com.dynokore.gst.reconciliation.adapters.persistence.JooqWorkPaperSource
import com.dynokore.gst.reconciliation.application.workpaper.AnnotateVariance
import com.dynokore.gst.reconciliation.application.workpaper.AnnotationResult
import com.dynokore.gst.reconciliation.application.workpaper.ArtefactRecordedPublisher
import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.SealOutcome
import com.dynokore.gst.reconciliation.application.workpaper.SealWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.SourceWorkPaper
import com.dynokore.gst.reconciliation.contract.model.WorkPaper
import com.dynokore.gst.reconciliation.contract.model.WorkPaperHeader
import com.dynokore.gst.reconciliation.domain.boxes.UnmappedLine
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * INV-AC-24381 (INV-T-24382, INV-REQ-656) over PostgreSQL: the work-paper source gives deterministic canonical bytes of
 * the work-paper of a run for the entity of the run, tenant-isolated, and SealWorkPaper seals them once.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class WorkPaperSourceDbTest : RunStoreDbFixture() {
    private val clock = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC)
    private val json: JsonMapper = JsonMapper.builder().findAndAddModules().build()

    private class RecordingStore : ObjectStore {
        val puts = mutableListOf<Pair<String, ByteArray>>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            puts += key.value to bytes
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray = throw UnsupportedOperationException()
    }

    private val getWorkPaper =
        GetWorkPaper(reader, LazyRunAnnotations { session.dsl() }, LazyRunAttestations { session.dsl() })
    private val source = JooqWorkPaperSource(getWorkPaper)

    /** Box 1 carries an unexplained residual and is annotated; Box 2 keeps its residual. */
    private fun storedRun(tenant: UUID): String {
        storeReturn(tenant)
        val lines =
            listOf(
                line(comparison("1", "100.00", "90.00", "10.00"), "10.00"),
                line(comparison("2", "50.5", "47.5", "3"), "3"),
            )
        val unmapped = listOf(UnmappedLine(UUID.randomUUID().toString(), 1, 3, "XX"))
        val run = record(tenant, "2026-11-19T09:00:00Z", outcome(lines = lines).copy(unmappedLines = unmapped)).runId
        val annotated =
            asTenant(tenant) {
                session.runner.inTransaction {
                    AnnotateVariance(
                        reader,
                        LazyRunAnnotations { session.dsl() },
                        LazyRunAttestations { session.dsl() },
                        clock,
                    ).execute(tenant.toString(), run, "1", "TIMING_DIFFERENCE", listOf("DOC-1"), USER)
                }
            }
        (annotated is AnnotationResult.Accepted) shouldBe true
        return run
    }

    private fun load(
        tenant: UUID,
        runId: String,
    ): SourceWorkPaper? = asTenant(tenant) { session.runner.inTransaction { source.load(tenant.toString(), runId) } }

    private fun seal(
        tenant: UUID,
        runId: String,
        store: RecordingStore,
    ): SealOutcome =
        asTenant(tenant) {
            session.runner.inTransaction {
                SealWorkPaper(
                    source,
                    JooqSealedWorkPapers(session.dsl(), store, clock, 30),
                    ArtefactRecordedPublisher { _, _, _, _, _ -> },
                ).execute(tenant.toString(), runId)
            }
        }

    private fun attest(
        tenant: UUID,
        run: String,
        sole: Boolean = false,
    ) = ReconciliationDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into reconciliation.work_paper_attestation (tenant_id, run_id, attested_by, attested_at" +
                    (if (sole) ", sole_attester_acknowledged_by, sole_attester_acknowledged_on" else "") +
                    ") values (?, ?, 'user-A', '2026-11-19T09:30:00Z'" +
                    (if (sole) ", 'user-A', '2026-11-19'" else "") + ")",
            ).use {
                it.setObject(1, tenant)
                it.setString(2, run)
                it.executeUpdate()
            }
    }

    private fun sortedKeys(node: JsonNode): Boolean =
        when {
            node.isObject -> {
                val names = node.propertyNames().toList()
                names == names.sorted() && node.properties().all { sortedKeys(it.value) }
            }

            node.isArray -> {
                node.all { sortedKeys(it) }
            }

            else -> {
                true
            }
        }

    @Test
    fun `INV-AC-24381 load returns the entity E1 never the run id and bytes that parse to the contract work-paper`() {
        val t1 = UUID.randomUUID()
        val run = storedRun(t1)
        val loaded = load(t1, run)!!
        loaded.entityRef shouldBe "E1"
        (loaded.entityRef == run) shouldBe false
        val paper = json.readValue(loaded.canonicalJson, WorkPaper::class.java)
        paper.runId shouldBe UUID.fromString(run)
        paper.header.entityRef shouldBe "E1"
        paper.header.periodRef shouldBe "P1"
        paper.header.returnVersion shouldBe 2
        paper.header.returnForm shouldBe WorkPaperHeader.ReturnForm.F5
        paper.header.preparedAt.toInstant() shouldBe Instant.parse("2026-11-19T09:00:00Z")
        paper.boxes.map { it.box } shouldBe listOf("1", "2")
        paper.unexplainedResidual.compareTo(BigDecimal("3.00")) shouldBe 0
        paper.supportingDocuments shouldBe listOf("DOC-1")
        val tree = json.readTree(loaded.canonicalJson)
        val box1 = tree["boxes"][0]
        box1["varianceClasses"].size() shouldBe 1
        box1["varianceClasses"][0]["varianceClass"].asString() shouldBe "TIMING_DIFFERENCE"
        box1["varianceClasses"][0]["amount"].asString() shouldBe "10.00"
        tree["boxes"][1]["submittedAmount"].asString() shouldBe "50.50"
        tree["boxes"][1]["variance"].asString() shouldBe "3.00"
        tree["unexplainedResidual"].asString() shouldBe "3.00"
        tree["header"]["preparedAt"].asString() shouldBe "2026-11-19T09:00:00Z"
        tree["header"]["returnVersion"].asInt() shouldBe 2
        tree.has("attestation") shouldBe false
    }

    @Test
    fun `INV-AC-24381 two loads return byte-for-byte equal arrays with sorted keys and no whitespace`() {
        val t1 = UUID.randomUUID()
        val run = storedRun(t1)
        val first = load(t1, run)!!.canonicalJson
        val second = load(t1, run)!!.canonicalJson
        first.contentEquals(second) shouldBe true
        sortedKeys(json.readTree(first)) shouldBe true
        val text = String(first, Charsets.UTF_8)
        text.startsWith("{\"boxes\":[{\"box\":\"1\",\"filedAmount\":\"90.00\"") shouldBe true
        text.contains("\": ") shouldBe false
        text.contains(", ") shouldBe false
        text.contains("\n") shouldBe false
    }

    @Test
    fun `INV-AC-24381 the attestation member is present only when the attestation read side holds one`() {
        val t1 = UUID.randomUUID()
        val run = storedRun(t1)
        json.readTree(load(t1, run)!!.canonicalJson).has("attestation") shouldBe false
        attest(t1, run)
        val tree = json.readTree(load(t1, run)!!.canonicalJson)
        tree["attestation"]["attestedBy"].asString() shouldBe "user-A"
        tree["attestation"]["attestedAt"].asString() shouldBe "2026-11-19T09:30:00Z"
        sortedKeys(tree) shouldBe true
    }

    @Test
    fun `INV-AC-1975 the sealed work-paper states the sole attester when the attestation holds one`() {
        val t1 = UUID.randomUUID()
        val run = storedRun(t1)
        attest(t1, run, sole = true)
        val first = load(t1, run)!!.canonicalJson
        val sole = json.readTree(first)["attestation"]["soleAttester"]
        sole.propertyNames().toList() shouldBe listOf("acknowledgedBy", "acknowledgedOn")
        sole["acknowledgedBy"].asString() shouldBe "user-A"
        sole["acknowledgedOn"].asString() shouldBe "2026-11-19"
        first.contentEquals(load(t1, run)!!.canonicalJson) shouldBe true
        sortedKeys(json.readTree(first)) shouldBe true
    }

    @Test
    fun `INV-AC-1975 a two-person attestation seals exactly attestedAt and attestedBy`() {
        val t1 = UUID.randomUUID()
        val run = storedRun(t1)
        attest(t1, run)
        val text = String(load(t1, run)!!.canonicalJson, Charsets.UTF_8)
        val expected = "\"attestation\":{\"attestedAt\":\"2026-11-19T09:30:00Z\",\"attestedBy\":\"user-A\"}"
        text.contains(expected) shouldBe true
        text.contains("soleAttester") shouldBe false
    }

    @Test
    fun `INV-AC-24381 an unknown run id and another tenant return null`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val run = storedRun(t1)
        load(t1, UUID.randomUUID().toString()) shouldBe null
        load(t2, run) shouldBe null
    }

    @Test
    fun `INV-AC-24381 SealWorkPaper seals once the first execute puts one object and the second puts none`() {
        val t1 = UUID.randomUUID()
        val run = storedRun(t1)
        val store = RecordingStore()
        seal(t1, run, store) shouldBe SealOutcome.SEALED
        store.puts.size shouldBe 1
        store.puts[0].second.contentEquals(load(t1, run)!!.canonicalJson) shouldBe true
        seal(t1, run, store) shouldBe SealOutcome.ALREADY_SEALED
        store.puts.size shouldBe 1
        scalar(
            "select count(*) from reconciliation.work_paper_seal where tenant_id = '$t1' and entity_ref = 'E1'",
        ) shouldBe 1L
        scalar("select count(*) from reconciliation.work_paper_seal where entity_ref = '$run'") shouldBe 0L
        seal(UUID.randomUUID(), run, store) shouldBe SealOutcome.NO_WORK_PAPER
        store.puts.size shouldBe 1
    }
}
