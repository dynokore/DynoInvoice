package com.dynokore.gst.controlPlane.adapters.corpus

import com.dynokore.platform.rulesevaluation.EvaluationProfile
import com.dynokore.platform.rulesevaluation.coding.MappingEntry
import com.dynokore.platform.rulesevaluation.coding.TenantMapping
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
    private const val PROFILE_FIXTURE = CorpusLoader.PROFILE_FIXTURE
    private val PROFILE_MEMBERS =
        setOf("obligationStartDate", "registrationEndDate", "voluntaryOverSubmission", "partiallyExempt", "election")

    fun mappingOf(node: JsonNode): TenantMapping {
        check(node.isObject && node.size() > 0) { "$MAPPING_FIXTURE is not a non-empty object" }
        return TenantMapping(
            node.propertyNames().map { code ->
                val value = node.get(code)
                check(value.isString) { "$MAPPING_FIXTURE: the value of $code is not a string" }
                MappingEntry(code, value.asString())
            },
        )
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
