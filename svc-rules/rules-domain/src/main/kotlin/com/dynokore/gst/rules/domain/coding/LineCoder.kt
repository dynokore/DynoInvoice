package com.dynokore.gst.rules.domain.coding

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.catalogue.Severity

/** The explicit inputs of coding one line (INV-REQ-363: codes are applied per line, in no order of lines). */
data class LineInput(
    val internalTaxCode: String,
    val direction: Direction? = null,
    val election: String? = null,
    /** The counterparty's approved schemes; null when the masterdata resolution gave no scheme data. */
    val counterpartyApprovedSchemes: Set<String>? = null,
    val writtenAgreement: String? = null,
)

sealed interface LineCoding {
    /**
     * The line's category code. [rate] is set when the pack fixes it ([prevailingRate] false); a prevailing rate is the
     * rate resolved for the document date by the caller, so it is left unset here.
     */
    data class Coded(
        val categoryCode: String,
        val rate: String? = null,
        val prevailingRate: Boolean = false,
        val ibt022Statement: String? = null,
        val selfBillingAssignment: SelfBillingAssignment? = null,
    ) : LineCoding

    /** A refusal: code and severity, and no category code, rate or statement (INV-AC-10201). */
    data class Refused(
        val code: ErrorCode,
    ) : LineCoding {
        val severity: Severity get() = code.severity
    }
}

/**
 * Pure per-line coding (INV-REQ-363, INV-REQ-364, INV-REQ-1805): the line's internal code is mapped through the
 * mapping resolved for the document, the code must exist in the catalogue resolved for it, and a scheme tag sends the
 * line to the scenario pack's treatment. Nothing is defaulted: a miss is a refusal, and the catalogue's completeness
 * flag is never read (INV-REQ-418 b3). The two sides of a transaction are coded by separate calls with their own
 * mapping, and nothing here compares them (INV-REQ-365).
 */
class LineCoder(
    mapping: List<MappingEntry>,
    private val catalogue: CodeCatalogue,
    private val scenario: ScenarioData,
) {
    private val byInternalCode: Map<String, MappingEntry> =
        mapping.groupBy { it.internalTaxCode }.mapValues { (code, rows) ->
            require(rows.size == 1) { "internal tax code $code is mapped twice" }
            rows.single()
        }

    fun code(line: LineInput): LineCoding {
        val entry = byInternalCode[line.internalTaxCode] ?: return LineCoding.Refused(ErrorCode.RUL_001)
        val tag = entry.schemeTag
        return when {
            tag == null -> plain(entry)
            tag == SELF_BILLING -> selfBilling(entry, line)
            else -> scheme(entry, tag, line)
        }
    }

    private fun plain(entry: MappingEntry): LineCoding =
        if (catalogue.contains(
                entry.irasCode,
            )
        ) {
            LineCoding.Coded(entry.irasCode)
        } else {
            LineCoding.Refused(ErrorCode.RUL_006)
        }

    private fun selfBilling(
        entry: MappingEntry,
        line: LineInput,
    ): LineCoding {
        val coded = plain(entry)
        if (coded !is LineCoding.Coded) return coded
        val party =
            if (line.direction ==
                Direction.PURCHASE
            ) {
                scenario.customerCumIssuer
            } else {
                scenario.supplierCumRecipient
            }
        return coded.copy(selfBillingAssignment = party)
    }

    private fun scheme(
        entry: MappingEntry,
        tag: String,
        line: LineInput,
    ): LineCoding {
        val side = if (line.direction == Direction.PURCHASE) Side.PURCHASE else Side.SUPPLY
        // a tag that no row lists is not handled, and fails closed (scenario-pack-handlers.schema.json)
        val row = scenario.schemeTagSides.firstOrNull { it.tag == tag && it.side == side }
        val treatments = scenario.treatments.filter { it.tag == tag && it.side == side }
        return when {
            row == null || row.handling == Handling.PENDING_QUESTION -> LineCoding.Refused(ErrorCode.RUL_004)

            // a handled pair with no treatment follows the general rules
            treatments.isEmpty() -> plain(entry)

            line.election.isNullOrBlank() -> LineCoding.Refused(ErrorCode.RUL_004)

            else -> treated(treatments.firstOrNull { applies(it, line) })
        }
    }

    private fun treated(chosen: Treatment?): LineCoding =
        when {
            chosen == null -> {
                LineCoding.Refused(ErrorCode.RUL_011)
            }

            !catalogue.contains(chosen.categoryCode) -> {
                LineCoding.Refused(ErrorCode.RUL_006)
            }

            else -> {
                LineCoding.Coded(
                    categoryCode = chosen.categoryCode,
                    rate = chosen.rate,
                    prevailingRate = chosen.rateBasis == RateBasis.PREVAILING,
                    ibt022Statement = chosen.ibt022Statement,
                )
            }
        }

    private fun applies(
        treatment: Treatment,
        line: LineInput,
    ): Boolean {
        val approved = line.counterpartyApprovedSchemes
        val statusOk =
            treatment.counterpartyApprovedUnder.isEmpty() ||
                (approved != null && approved.any { it in treatment.counterpartyApprovedUnder })
        val agreementOk = treatment.writtenAgreement == null || treatment.writtenAgreement == line.writtenAgreement
        return statusOk && agreementOk
    }

    companion object {
        /** The scheme tag of the contract's MappingEntry that selects the self-billing assignment (INV-Q-6988). */
        const val SELF_BILLING = "SELF_BILLING"
    }
}
