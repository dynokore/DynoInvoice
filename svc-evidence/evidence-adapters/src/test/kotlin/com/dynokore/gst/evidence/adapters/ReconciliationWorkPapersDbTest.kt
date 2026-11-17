package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqReconciliationWorkPapers
import com.dynokore.gst.evidence.adapters.persistence.PackageSizeRefusal
import com.dynokore.gst.evidence.adapters.persistence.WorkPaperIntegrityRefusal
import com.dynokore.gst.evidence.adapters.persistence.WorkPaperRefusal
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectKeyPrefix
import com.dynokore.platform.evidencestore.ObjectRef
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * INV-AC-24184: the export source of RECONCILIATION_WORK_PAPERS over PostgreSQL and a fake object store. T1 has two
 * recorded work-papers (E1/R1, E2/R2) and one RENDITION row; T2 has one work-paper row.
 */
class ReconciliationWorkPapersDbTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val r1 = "reconciliation/$t1/R1/v1"
    private val r2 = "reconciliation/$t1/R2/v1"
    private val rT2 = "reconciliation/$t2/R3/v1"

    private class Fake : ObjectStore {
        val objects = linkedMapOf<String, ByteArray>()
        val reads = mutableListOf<String>()

        override fun put(
            key: ObjectKey,
            bytes: ByteArray,
            retainUntil: Instant,
        ): ObjectRef {
            objects[key.value] = bytes
            return ObjectRef(key, retainUntil)
        }

        override fun get(key: ObjectKey): ByteArray {
            reads += key.value
            return objects[key.value] ?: error("no such object")
        }

        override fun bytesUnder(prefix: ObjectKeyPrefix): Long =
            objects.filterKeys { it.startsWith(prefix.value) }.values.sumOf { it.size.toLong() }
    }

    private fun paper(period: String?) =
        (if (period == null) """{"header":{"entityRef":"E"}}""" else """{"header":{"periodRef":"$period"}}""")
            .toByteArray()

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).let { HexFormat.of().formatHex(it) }

    private fun <T> tx(
        tenant: UUID,
        body: (DSLContext) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            body(DSL.using(c, SQLDialect.POSTGRES)).also { c.commit() }
        }

    private fun row(
        tenant: UUID,
        entity: String?,
        type: String,
        reference: String,
        hash: String,
    ) = tx(tenant) { dsl ->
        dsl.execute(
            "insert into evidence.evidence_artefact (id, tenant_id, entity_ref, artefact_type, reference, hash, " +
                "event_id, recorded_at) values (?, ?, ?, ?, ?, ?, ?, now())",
            UUID.randomUUID(),
            tenant,
            entity,
            type,
            reference,
            hash,
            UUID.randomUUID().toString(),
        )
    }

    private val b1 = paper("P1")
    private val b2 = paper("P2")

    private fun fixture(): Fake {
        val store = Fake()
        store.objects[r1] = b1
        store.objects[r2] = b2
        store.objects[rT2] = paper("P3")
        row(t1, "E1", "RECONCILIATION_WORK_PAPER", r1, sha(b1))
        row(t1, "E2", "RECONCILIATION_WORK_PAPER", r2, sha(b2))
        row(t1, "E1", "RENDITION", "rendition/$t1/x", "c".repeat(64))
        row(t2, "E9", "RECONCILIATION_WORK_PAPER", rT2, sha(paper("P3")))
        return store
    }

    private fun read(
        store: Fake,
        tenant: UUID = t1,
        maxBytes: Long = 1_000_000,
    ) = tx(tenant) { dsl -> JooqReconciliationWorkPapers(dsl, store, maxBytes).candidates(TenantId(tenant)) }

    @Test
    fun `INV-AC-24184 T1 returns the two verified work-papers ordered by entity, without the RENDITION and T2 rows`() {
        val c = read(fixture())
        c.map { it.entity.value } shouldBe listOf("E1", "E2")
        c.map { it.period.value } shouldBe listOf("P1", "P2")
        c.map { it.item.name } shouldBe listOf("R1-v1.json", "R2-v1.json")
        c.all { it.item.contentClass == PackageContentClass.RECONCILIATION_WORK_PAPERS } shouldBe true
        c.all { it.tenant == TenantId(t1) } shouldBe true
        c.map { it.item.bytes().toList() } shouldBe listOf(b1.toList(), b2.toList())
    }

    @Test
    fun `INV-AC-24184 bytes of R1 altered by one byte raise the integrity refusal naming the reference`() {
        val store = fixture()
        store.objects[r1] = b1.copyOf().also { it[0] = (it[0] + 1).toByte() }
        shouldThrow<WorkPaperIntegrityRefusal> { read(store) }.reference shouldBe r1
    }

    @Test
    fun `INV-AC-24184 a missing object of R2 raises and returns nothing`() {
        val store = fixture()
        store.objects.remove(r2)
        shouldThrow<IllegalStateException> { read(store) }
    }

    @Test
    fun `INV-AC-24184 a T1 row whose reference is of T2 raises a refusal and no object is read for it`() {
        val store = fixture()
        val foreign = "reconciliation/$t2/R9/v1"
        store.objects[foreign] = paper("P9")
        row(t1, "E3", "RECONCILIATION_WORK_PAPER", foreign, sha(paper("P9")))
        shouldThrow<WorkPaperRefusal> { read(store) }
        store.reads.contains(foreign) shouldBe false
    }

    @Test
    fun `INV-AC-24184 bytes under the T1 prefix above maxBytes raise the size refusal before any object is read`() {
        val store = fixture()
        val under = (b1.size + b2.size).toLong()
        val e = shouldThrow<PackageSizeRefusal> { read(store, maxBytes = under - 1) }
        e.contentClass shouldBe PackageContentClass.RECONCILIATION_WORK_PAPERS
        store.reads shouldBe emptyList()
        read(store, maxBytes = under).size shouldBe 2
    }

    @Test
    fun `INV-AC-24184 bytes without header periodRef raise a refusal`() {
        val bad = paper(null)
        val fresh = Fake()
        val t3 = UUID.randomUUID()
        val ref = "reconciliation/$t3/R4/v1"
        fresh.objects[ref] = bad
        row(t3, "E4", "RECONCILIATION_WORK_PAPER", ref, sha(bad))
        val e = shouldThrow<WorkPaperRefusal> { read(fresh, tenant = t3) }
        e.message!! shouldContain "periodRef"
    }

    @Test
    fun `INV-AC-24184 a T2-bound connection asked for T1 returns nothing, the predicate is the guard`() {
        val store = fixture()
        tx(t2) { dsl -> JooqReconciliationWorkPapers(dsl, store, 1_000_000).candidates(TenantId(t1)) } shouldBe
            emptyList()
    }
}
