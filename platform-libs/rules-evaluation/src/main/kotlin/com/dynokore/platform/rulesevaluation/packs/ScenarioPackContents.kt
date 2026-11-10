package com.dynokore.platform.rulesevaluation.packs

import com.dynokore.platform.rulesevaluation.coding.Direction
import com.dynokore.platform.rulesevaluation.coding.Handling
import com.dynokore.platform.rulesevaluation.coding.RateBasis
import com.dynokore.platform.rulesevaluation.coding.ScenarioData
import com.dynokore.platform.rulesevaluation.coding.SchemeTagSide
import com.dynokore.platform.rulesevaluation.coding.SelfBillingAssignment
import com.dynokore.platform.rulesevaluation.coding.Side
import com.dynokore.platform.rulesevaluation.coding.SubmissionType
import com.dynokore.platform.rulesevaluation.coding.Treatment

/**
 * Decodes the decision sections of a SCENARIO pack (`scenario-pack-handlers.schema.json`, INV-Q-6510): the
 * self-billing assignment, `schemeTagSides` and `treatments`. `approvableSchemes` and `supplyDateBasis` are read by
 * other evaluations and are not decoded here; a member the schema does not name is ignored (API-41). Every value the
 * coding decides from is read from the pack: no assignment, tag or treatment is a constant of the engine (INV-REQ-416).
 */
object ScenarioPackContents {
    private const val PACK_TYPE = "SCENARIO"
    private val TAG = Regex("^[A-Z][A-Z0-9_]{1,39}$")
    private val SIDES = setOf("SUPPLY", "PURCHASE")
    private val RATE = Regex("^[0-9]{1,3}(\\.[0-9]{1,6})?$")

    fun decode(contents: ByteArray): ScenarioData {
        val root = ContentsNode.root(PACK_TYPE, contents)
        val assignment = root.obj("selfBillingAssignment")
        return ScenarioData(
            customerCumIssuer = party(assignment.obj("customerCumIssuer")),
            supplierCumRecipient = party(assignment.obj("supplierCumRecipient")),
            schemeTagSides = root.array("schemeTagSides", min = 1, max = 100).map(::schemeTagSide),
            treatments = root.array("treatments", min = 1, max = 300).map(::treatment),
        )
    }

    private fun cited(node: ContentsNode) {
        node.text("sourceClass", oneOf = SOURCE_CLASSES)
        node.text("sourceReference", min = 1, max = 500)
    }

    private fun party(node: ContentsNode): SelfBillingAssignment {
        val direction = node.text("direction", oneOf = setOf("SALE", "PURCHASE"))
        val type = node.text("submissionType", oneOf = SubmissionType.entries.map { it.name }.toSet())
        cited(node)
        return SelfBillingAssignment(Direction.valueOf(direction), SubmissionType.valueOf(type))
    }

    private fun schemeTagSide(node: ContentsNode): SchemeTagSide {
        val tag = node.text("tag", pattern = TAG)
        val side = node.text("side", oneOf = SIDES)
        val handling = node.text("handling", oneOf = Handling.entries.map { it.name }.toSet())
        node.optionalText("pendingReference", min = 1, max = 300)
        val parent = node.optionalText("parentScheme", pattern = TAG)
        cited(node)
        if (handling == "PENDING_QUESTION" && !node.has("pendingReference")) {
            node.fail("pendingReference", "is required for handling PENDING_QUESTION")
        }
        if (handling == "HANDLED" && node.has("pendingReference")) {
            node.fail("pendingReference", "must be absent for handling HANDLED")
        }
        return SchemeTagSide(tag, Side.valueOf(side), Handling.valueOf(handling), parent)
    }

    private fun treatment(node: ContentsNode): Treatment {
        val tag = node.text("tag", pattern = TAG)
        val side = node.text("side", oneOf = SIDES)
        val code = node.text("categoryCode", pattern = CODE_PATTERN)
        val basis = node.text("rateBasis", oneOf = RateBasis.entries.map { it.name }.toSet())
        val approvedUnder = node.texts("counterpartyApprovedUnder", min = 1, max = 10, pattern = TAG)
        val agreement = node.optionalText("writtenAgreement", oneOf = setOf("AGREED", "NOT_AGREED"))
        val rate = node.optionalText("rate", pattern = RATE)
        val statement = node.optionalText("ibt022Statement", min = 1, max = 500)
        val sourceClass = node.text("sourceClass", oneOf = SOURCE_CLASSES)
        val sourceReference = node.text("sourceReference", min = 1, max = 500)
        if (basis == "FIXED" && rate == null) node.fail("rate", "is required for rateBasis FIXED")
        if (basis == "PREVAILING" && rate != null) node.fail("rate", "must be absent for rateBasis PREVAILING")
        return Treatment(
            tag = tag,
            side = Side.valueOf(side),
            categoryCode = code,
            rateBasis = RateBasis.valueOf(basis),
            rate = rate,
            counterpartyApprovedUnder = approvedUnder.orEmpty().toSet(),
            writtenAgreement = agreement,
            ibt022Statement = statement,
            sourceClass = sourceClass,
            sourceReference = sourceReference,
        )
    }
}
