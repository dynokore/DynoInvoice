package com.dynokore.gst.consoleBff.domain.exceptions

import com.dynokore.gst.consoleBff.domain.badge.ItemState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ExceptionReadModelTest {
    private val tenant = "11111111-1111-1111-1111-111111111111"
    private val doc = "22222222-2222-2222-2222-222222222222"
    private val raised = Instant.parse("2026-11-12T08:00:00Z")
    private val held =
        DocumentHeldFacts(
            entityRef = "ENT-1",
            docUuid = doc,
            docsequence = 7L,
            holdReasons = listOf("MDM-002", "PAY-001"),
            prescribedPeriodRef = "2026-Q3",
            code = "PAY-001",
            remediationOwner = "Tax operations",
            disposition = "MANUAL",
        )

    private fun released(vararg codes: String) = DocumentReleasedFacts(doc, 8L, codes.toList())

    @Test
    fun `INV-AC-20958 pinned ids are version 5 for PAY-001 and MDM-002`() {
        val pay = exceptionId(tenant, "ENT-1", doc, "PAY-001")
        val mdm = exceptionId(tenant, "ENT-1", doc, "MDM-002")
        pay shouldBe UUID.fromString("8115e69b-adb5-52d0-98b2-6422b8e3abe1")
        mdm shouldBe UUID.fromString("7c07080a-dc42-5cb6-8eec-07f213c4dd0c")
        pay.version() shouldBe 5
        mdm.version() shouldBe 5
        pay.variant() shouldBe 2
    }

    @Test
    fun `INV-AC-20958 namespace constant equals the literal`() {
        EXCEPTION_NAMESPACE shouldBe "5c0b4d8e-3a6f-4f0e-9d2b-7e1a64c8b3f5"
    }

    @Test
    fun `INV-AC-20958 changing tenant entityRef docUuid or code changes the id`() {
        val base = exceptionId(tenant, "ENT-1", doc, "PAY-001")
        exceptionId("11111111-1111-1111-1111-111111111112", "ENT-1", doc, "PAY-001") shouldNotBe base
        exceptionId(tenant, "ENT-2", doc, "PAY-001") shouldNotBe base
        exceptionId(tenant, "ENT-1", "22222222-2222-2222-2222-222222222223", "PAY-001") shouldNotBe base
        exceptionId(tenant, "ENT-1", doc, "PAY-002") shouldNotBe base
    }

    @Test
    fun `INV-AC-20958 tuples that concatenate to one string without lengths give different ids`() {
        exceptionId(tenant, "AB", "C2222222-2222-2222-2222-222222222222", "PAY-001") shouldNotBe
            exceptionId(tenant, "A", "BC2222222-2222-2222-2222-222222222222", "PAY-001")
    }

    @Test
    fun `INV-AC-20958 first DocumentHeld gives two OPEN rows with owner on the PAY-001 row only`() {
        val rows = ExceptionReadModel.opened(tenant, held, raised)
        rows.map { it.code } shouldBe listOf("MDM-002", "PAY-001")
        rows.map { it.state }.toSet() shouldBe setOf(ItemState.OPEN)
        val pay = rows.single { it.code == "PAY-001" }
        pay.exceptionId shouldBe UUID.fromString("8115e69b-adb5-52d0-98b2-6422b8e3abe1")
        pay.remediationOwner shouldBe "Tax operations"
        pay.disposition shouldBe "MANUAL"
        pay.raisedAt shouldBe raised
        pay.periodRef shouldBe "2026-Q3"
        pay.docsequence shouldBe 7L
        pay.tenantId shouldBe tenant
    }

    @Test
    fun `INV-AC-20958 MDM-002 row holds the empty string for owner and disposition`() {
        val mdm = ExceptionReadModel.opened(tenant, held, raised).single { it.code == "MDM-002" }
        mdm.exceptionId shouldBe UUID.fromString("7c07080a-dc42-5cb6-8eec-07f213c4dd0c")
        mdm.remediationOwner shouldBe ""
        mdm.disposition shouldBe ""
    }

    @Test
    fun `INV-AC-20958 a repeated hold equals the first result`() {
        ExceptionReadModel.opened(tenant, held, raised) shouldBe ExceptionReadModel.opened(tenant, held, raised)
    }

    @Test
    fun `INV-AC-20958 release of PAY-001 resolves only PAY-001`() {
        val rows = ExceptionReadModel.opened(tenant, held, raised)
        val out = ExceptionReadModel.release(rows, released("PAY-001"))
        out.map { it.code } shouldBe listOf("PAY-001")
        out.single().state shouldBe ItemState.RESOLVED
    }

    @Test
    fun `INV-AC-20958 repeated release and release of XXX-999 change nothing`() {
        val rows = ExceptionReadModel.opened(tenant, held, raised)
        val resolved = ExceptionReadModel.release(rows, released("PAY-001"))
        val after = rows.map { r -> resolved.firstOrNull { it.exceptionId == r.exceptionId } ?: r }
        ExceptionReadModel.release(after, released("PAY-001")) shouldBe emptyList()
        ExceptionReadModel.release(rows, released("XXX-999")) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20958 second DocumentHeld gives one OPEN row with empty owner and disposition`() {
        val second =
            DocumentHeldFacts("ENT-1", "33333333-3333-3333-3333-333333333333", 1L, listOf("MDM-002"), "2026-Q3")
        val rows = ExceptionReadModel.opened(tenant, second, raised)
        rows.size shouldBe 1
        rows.single().state shouldBe ItemState.OPEN
        rows.single().remediationOwner shouldBe ""
        rows.single().disposition shouldBe ""
    }

    @Test
    fun `INV-AC-20958 code not in holdReasons writes the empty string on every row`() {
        val rows = ExceptionReadModel.opened(tenant, held.copy(code = "ZZZ-999"), raised)
        rows.map { it.remediationOwner } shouldBe listOf("", "")
        rows.map { it.disposition } shouldBe listOf("", "")
    }

    @Test
    fun `INV-AC-20958 a stored OVERRIDDEN row is not resolved by a release`() {
        val rows = ExceptionReadModel.opened(tenant, held, raised).map { it.copy(state = ItemState.OVERRIDDEN) }
        ExceptionReadModel.release(rows, released("PAY-001", "MDM-002")) shouldBe emptyList()
    }
}
