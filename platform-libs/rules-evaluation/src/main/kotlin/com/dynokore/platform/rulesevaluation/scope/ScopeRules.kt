package com.dynokore.platform.rulesevaluation.scope

import com.dynokore.platform.rulesevaluation.coding.LineCoding

/**
 * The pure data of the scope rules: the `lineVerdict` per category code of the scope pack, the intra-group `treatment`
 * (null when the pack has none) and the category codes of the code catalogue entries in group PURCHASE_STANDARD_RATED.
 */
data class ScopeRulesData(
    val lineVerdicts: Map<String, ScopeVerdict>,
    val intraGroupTreatment: String?,
    val purchaseStandardRatedCodes: Set<String>,
)

/**
 * The scope rules as pure code (INV-REQ-376, INV-REQ-377, INV-REQ-378, INV-REQ-738, INV-REQ-720). Deny by default:
 * anything the data does not name is NOT_SUBMITTABLE. The first matching step wins.
 */
class ScopeRules(
    private val data: ScopeRulesData,
) : ScopeVerdictPort {
    override fun verdictOf(facts: ScopeFacts): ScopeVerdict = decide(facts).verdict

    override fun outcomeOf(facts: ScopeFacts): ScopeOutcome = decide(facts)

    private fun decide(f: ScopeFacts): ScopeOutcome =
        windowVerdict(f)?.let(::plain)
            ?: (f.line as? LineCoding.Coded)?.let { decideCoded(f, it.categoryCode) }
            ?: plain(ScopeVerdict.NOT_SUBMITTABLE) // step 2: a refused line

    /** Step 1: the registration window; an absent obligation start date fails closed. */
    private fun windowVerdict(f: ScopeFacts): ScopeVerdict? {
        val start = f.obligationStartDate
        val end = f.registrationEndDate
        return when {
            start == null || f.date.value < start.value -> ScopeVerdict.PRE_OBLIGATION
            end != null && f.date.value > end.value -> ScopeVerdict.NOT_SUBMITTABLE
            else -> null
        }
    }

    /** Steps 3 to 7 for a coded line that is inside the window. */
    private fun decideCoded(
        f: ScopeFacts,
        mapped: String,
    ): ScopeOutcome {
        val apportioned = PartialExemption.apportionedCode(f.direction, f.exemption)
        val code = apportioned ?: mapped
        val lineVerdict = data.lineVerdicts[code]
        val purchase = f.direction == ScopeDirection.PURCHASE
        val partial = purchase && f.exemption.partiallyExempt
        val exclusion = if (purchase) exclusionOf(f, mapped, partial) else null
        return when {
            // Step 3: intra-group treatment other than PASS_THROUGH or absent.
            f.sameGroup && data.intraGroupTreatment != PASS_THROUGH -> {
                plain(ScopeVerdict.NOT_SUBMITTABLE)
            }

            // Step 4: DS is EXCLUDED_TXN in either mode whatever the data says (INV-REQ-493, INV-Q-16736).
            code == "DS" -> {
                plain(ScopeVerdict.EXCLUDED_TXN)
            }

            // Steps 4a and 4b: a disallowed expense, and the purchases the recorded election names.
            exclusion != null -> {
                exclusion
            }

            // Step 4c: a code the data lacks.
            lineVerdict == null -> {
                plain(ScopeVerdict.NOT_SUBMITTABLE)
            }

            else -> {
                ScopeOutcome(
                    overrideVerdict(f, code, partial) ?: lineVerdict,
                    f.sameGroup && lineVerdict == ScopeVerdict.IN_SCOPE,
                    traceOf(partial, apportioned, mapped),
                    apportioned,
                )
            }
        }
    }

    /**
     * Step 4a: a disallowed expense (BL) is out of scope for every entity (INV-REQ-367, INV-BR-1473). Step 4b: the
     * recorded election excludes the purchases coded with the codes it names (INV-REQ-369), for a partially exempt
     * entity only. Both name themselves in the decision trace.
     */
    private fun exclusionOf(
        f: ScopeFacts,
        mapped: String,
        partial: Boolean,
    ): ScopeOutcome? {
        val election = f.exemption.election
        return when {
            mapped == PartialExemption.DISALLOWED -> {
                // OUT_OF_SCOPE whatever the pack lists for BL (coordinator ruling on the verdicts, INV-T-4969)
                val entry = TraceEntry(PartialExemption.RULE_NON_REPORTABLE, mapOf("categoryCode" to mapped))
                ScopeOutcome(ScopeVerdict.OUT_OF_SCOPE, false, listOf(entry))
            }

            partial && election != null && election.names(f.internalTaxCode, mapped) -> {
                val codes = election.codes.sorted().joinToString(",")
                ScopeOutcome(
                    ScopeVerdict.OUT_OF_SCOPE,
                    false,
                    listOf(
                        partialEntry(),
                        TraceEntry(
                            PartialExemption.RULE_EXEMPT_ELECTION,
                            mapOf(
                                "codes" to codes,
                                "basis" to election.basis,
                            ),
                        ),
                    ),
                )
            }

            else -> {
                null
            }
        }
    }

    private fun partialEntry() = TraceEntry(PartialExemption.RULE_PARTIAL_EXEMPTION, mapOf("partiallyExempt" to "true"))

    private fun traceOf(
        partial: Boolean,
        apportioned: String?,
        mapped: String,
    ): List<TraceEntry> =
        listOfNotNull(
            if (partial) partialEntry() else null,
            apportioned?.let {
                TraceEntry(PartialExemption.RULE_APPORTIONMENT, mapOf("mappedCode" to mapped, "categoryCode" to it))
            },
        )

    /**
     * Step 5 (a purchase with no input tax claimed; not for a partially exempt entity, which submits it whether or
     * not the tax is claimed, INV-REQ-367) and step 6 (OS under voluntary over-submission).
     */
    private fun overrideVerdict(
        f: ScopeFacts,
        code: String,
        partial: Boolean,
    ): ScopeVerdict? =
        when {
            !partial &&
                f.direction == ScopeDirection.PURCHASE &&
                code in data.purchaseStandardRatedCodes &&
                f.inputTaxClaimedNonZero == false -> {
                if (f.voluntaryOverSubmission) ScopeVerdict.SUBMITTABLE else ScopeVerdict.OUT_OF_SCOPE
            }

            // INV-REQ-2437 behavior 2: an import permit mapped to ME, IGDS or IM is transmitted only under VP-21 ON
            (code == "OS" || code in IMPORT_PERMIT_CODES) && f.voluntaryOverSubmission -> {
                ScopeVerdict.SUBMITTABLE
            }

            else -> {
                null
            }
        }

    private fun plain(v: ScopeVerdict) = ScopeOutcome(v, disregardedSupplyWarning = false)

    private companion object {
        const val PASS_THROUGH = "PASS_THROUGH"
        val IMPORT_PERMIT_CODES = setOf("ME", "IGDS", "IM")
    }
}
