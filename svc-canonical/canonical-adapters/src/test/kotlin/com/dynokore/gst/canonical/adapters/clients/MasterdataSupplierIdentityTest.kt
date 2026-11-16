package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.adapters.clients.masterdata.model.IdentityTripleFacts
import com.dynokore.gst.canonical.application.aggregate.SupplierAnswer
import com.dynokore.gst.canonical.application.aggregate.SupplierIdentity
import com.dynokore.gst.canonical.application.document.EntityConstitutions
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The supplier identity adapter over the identity triple of masterdata-svc (INV-REQ-437). */
class MasterdataSupplierIdentityTest {
    private val tenant = UUID.randomUUID()
    private val date = LocalDate.parse("2026-03-20")
    private var calls = 0

    private fun adapter(
        constitution: String?,
        answer: MasterDataAnswer<IdentityTripleFacts>,
    ) = MasterdataSupplierIdentity(
        object : EntityConstitutions {
            override fun constitutionOf(
                tenantId: UUID,
                entityRef: String,
            ): String? = constitution.takeIf { tenantId == tenant && entityRef == "E1" }
        },
    ) { entityRef, k, d ->
        calls++
        check(entityRef == "E1" && k == "PTE_LTD" && d == date)
        answer
    }

    private fun triple(
        sender: String = "0195:1",
        supplier: String = "0195:2",
        gstn: String = "201912345A",
    ) = MasterDataAnswer.Answered(IdentityTripleFacts(sender, supplier, gstn))

    @Test
    fun `INV-AC-22852 unknown constitution retries without calling masterdata`() {
        adapter(null, triple()).identityOf(tenant, "E1", date) shouldBe SupplierAnswer.Retry("constitution unknown")
        calls shouldBe 0
    }

    @Test
    fun `INV-AC-22852 triple is found with the three values unchanged`() {
        adapter("PTE_LTD", triple()).identityOf(tenant, "E1", date) shouldBe
            SupplierAnswer.Found(SupplierIdentity("0195:1", "0195:2", "201912345A"))
        val max = SupplierIdentity("s".repeat(64), "u".repeat(64), "g".repeat(20))
        adapter("PTE_LTD", triple(max.senderId, max.supplierId, max.supplierGstn))
            .identityOf(tenant, "E1", date) shouldBe SupplierAnswer.Found(max)
    }

    @Test
    fun `INV-AC-22852 refusal MDM-005 retries`() {
        adapter("PTE_LTD", MasterDataAnswer.Refused(422, "MDM-005")).identityOf(tenant, "E1", date) shouldBe
            SupplierAnswer.Retry("masterdata refused: status 422 code MDM-005")
        adapter("PTE_LTD", MasterDataAnswer.Unavailable("down")).identityOf(tenant, "E1", date) shouldBe
            SupplierAnswer.Retry("down")
    }

    @Test
    fun `INV-AC-22852 blank supplierGstn retries`() {
        for (blank in listOf("", "   ")) {
            adapter("PTE_LTD", triple(gstn = blank)).identityOf(tenant, "E1", date) shouldBe
                SupplierAnswer.Retry("supplierGstn is blank or longer than 20")
        }
        adapter("PTE_LTD", triple(sender = " ")).identityOf(tenant, "E1", date) shouldBe
            SupplierAnswer.Retry("senderId is blank or longer than 64")
        adapter("PTE_LTD", triple(supplier = "x".repeat(65))).identityOf(tenant, "E1", date) shouldBe
            SupplierAnswer.Retry("supplierId is blank or longer than 64")
    }

    @Test
    fun `INV-AC-22852 supplierGstn of 21 characters retries`() {
        adapter("PTE_LTD", triple(gstn = "g".repeat(21))).identityOf(tenant, "E1", date) shouldBe
            SupplierAnswer.Retry("supplierGstn is blank or longer than 20")
    }
}
