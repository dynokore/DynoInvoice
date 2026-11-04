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
    /** The line's own ARCS written-agreement value; it overrides the counterparty's default (INV-REQ-2434). */
    val writtenAgreement: String? = null,
    /** The counterparty's default ARCS written-agreement value; absent with the line's own means NOT_AGREED. */
    val counterpartyWrittenAgreement: String? = null,
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

    fun code(line: LineInput): LineCoding = decide(line).first

    /**
     * The coding and, for a line under a scheme tag, the decision trace of INV-REQ-2447 behavior 3: the handler (the
     * tag), the pack entry and its source class, and the counterparty data used. Empty for any other line.
     */
    fun codeWithTrace(line: LineInput): Pair<LineCoding, Map<String, String>> = decide(line)

    private fun decide(line: LineInput): Pair<LineCoding, Map<String, String>> {
        val entry = byInternalCode[line.internalTaxCode] ?: return LineCoding.Refused(ErrorCode.RUL_001) to NO_TRACE
        val tag = entry.schemeTag
        return when {
            tag == null -> plain(entry) to NO_TRACE
            tag == SELF_BILLING -> selfBilling(entry, line) to NO_TRACE
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
    ): Pair<LineCoding, Map<String, String>> {
        val side = if (line.direction == Direction.PURCHASE) Side.PURCHASE else Side.SUPPLY
        // a tag that no row lists is not handled, and fails closed (scenario-pack-handlers.schema.json)
        val row = scenario.schemeTagSides.firstOrNull { it.tag == tag && it.side == side }
        val treatments = scenario.treatments.filter { it.tag == tag && it.side == side }
        val base =
            mapOf(
                "handler" to tag,
                "side" to side.name,
                "election" to line.election.orEmpty(),
                "counterpartyApprovedSchemes" to
                    line.counterpartyApprovedSchemes
                        .orEmpty()
                        .sorted()
                        .joinToString(","),
                "writtenAgreement" to agreementOf(line),
            )
        return when {
            row == null || row.handling == Handling.PENDING_QUESTION -> {
                LineCoding.Refused(ErrorCode.RUL_004) to (base + ("outcome" to "FLOW_BLOCKED"))
            }

            !electionSatisfied(tag, side, line.election) -> {
                LineCoding.Refused(ErrorCode.RUL_004) to (base + ("outcome" to "SCHEME_NOT_ELECTED"))
            }

            // a handled pair with no treatment follows the general rules
            treatments.isEmpty() -> {
                plain(entry) to (base + ("outcome" to "GENERAL_RULES"))
            }

            else -> {
                treated(entry, treatments, line, base)
            }
        }
    }

    /**
     * The tag must name the scheme the entity elected (INV-REQ-2436 behavior 2). The one exception is the
     * recipient side of A3PL, which needs no election.
     */
    private fun electionSatisfied(
        tag: String,
        side: Side,
        election: String?,
    ): Boolean = election == tag || (tag to side) == RECIPIENT_WITHOUT_ELECTION

    /** The line's own value, else the counterparty's, else NOT_AGREED: the waiver treatment (INV-REQ-2434 b4). */
    private fun agreementOf(line: LineInput): String =
        line.writtenAgreement ?: line.counterpartyWrittenAgreement ?: NOT_AGREED

    private fun treated(
        entry: MappingEntry,
        treatments: List<Treatment>,
        line: LineInput,
        base: Map<String, String>,
    ): Pair<LineCoding, Map<String, String>> {
        val chosen = treatments.firstOrNull { applies(it, line) }
        // an agreed line coded with a waiver code of the same pair contradicts the flag (INV-REQ-2441 behavior 3)
        val waiverCodes = treatments.filter { it.writtenAgreement == NOT_AGREED }.map { it.categoryCode }.toSet()
        val contradicted = chosen?.writtenAgreement == AGREED && entry.irasCode in waiverCodes
        return when {
            chosen == null || contradicted -> {
                LineCoding.Refused(ErrorCode.RUL_011) to (base + ("outcome" to "COUNTERPARTY_DATA"))
            }

            !catalogue.contains(chosen.categoryCode) -> {
                LineCoding.Refused(ErrorCode.RUL_006) to (base + ("outcome" to "CODE_NOT_IN_CATALOGUE"))
            }

            else -> {
                val trace =
                    base +
                        mapOf(
                            "outcome" to "TREATMENT",
                            "entryCategoryCode" to chosen.categoryCode,
                            "entrySourceClass" to chosen.sourceClass.orEmpty(),
                            "entrySourceReference" to chosen.sourceReference.orEmpty(),
                        )
                LineCoding.Coded(
                    categoryCode = chosen.categoryCode,
                    rate = chosen.rate,
                    prevailingRate = chosen.rateBasis == RateBasis.PREVAILING,
                    ibt022Statement = chosen.ibt022Statement,
                ) to trace
            }
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
        val agreementOk = treatment.writtenAgreement == null || treatment.writtenAgreement == agreementOf(line)
        return statusOk && agreementOk
    }

    companion object {
        /** The scheme tag of the contract's MappingEntry that selects the self-billing assignment (INV-Q-6988). */
        const val SELF_BILLING = "SELF_BILLING"
        private const val AGREED = "AGREED"
        private const val NOT_AGREED = "NOT_AGREED"
        private val NO_TRACE: Map<String, String> = emptyMap()

        /** INV-REQ-2436 behavior 2: the recipient side of A3PL needs no election. */
        private val RECIPIENT_WITHOUT_ELECTION = "A3PL" to Side.PURCHASE
    }
}
