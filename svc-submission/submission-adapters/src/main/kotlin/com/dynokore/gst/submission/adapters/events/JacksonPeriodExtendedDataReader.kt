package com.dynokore.gst.submission.adapters.events

import com.dynokore.gst.submission.application.extension.ExtensionScopeKind
import com.dynokore.gst.submission.application.extension.ExtensionTarget
import com.dynokore.gst.submission.application.extension.PeriodExtendedData
import com.dynokore.gst.submission.application.extension.PeriodExtendedDataReader
import com.dynokore.gst.submission.application.extension.PeriodExtendedScope
import com.dynokore.platform.outboxandqueue.ContractViolation
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * [PeriodExtendedDataReader] over Jackson (period-extended-payload.schema.json). A missing or malformed member is a
 * [ContractViolation]; enum values match exactly (no case folding). Messages name the member, never a value.
 */
class JacksonPeriodExtendedDataReader : PeriodExtendedDataReader {
    private val mapper = ObjectMapper()

    override fun read(dataJson: String): PeriodExtendedData {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                throw ContractViolation("PeriodExtended data is not JSON", e)
            }
        if (data == null || !data.isObject) throw ContractViolation("PeriodExtended data is not a JSON object")
        val target = enumOf<ExtensionTarget>(data.text("target"), "target")
        val filing = data.date("newFilingDueDate")
        val submission = data.date("newSubmissionDueDate")
        val filingNeeded = target != ExtensionTarget.SUBMISSION_DUE_DATE
        val submissionNeeded = target != ExtensionTarget.FILING_DUE_DATE
        if ((filingNeeded && filing == null) || (submissionNeeded && submission == null)) {
            throw ContractViolation("PeriodExtended: the new date of target $target is missing")
        }
        return PeriodExtendedData(
            entityRef = data.text("entityRef"),
            period = data.text("period"),
            target = target,
            newFilingDueDate = filing,
            newSubmissionDueDate = submission,
            scope = scopeOf(data.get("scope")),
            irasReference = data.text("irasReference"),
        )
    }

    private fun scopeOf(node: JsonNode?): PeriodExtendedScope {
        if (node == null || !node.isObject) throw ContractViolation("PeriodExtended scope is missing or not an object")
        val kind = enumOf<ExtensionScopeKind>(node.text("kind"), "scope kind")
        return when (kind) {
            ExtensionScopeKind.PERIOD -> {
                PeriodExtendedScope(kind)
            }

            ExtensionScopeKind.DOCUMENT_SET -> {
                val refs = node.get("documentRefs")
                if (refs == null || !refs.isArray || refs.isEmpty) {
                    throw ContractViolation("PeriodExtended scope DOCUMENT_SET has no documentRefs")
                }
                val list =
                    refs.values().map {
                        if (!it.isString || it.asString().isEmpty()) {
                            throw ContractViolation("PeriodExtended documentRefs holds a non-string entry")
                        }
                        it.asString()
                    }
                PeriodExtendedScope(kind, documentRefs = list)
            }

            ExtensionScopeKind.DATE_RANGE -> {
                val from = node.date("dateFrom")
                val to = node.date("dateTo")
                if (from == null || to == null) throw ContractViolation("PeriodExtended scope DATE_RANGE needs both dates")
                PeriodExtendedScope(kind, dateFrom = from, dateTo = to)
            }
        }
    }

    private inline fun <reified E : Enum<E>> enumOf(
        text: String,
        member: String,
    ): E =
        enumValues<E>().firstOrNull { it.name == text }
            ?: throw ContractViolation("PeriodExtended $member is not one of the allowed values")

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            throw ContractViolation("PeriodExtended data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    /** Null when absent or JSON null; a [ContractViolation] when present and not a date. */
    private fun JsonNode.date(name: String): LocalDate? {
        val node = get(name)
        if (node == null || node.isNull) return null
        if (!node.isString) throw ContractViolation("PeriodExtended data member $name is not a date")
        return try {
            LocalDate.parse(node.asString())
        } catch (e: DateTimeParseException) {
            throw ContractViolation("PeriodExtended data member $name is not a date", e)
        }
    }
}
