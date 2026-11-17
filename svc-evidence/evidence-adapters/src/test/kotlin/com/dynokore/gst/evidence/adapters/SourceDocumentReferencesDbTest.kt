package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.persistence.JooqSourceDocumentReferences
import com.dynokore.gst.evidence.adapters.persistence.PackageSizeRefusal
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * INV-AC-24168: the export source of SOURCE_DOCUMENT_REFERENCES returns one item per stored reference of the tenant.
 * T1 holds D1 v1 ('dms://t1/a'), D1 v2 ('dms://t1/b') and D2 v1 (no reference); T2 holds one row ('dms://t2/c').
 */
class SourceDocumentReferencesDbTest {
    private val t1 = UUID.randomUUID()
    private val t2 = UUID.randomUUID()
    private val d1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val d2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val d3 = UUID.fromString("00000000-0000-0000-0000-0000000000d3")
    private val alphabet = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val mapper = JsonMapper.builder().build()

    private fun <T> tx(
        tenant: UUID,
        body: (DSLContext) -> T,
    ): T =
        EvidenceDb.connect(EvidenceDb.RUNTIME, EvidenceDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            body(DSL.using(c, SQLDialect.POSTGRES)).also { c.commit() }
        }

    private fun insert(
        tenant: UUID,
        doc: UUID,
        version: Int,
        entity: String,
        ref: String?,
    ) = tx(tenant) { dsl ->
        dsl.execute(
            "insert into evidence.document_locator (tenant_id, doc_uuid, version, entity_ref, period_ref, " +
                "transaction_date, trace_reference, source_event_id, recorded_at, source_document_ref) " +
                "values (?, ?, ?, ?, '2026-Q1', date '2026-01-15', 'trace', ?, now(), ?)",
            tenant,
            doc,
            version,
            entity,
            UUID.randomUUID().toString(),
            ref,
        )
    }

    private fun fixture() {
        insert(t1, d1, 1, "ENT-A", "dms://t1/a")
        insert(t1, d1, 2, "ENT-A", "dms://t1/b")
        insert(t1, d2, 1, "ENT-A", null)
        insert(t2, d3, 1, "ENT-C", "dms://t2/c")
    }

    private fun read(
        tenant: UUID,
        maxBytes: Long = 1_000_000,
    ) = tx(tenant) { dsl -> JooqSourceDocumentReferences(dsl, maxBytes).candidates(TenantId(tenant)) }

    @Test
    fun `INV-AC-24168 T1 returns 2 candidates of the class with the row values in the item bytes`() {
        fixture()
        val candidates = read(t1)
        candidates.size shouldBe 2
        candidates.all { it.item.contentClass == PackageContentClass.SOURCE_DOCUMENT_REFERENCES } shouldBe true
        candidates.all { it.tenant == TenantId(t1) } shouldBe true
        val nodes = candidates.map { mapper.readTree(it.item.bytes()) }
        nodes.map { it.get("docUuid").asString() } shouldBe listOf(d1.toString(), d1.toString())
        nodes.map { it.get("version").asInt() } shouldBe listOf(1, 2)
        nodes.map { it.get("entityRef").asString() } shouldBe listOf("ENT-A", "ENT-A")
        nodes.map { it.get("periodRef").asString() } shouldBe listOf("2026-Q1", "2026-Q1")
        nodes.map { it.get("sourceDocumentRef").asString() } shouldBe listOf("dms://t1/a", "dms://t1/b")
        nodes.all { !it.has("documentNumber") } shouldBe true
        val members =
            nodes
                .first()
                .propertyNames()
                .asSequence()
                .toList()
        members shouldBe
            listOf("docUuid", "version", "entityRef", "periodRef", "sourceDocumentRef")
    }

    @Test
    fun `INV-AC-24168 the item names are unique, match the item name rule and differ per version`() {
        fixture()
        val names = read(t1).map { it.item.name }
        names shouldBe listOf("$d1-1", "$d1-2")
        names.toSet().size shouldBe names.size
        names.all { alphabet.matches(it) } shouldBe true
    }

    @Test
    fun `INV-AC-24168 bound to T2 the source returns 1 candidate and none of T1`() {
        fixture()
        val candidates = read(t2)
        candidates.size shouldBe 1
        mapper.readTree(candidates.single().item.bytes()).get("sourceDocumentRef").asString() shouldBe "dms://t2/c"
    }

    @Test
    fun `INV-AC-24168 a limit below the total of T1 throws the size refusal and returns nothing`() {
        fixture()
        val total = "dms://t1/a".length + "dms://t1/b".length
        shouldThrow<PackageSizeRefusal> { read(t1, total - 1L) }
        read(t1, 1_000_000).size shouldBe 2
    }
}
