package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.persistence.JooqPrecedingDocumentLookup
import com.dynokore.gst.canonical.application.document.PrecedingReference
import com.dynokore.gst.canonical.application.document.ResolvePrecedingDocUuid
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.PrecedingResolution
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The link from a credit note to its preceding document over `document` (INV-AC-752 needs it; INV-Q-6311 A). */
class PrecedingDocumentLookupTest {
    private val kit = DocumentKit()
    private val resolver = ResolvePrecedingDocUuid(JooqPrecedingDocumentLookup(kit.dsl), kit.transaction)
    private val tenant = UUID.randomUUID()
    private val entity = UUID.randomUUID()
    private val number = "INV-" + UUID.randomUUID()
    private val issuer = "issuer-1"
    private val creditNoteUuid = UUID.randomUUID()
    private val day = LocalDate.of(2026, 3, 10)

    private fun original(
        owner: UUID = tenant,
        within: UUID = entity,
        direction: Direction = Direction.SALE,
        by: String = issuer,
        numbered: String = number,
        year: Short = 2026,
        on: LocalDate = day,
    ): NewDocument =
        DocumentKit
            .document(owner, within, on, number = numbered, issuer = by)
            .copy(direction = direction, fiscalYear = year)
            .also { kit.insert(it) }

    private fun resolve(
        date: LocalDate? = null,
        asTenant: UUID = tenant,
        own: UUID = creditNoteUuid,
        precedingNumber: String = number,
    ): PrecedingResolution =
        kit.asTenant(asTenant) {
            resolver.resolvePrecedingDocUuid(
                PrecedingReference(own, entity, Direction.SALE, issuer, precedingNumber, date),
            )
        }

    @Test
    fun `INV-AC-752 a number of one document of the same entity, direction and issuer is resolved`() {
        val document = original()
        resolve() shouldBe PrecedingResolution.Resolved(document.docUuid)
    }

    @Test
    fun `INV-AC-752 the same number on another entity is unresolved`() {
        original(within = UUID.randomUUID())
        resolve() shouldBe PrecedingResolution.Unresolved
    }

    @Test
    fun `INV-AC-752 the same number on the other direction is unresolved`() {
        original(direction = Direction.PURCHASE)
        resolve() shouldBe PrecedingResolution.Unresolved
    }

    @Test
    fun `INV-AC-752 the same number of another issuer is unresolved`() {
        original(by = "issuer-2")
        resolve() shouldBe PrecedingResolution.Unresolved
    }

    @Test
    fun `INV-AC-752 the same number in another tenant is unresolved for a session of tenant A`() {
        val otherTenant = UUID.randomUUID()
        original(owner = otherTenant)
        resolve() shouldBe PrecedingResolution.Unresolved
    }

    @Test
    fun `INV-AC-752 a credit note whose preceding number is its own number is unresolved`() {
        val itself = original()
        resolve(own = itself.docUuid) shouldBe PrecedingResolution.Unresolved
    }

    @Test
    fun `INV-AC-752 a number in two fiscal years is unresolved without a date and resolved with the date of one`() {
        val earlier = original(year = 2025, on = LocalDate.of(2025, 6, 1))
        val later = original(year = 2026, on = LocalDate.of(2026, 6, 1))
        resolve() shouldBe PrecedingResolution.Unresolved
        resolve(LocalDate.of(2025, 6, 1)) shouldBe PrecedingResolution.Resolved(earlier.docUuid)
        resolve(LocalDate.of(2026, 6, 1)) shouldBe PrecedingResolution.Resolved(later.docUuid)
    }

    @Test
    fun `INV-AC-752 a number of exactly 64 characters is found`() {
        val long = "N".repeat(64)
        val document = original(numbered = long)
        resolve(precedingNumber = long) shouldBe PrecedingResolution.Resolved(document.docUuid)
    }
}
