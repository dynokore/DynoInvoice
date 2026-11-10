package com.dynokore.platform.rulesevaluation.coding

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.platform.rulesevaluation.EvaluationResult
import com.dynokore.platform.rulesevaluation.packs.CodeCataloguePackContents
import com.dynokore.platform.rulesevaluation.packs.MappingPackContents
import com.dynokore.platform.rulesevaluation.packs.ScenarioPackContents
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class TenantMappingTest {
    private val catalogueJson =
        """{"complete":true,"codes":[{"code":"SR","group":"SUPPLY_STANDARD_RATED","side":"SUPPLY","annexERate":"9",""" +
            """"sourceClass":"PRIMARY","sourceReference":"p79"}]}"""
    private val mappingJson =
        """{"entries":[{"entryId":"e1","internalTaxCode":"SR-STD","irasCode":"SR","sourceClass":"PRIMARY"}]}"""
    private val scenarioJson =
        """{"selfBillingAssignment":{"customerCumIssuer":{"direction":"PURCHASE","submissionType":"TYPE_3",""" +
            """"sourceClass":"PRIMARY","sourceReference":"r"},"supplierCumRecipient":{"direction":"SALE",""" +
            """"submissionType":"TYPE_2","sourceClass":"PRIMARY","sourceReference":"r"}},""" +
            """"schemeTagSides":[{"tag":"A3PL","side":"SUPPLY","handling":"HANDLED","sourceClass":"PRIMARY",""" +
            """"sourceReference":"r"}],"treatments":[{"tag":"A3PL","side":"SUPPLY","categoryCode":"SR",""" +
            """"rateBasis":"PREVAILING","counterpartyApprovedUnder":["A3PL"],"sourceClass":"PRIMARY",""" +
            """"sourceReference":"r"}]}"""

    private fun coder() =
        LineCoder(
            TenantMapping(MappingPackContents.decode(mappingJson.toByteArray())),
            CodeCataloguePackContents.decode(catalogueJson.toByteArray()),
            ScenarioPackContents.decode(scenarioJson.toByteArray()),
        )

    @Test
    fun `INV-AC-19451 a line is coded through a TenantMapping decoded from pack contents`() {
        coder().code(LineInput("SR-STD")) shouldBe LineCoding.Coded("SR")
    }

    @Test
    fun `INV-AC-19451 an unmapped internal tax code is refused with no default code and no category code`() {
        val answer = coder().code(LineInput("X-NEW"))
        answer shouldBe LineCoding.Refused(ErrorCode.RUL_001)
        answer.shouldBeInstanceOf<LineCoding.Refused>()
        TenantMapping(emptyList()).entryOf("X-NEW") shouldBe null
    }

    @Test
    fun `INV-AC-19451 a mapping key is matched exactly, never case-folded`() {
        coder().code(LineInput("sr-std")) shouldBe LineCoding.Refused(ErrorCode.RUL_001)
        TenantMapping(listOf(MappingEntry("SR-STD", "SR"))).entryOf("sr-std") shouldBe null
    }

    @Test
    fun `INV-AC-19451 one internal tax code mapped twice is refused`() {
        shouldThrow<IllegalArgumentException> {
            TenantMapping(listOf(MappingEntry("A", "SR"), MappingEntry("A", "ZR")))
        }
    }

    @Test
    fun `INV-AC-19451 the evaluation result of this slice carries no scope verdict and no document type`() {
        EvaluationResult("SR", null, null).scopeVerdict shouldBe null
        EvaluationResult("SR", null, null).documentTypeCode shouldBe null
    }
}
