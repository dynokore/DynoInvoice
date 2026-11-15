package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.DocumentKit
import com.dynokore.gst.canonical.adapters.DocumentSql
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-AC-21562: the derived columns of a document version are null until derived; the other constraints stay. */
class DocumentVersionNullableDbTest {
    private val kit = DocumentKit()
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val day = LocalDate.of(2026, 3, 15)

    private fun newVersion(
        verdict: ScopeVerdict?,
        scopePack: String?,
        mappingPack: String?,
        specification: SpecificationVersion?,
    ) = NewVersion(DocumentKit.defaultCanonical("n"), verdict, scopePack, mappingPack, null, specification, day, "svc")

    private fun row(
        doc: UUID,
        version: Int,
    ) = DocumentSql
        .rows(
            "select scope_verdict, scope_pack_version, mapping_pack_version, mde_pack_version, " +
                "specification_version, canonical::text, created_by from canonical.document_version " +
                "where doc_uuid = ? and version = ?",
            doc,
            version,
        ).single()

    private fun nullFlags(doc: UUID) =
        DocumentSql
            .rows(
                "select scope_verdict is null, scope_pack_version is null, mapping_pack_version is null, " +
                    "mde_pack_version is null, specification_version is null from canonical.document_version " +
                    "where doc_uuid = ? and version = 1",
                doc,
            ).single()

    private fun inserted(): UUID {
        val document =
            DocumentKit.document(tenant, entity, day).let {
                it.copy(first = newVersion(null, null, null, null))
            }
        kit.insert(document)
        return document.docUuid
    }

    private fun create(
        doc: UUID,
        from: Int,
        version: NewVersion,
    ) = kit.asTenant(tenant) { kit.versions.createVersion(doc, from, version) }

    private fun bogus(
        column: String,
        value: String,
    ): String {
        val doc = inserted()
        val partition = "2026-03-15"
        val scope = if (column == "scope_verdict") "'$value'" else "null"
        val canonicalValue = if (column == "canonical") "null" else "'{}'"
        val specification = if (column == "specification_version") "'$value'" else "null"
        return DocumentSql.runtimeRefusal(
            tenant,
            "insert into canonical.document_version (doc_uuid, version, tenant_id, entity_id, partition_date, " +
                "canonical, scope_verdict, specification_version, created_by) values ('$doc', 9, '$tenant', " +
                "'$entity', '$partition', $canonicalValue, $scope, $specification, 'svc')",
        )!!
    }

    @Test
    fun `INV-AC-21562 N1 the first version keeps the five derived columns as SQL NULL`() {
        val doc = inserted()
        nullFlags(doc) shouldBe listOf("t", "t", "t", "t", "t")
        // No column default may exist: a default would turn a plain insert into a derived-looking value.
        DocumentSql
            .rows(
                "select column_name from information_schema.columns where table_schema = 'canonical' and " +
                    "table_name = 'document_version' and column_default is not null and column_name in " +
                    "('scope_verdict', 'scope_pack_version', 'mapping_pack_version', 'mde_pack_version', " +
                    "'specification_version')",
            ).size shouldBe 0
        val stored = row(doc, 1)
        stored[5]!!.contains("\"documentNumber\"") shouldBe true
        stored[6] shouldBe "svc"
    }

    @Test
    fun `INV-AC-21562 N2 version 2 keeps its derived values by equality`() {
        val doc = inserted()
        create(
            doc,
            1,
            newVersion(ScopeVerdict.IN_SCOPE, "SP-1", "MP-1", SpecificationVersion.SG_PEPPOL_BIS),
        ) shouldBe UpdateOutcome.Updated(2)
        val stored = row(doc, 2)
        stored.take(5) shouldBe listOf("IN_SCOPE", "SP-1", "MP-1", null, "SG_PEPPOL_BIS")
        stored[6] shouldBe "svc"
    }

    @Test
    fun `INV-AC-21562 N3 version 3 keeps a verdict with null pack versions`() {
        val doc = inserted()
        create(doc, 1, newVersion(ScopeVerdict.IN_SCOPE, "SP-1", "MP-1", SpecificationVersion.PINT_SG))
        create(
            doc,
            2,
            newVersion(ScopeVerdict.EXCLUDED_TXN, null, null, null),
        ) shouldBe UpdateOutcome.Updated(3)
        row(doc, 3).take(5) shouldBe listOf("EXCLUDED_TXN", null, null, null, null)
    }

    @Test
    fun `INV-AC-21562 N4 a scope verdict BOGUS is refused by the check constraint`() {
        bogus("scope_verdict", "BOGUS") shouldBe "23514"
    }

    @Test
    fun `INV-AC-21562 N5 a null canonical is refused by the not-null constraint`() {
        bogus("canonical", "x") shouldBe "23502"
    }

    @Test
    fun `INV-AC-21562 N6 a specification version BOGUS is refused by the check constraint`() {
        bogus("specification_version", "BOGUS") shouldBe "23514"
    }
}
