package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.domain.document.WindowDocument
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The window read of INV-REQ-772 b1 over `document` and the current `document_version`. */
class DocumentWindowTest {
    private val kit = DocumentKit()
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val march = LocalDate.of(2026, 3, 1) to LocalDate.of(2026, 3, 31)
    private val april = LocalDate.of(2026, 4, 1) to LocalDate.of(2026, 4, 30)

    private fun insert(
        on: LocalDate,
        owner: UUID = tenant,
        within: UUID = entity,
    ) = DocumentKit.document(owner, within, on).also { kit.insert(it) }

    private fun read(
        window: Pair<LocalDate, LocalDate>,
        asTenant: UUID = tenant,
        of: UUID = entity,
        pageSize: Int = 100,
        after: UUID? = null,
    ): List<WindowDocument> =
        kit.asTenant(asTenant) { kit.windows.readWindow(asTenant, of, window.first, window.second, pageSize, after) }

    @Test
    fun `a window returns the documents of the entity with their current version`() {
        val document = insert(LocalDate.of(2026, 3, 10))
        val read = read(march).single()
        read.docUuid shouldBe document.docUuid
        read.documentTypeCode shouldBe 380.toShort()
        read.transactionDate shouldBe LocalDate.of(2026, 3, 10)
        read.currentVersion shouldBe 1
        read.canonical.contains(document.documentNumber) shouldBe true
    }

    @Test
    fun `the documents of another entity and of another tenant are never returned`() {
        insert(LocalDate.of(2026, 3, 10), within = UUID.randomUUID())
        val otherTenant = UUID.randomUUID()
        val foreign = insert(LocalDate.of(2026, 3, 10), owner = otherTenant)
        read(march).shouldBeEmpty()
        // a session of tenant A asking for the entity of tenant B gets nothing
        read(march, of = foreign.entityId).shouldBeEmpty()
        // and tenant B's own session reads its document
        read(march, asTenant = otherTenant, of = foreign.entityId).single().docUuid shouldBe foreign.docUuid
    }

    @Test
    fun `a window of one period returns none of the neighbouring period`() {
        insert(LocalDate.of(2026, 3, 31))
        val inApril = insert(LocalDate.of(2026, 4, 1))
        read(april).map { it.docUuid } shouldBe listOf(inApril.docUuid)
        read(march).size shouldBe 1
    }

    @Test
    fun `an amended document is returned for the new period with its latest version and not for the old`() {
        val document = insert(LocalDate.of(2026, 3, 10))
        kit.asTenant(tenant) {
            kit.versions.createVersion(
                document.docUuid,
                1,
                DocumentKit.version(
                    LocalDate.of(2026, 4, 5),
                    """{"marker":"latest",""" +
                        """"lines":{"value":[],"classification":"content"},""" +
                        """"taxSubtotals":{"value":[],"classification":"content"},""" +
                        """"parties":{"value":{"supplier":{},"buyer":{}},"classification":"content"}}""",
                ),
            )
        } shouldBe UpdateOutcome.Updated(2)
        read(march).shouldBeEmpty()
        val returned = read(april).single()
        returned.docUuid shouldBe document.docUuid
        returned.currentVersion shouldBe 2
        returned.canonical.contains("latest") shouldBe true
    }

    @Test
    fun `a window beyond the stored history returns the existing documents`() {
        val document = insert(LocalDate.of(2026, 3, 10))
        read(LocalDate.of(1900, 1, 1) to LocalDate.of(2999, 12, 31)).single().docUuid shouldBe document.docUuid
    }

    @Test
    fun `paging by afterDocUuid returns every document once in doc_uuid order`() {
        val expected = (1..7).map { insert(LocalDate.of(2026, 3, it)).docUuid }.sortedBy { it.toString() }
        val seen = mutableListOf<UUID>()
        var after: UUID? = null
        do {
            val page = read(march, pageSize = 3, after = after)
            (page.size <= 3) shouldBe true
            seen += page.map { it.docUuid }
            after = page.lastOrNull()?.docUuid
        } while (page.isNotEmpty())
        seen shouldBe expected
    }
}
