package com.dynokore.gst.controlPlane.adapters.corpus

import com.dynokore.platform.rulesevaluation.EvaluationProfile
import com.dynokore.platform.rulesevaluation.coding.MappingEntry
import com.dynokore.platform.rulesevaluation.coding.TenantMapping
import com.dynokore.platform.rulesevaluation.packs.MappingPackContents
import com.dynokore.platform.rulesevaluation.packs.PackContentsFailure
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The string member [member] of [node]; anything else, or no member, is a refusal naming [where]. */
internal fun jsonText(
    node: JsonNode,
    member: String,
    where: String,
): String {
    val value = node.get(member)
    check(value != null && value.isString) { "$where: $member is missing or not a string" }
    return value.asString()
}

/** The two fixtures that carry the evaluation inputs of the run (INV-Q-10094 and the profile input). */
internal object CorpusFixtures {
    private const val MAPPING_FIXTURE = CorpusLoader.MAPPING_FIXTURE
    private const val SCHEME_FIXTURE = CorpusLoader.MAPPING_SCHEME_FIXTURE
    private const val PROFILE_FIXTURE = CorpusLoader.PROFILE_FIXTURE
    private val PROFILE_MEMBERS =
        setOf("obligationStartDate", "registrationEndDate", "voluntaryOverSubmission", "partiallyExempt", "election")

    /**
     * The tenant mapping: the flat entries of `mapping-fixture.json` and the scheme-tagged entries of
     * `mapping-scheme-fixture.json` (published mapping-pack-contents format). A code held by both files, a scheme
     * entry without a scheme tag, or a scheme file the decoder refuses is a refusal; the files are never merged by
     * overwriting.
     */
    fun mappingOf(
        flat: JsonNode,
        scheme: JsonNode,
        schemeBytes: ByteArray,
    ): TenantMapping {
        check(flat.isObject && flat.size() > 0) { "$MAPPING_FIXTURE is not a non-empty object" }
        check(scheme.isObject) { "$SCHEME_FIXTURE is not an object" }
        val flatEntries =
            flat.propertyNames().map { code ->
                val value = flat.get(code)
                check(value.isString) { "$MAPPING_FIXTURE: the value of $code is not a string" }
                MappingEntry(code, value.asString())
            }
        val schemeEntries =
            try {
                MappingPackContents.decode(schemeBytes)
            } catch (e: PackContentsFailure) {
                throw IllegalStateException("$SCHEME_FIXTURE is not in the mapping-pack-contents format", e)
            }
        check(schemeEntries.isNotEmpty()) { "$SCHEME_FIXTURE holds no entry" }
        schemeEntries.forEach {
            check(!it.schemeTag.isNullOrBlank()) { "$SCHEME_FIXTURE: ${it.internalTaxCode} has no schemeTag" }
        }
        val twice =
            schemeEntries.map { it.internalTaxCode }.toSet() intersect flatEntries.map { it.internalTaxCode }.toSet()
        check(twice.isEmpty()) { "$SCHEME_FIXTURE and $MAPPING_FIXTURE both map $twice" }
        return TenantMapping(flatEntries + schemeEntries)
    }

    fun profileOf(node: JsonNode): EvaluationProfile {
        check(node.isObject && node.propertyNames().toSet() == PROFILE_MEMBERS) {
            "$PROFILE_FIXTURE does not hold exactly the members $PROFILE_MEMBERS"
        }
        check(node.get("election").isNull) { "$PROFILE_FIXTURE: election is not null" }
        return EvaluationProfile(
            obligationStartDate = date(node, "obligationStartDate"),
            registrationEndDate =
                node
                    .get("registrationEndDate")
                    .takeUnless {
                        it.isNull
                    }?.let { date(node, "registrationEndDate") },
            voluntaryOverSubmission = flag(node, "voluntaryOverSubmission"),
            partiallyExempt = flag(node, "partiallyExempt"),
            election = null,
        )
    }

    private fun flag(
        node: JsonNode,
        member: String,
    ): Boolean {
        val value = node.get(member)
        check(value != null && value.isBoolean) { "$PROFILE_FIXTURE: $member is not a boolean" }
        return value.asBoolean()
    }

    private fun date(
        node: JsonNode,
        member: String,
    ): LocalDate =
        try {
            LocalDate.parse(jsonText(node, member, PROFILE_FIXTURE))
        } catch (e: DateTimeParseException) {
            throw IllegalStateException("$PROFILE_FIXTURE: $member is not an ISO date", e)
        }
}
