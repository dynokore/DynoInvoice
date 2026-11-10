package com.dynokore.platform.rulesevaluation

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.platform.rulesevaluation.coding.LineCoder
import com.dynokore.platform.rulesevaluation.coding.LineCoding
import com.dynokore.platform.rulesevaluation.coding.LineInput
import com.dynokore.platform.rulesevaluation.coding.TenantMapping
import com.dynokore.platform.rulesevaluation.packs.CodeCataloguePackContents
import com.dynokore.platform.rulesevaluation.packs.MappingPackContents
import com.dynokore.platform.rulesevaluation.packs.PackContentsFailure
import com.dynokore.platform.rulesevaluation.packs.ScenarioPackContents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Consumer-driven contract test of the module API (the ModuleApiConsumerContractTest pattern): a stub written the way
 * `rules-svc` and `control-plane-svc` write their coding use case, through the public surface only. The services do
 * not embed the module yet: the stub plays their role.
 */
class ModuleApiConsumerContractTest {
    private val scenario =
        """{"selfBillingAssignment":{"customerCumIssuer":{"direction":"PURCHASE","submissionType":"TYPE_3",""" +
            """"sourceClass":"PRIMARY","sourceReference":"r"},"supplierCumRecipient":{"direction":"SALE",""" +
            """"submissionType":"TYPE_2","sourceClass":"PRIMARY","sourceReference":"r"}},""" +
            """"schemeTagSides":[{"tag":"A3PL","side":"SUPPLY","handling":"HANDLED","sourceClass":"PRIMARY",""" +
            """"sourceReference":"r"}],"treatments":[{"tag":"A3PL","side":"SUPPLY","categoryCode":"SR",""" +
            """"rateBasis":"PREVAILING","counterpartyApprovedUnder":["A3PL"],"sourceClass":"PRIMARY",""" +
            """"sourceReference":"r"}]}"""

    private class RulesSvcStub(
        mapping: String,
        catalogue: String,
        scenario: String,
    ) {
        private val coder =
            LineCoder(
                TenantMapping(MappingPackContents.decode(mapping.toByteArray())),
                CodeCataloguePackContents.decode(catalogue.toByteArray()),
                ScenarioPackContents.decode(scenario.toByteArray()),
            )

        fun evaluate(internalTaxCode: String): EvaluationResult =
            when (val c = coder.code(LineInput(internalTaxCode))) {
                is LineCoding.Coded -> EvaluationResult(c.categoryCode, null, null)
                is LineCoding.Refused -> EvaluationResult(null, null, null)
            }
    }

    private val catalogue =
        """{"complete":true,"codes":[{"code":"SR","group":"SUPPLY_STANDARD_RATED","side":"SUPPLY","annexERate":"9",""" +
            """"sourceClass":"PRIMARY","sourceReference":"p79"}]}"""
    private val mapping =
        """{"entries":[{"entryId":"e1","internalTaxCode":"SR-STD","irasCode":"SR","sourceClass":"PRIMARY"}]}"""

    @Test
    fun `INV-AC-19451 a service decodes the packs, codes a line and gets a category code or none`() {
        val svc = RulesSvcStub(mapping, catalogue, scenario)
        assertEquals(EvaluationResult("SR", null, null), svc.evaluate("SR-STD"))
        assertEquals(EvaluationResult(null, null, null), svc.evaluate("UNMAPPED"))
    }

    @Test
    fun `INV-AC-19451 contents that break the schema fail the decode and are never repaired`() {
        assertThrows(PackContentsFailure::class.java) { MappingPackContents.decode("""{"entries":[]}""".toByteArray()) }
        val coder =
            LineCoder(
                emptyList(),
                CodeCataloguePackContents.decode(catalogue.toByteArray()),
                ScenarioPackContents.decode(scenario.toByteArray()),
            )
        assertEquals(ErrorCode.RUL_001, (coder.code(LineInput("X")) as LineCoding.Refused).code)
    }
}
