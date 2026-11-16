package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqTenantPackageCandidates
import com.dynokore.gst.evidence.adapters.persistence.PackageSizeRefusal
import com.dynokore.platform.evidencestore.exportpackage.AssemblyRefusal
import com.dynokore.platform.evidencestore.exportpackage.AssemblyResult
import com.dynokore.platform.evidencestore.exportpackage.AuditPackAssembler
import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageScope
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-22948: the tenant package reader returns the items of the four sourced classes of one tenant only.
 * T1 holds two entities with 2 rows in each of the four chain tables; T2 holds 1 row in each.
 */
class TenantPackageCandidatesDbTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val rowBytes = 10
    private val tables =
        mapOf(
            "evidence_payload" to PackageContentClass.TRANSMITTED_XML,
            "acknowledgement" to PackageContentClass.ACKNOWLEDGEMENTS,
            "decision_trace" to PackageContentClass.DECISION_TRACES,
            "audit_log" to PackageContentClass.AUDIT_LOG,
        )
    private val alphabet = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    private fun <T> tx(
        tenant: UUID,
        body: (DSLContext) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            body(DSL.using(c, SQLDialect.POSTGRES)).also { c.commit() }
        }

    private fun seed(
        tenant: UUID,
        entities: List<String>,
        rows: Int,
    ) = tx(tenant) { dsl ->
        tables.keys.forEach { table ->
            entities.forEach { entity ->
                dsl.fetch(
                    "select evidence.create_retention_partition(" +
                        "cast(? as regclass), ?, ?, '2026-Q1', '2026-Q1' || chr(1))",
                    "evidence.$table",
                    tenant,
                    entity,
                )
                for (seq in 1..rows) {
                    dsl.execute(
                        "insert into evidence.$table (id, tenant_id, entity_ref, period_ref, seq, evidence_type, " +
                            "content, content_hash, recorded_at) " +
                            "values (gen_random_uuid(), ?, ?, '2026-Q1', ?, 'T', ?, ?, now())",
                        tenant,
                        entity,
                        seq.toLong(),
                        ByteArray(rowBytes) { seq.toByte() },
                        "sha256:" + "a".repeat(64),
                    )
                }
            }
        }
    }

    private fun fixture() {
        seed(t1, listOf("ENT-A", "ENT-B"), 2)
        seed(t2, listOf("ENT-C"), 1)
    }

    private fun assemble(candidates: List<PackageCandidate>) =
        AuditPackAssembler.assemble(
            PackageScope.WholeTenant(TenantId(t1)),
            candidates,
            Instant.parse("2026-11-15T00:00:00Z"),
        )

    private fun read(
        tenant: UUID,
        maxBytes: Long = 1_000_000,
    ): List<PackageCandidate> =
        tx(tenant) { dsl ->
            JooqTenantPackageCandidates(dsl, maxBytes).sources().values.flatMap { it.candidates(TenantId(tenant)) }
        }

    @Test
    fun `INV-AC-22948 the reader answers 16 candidates for T1 with the class of each table and none of T2`() {
        fixture()
        val candidates = read(t1)
        candidates.size shouldBe 16
        candidates.groupingBy { it.item.contentClass }.eachCount() shouldBe tables.values.associateWith { 4 }
        candidates.map { it.item.contentClass to it.item.name }.toSet().size shouldBe candidates.size
        candidates.all { alphabet.matches(it.item.name) } shouldBe true
        candidates.map { it.entity.value }.toSet() shouldContainExactlyInAnyOrder listOf("ENT-A", "ENT-B")
        candidates.all { it.tenant == TenantId(t1) } shouldBe true
    }

    @Test
    fun `INV-AC-22948 the reader names the same seq under two entities differently`() {
        fixture()
        val names = read(t1).filter { it.item.contentClass == PackageContentClass.AUDIT_LOG }.map { it.item.name }
        names.count { it.startsWith("1-") } shouldBe 2
        names.toSet().size shouldBe 4
    }

    @Test
    fun `INV-AC-22948 the reader bound to T2 answers 4 candidates`() {
        fixture()
        val candidates = read(t2)
        candidates.size shouldBe 4
        candidates.all { it.entity.value == "ENT-C" } shouldBe true
    }

    @Test
    fun `INV-AC-22948 a limit below the total throws the size refusal and returns no candidate`() {
        fixture()
        val perClass = 4L * rowBytes
        val total = 4 * perClass
        shouldThrow<PackageSizeRefusal> { read(t1, perClass - 1) }
        shouldThrow<PackageSizeRefusal> { read(t1, perClass * 2 + rowBytes) }
        shouldThrow<PackageSizeRefusal> { read(t1, total - 1) }
        read(t1, total).size shouldBe 16
    }

    @Test
    fun `INV-AC-22948 the two unsourced classes have no source`() {
        tx(t1) { dsl ->
            JooqTenantPackageCandidates(dsl, 1).sources().keys shouldContainExactlyInAnyOrder tables.values
        }
    }

    @Test
    fun `INV-AC-22948 the four names of one row are equal across the four classes`() {
        fixture()
        val byRow = read(t1).groupBy { it.entity.value to it.item.name.substringBefore('-') }
        byRow.size shouldBe 4
        byRow.values.forEach { group -> group.map { it.item.name }.toSet().size shouldBe 1 }
    }

    @Test
    fun `INV-AC-22948 the 16 candidates of T1 are assembled into 16 distinct data paths`() {
        fixture()
        val result = assemble(read(t1))
        val assembled = result as AssemblyResult.Assembled
        assembled.included shouldBe 16
        val data = assembled.files.paths.filter { it.startsWith("data/") }
        data.size shouldBe 16
        data.toSet().size shouldBe 16
        assembled.files.paths.size shouldBe 16 + 4 + 1
    }

    @Test
    fun `INV-AC-22948 the same class and name twice is refused as DUPLICATE_ITEM`() {
        fixture()
        val one = read(t1).first()
        val result = assemble(listOf(one, one))
        (result as AssemblyResult.Refused).reason shouldBe AssemblyRefusal.DUPLICATE_ITEM
    }
}
