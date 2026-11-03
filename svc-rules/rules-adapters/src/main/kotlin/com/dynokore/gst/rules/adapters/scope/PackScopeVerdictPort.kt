package com.dynokore.gst.rules.adapters.scope

import com.dynokore.gst.rules.adapters.packs.ScopePackContents
import com.dynokore.gst.rules.application.CodeCataloguePort
import com.dynokore.gst.rules.domain.resolution.PackLookup
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.gst.rules.domain.scope.ScopeFacts
import com.dynokore.gst.rules.domain.scope.ScopeOutcome
import com.dynokore.gst.rules.domain.scope.ScopeRules
import com.dynokore.gst.rules.domain.scope.ScopeRulesData
import com.dynokore.gst.rules.domain.scope.ScopeVerdict
import com.dynokore.gst.rules.domain.scope.ScopeVerdictPort
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackVersion

/** No SCOPE pack version is armed on [date]: the caller answers RUL-002, a verdict is never invented. */
class ScopePackMissing(
    val date: TransactionDate,
) : IllegalStateException("no scope pack is armed on $date")

/**
 * The production [ScopeVerdictPort] (INV-Q-13820, INV-Q-13824): the SCOPE pack version effective at the transaction date
 * is resolved through the armed-pack lookup and read from the holder copy ([artefacts] is
 * `PersistedPackRegistry.artefactOf`); `lineScopeRules`, `intraGroupRule.treatment` and the catalogue codes of group
 * PURCHASE_STANDARD_RATED become the data of the pure [ScopeRules], which decides.
 */
class PackScopeVerdictPort(
    private val lookup: PackLookup,
    private val artefacts: (PackVersion) -> PackArtefact?,
    private val catalogue: CodeCataloguePort,
) : ScopeVerdictPort {
    override fun verdictOf(facts: ScopeFacts): ScopeVerdict = outcomeOf(facts).verdict

    override fun outcomeOf(facts: ScopeFacts): ScopeOutcome = ScopeRules(dataAt(facts)).outcomeOf(facts)

    private fun dataAt(facts: ScopeFacts): ScopeRulesData {
        val version = lookup.resolve(PackTypes.SCOPE, facts.tenant, facts.date.value) ?: throw ScopePackMissing(facts.date)
        val artefact =
            checkNotNull(artefacts(version)) {
                "the SCOPE version ${version.version.value} is resolved but its artefact is not held"
            }
        val scope = ScopePackContents.decodeScopeData(artefact.contents)
        val purchaseStandardRated =
            catalogue
                .catalogueAt(facts.tenant, facts.date)
                ?.entries
                ?.filter { it.group == PURCHASE_STANDARD_RATED }
                ?.map { it.code }
                ?.toSet()
                .orEmpty()
        return ScopeRulesData(scope.lineVerdicts, scope.intraGroupTreatment, purchaseStandardRated)
    }

    private companion object {
        const val PURCHASE_STANDARD_RATED = "PURCHASE_STANDARD_RATED"
    }
}
