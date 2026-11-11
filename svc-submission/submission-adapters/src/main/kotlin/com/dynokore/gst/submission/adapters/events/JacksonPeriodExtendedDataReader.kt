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
        val data = parse(dataJson)
        val target = enumOf<ExtensionTarget>(data.text("target"), "target")
        val filing = data.date("newFilingDueDate")
        val submission = data.date("newSubmissionDueDate")
        requireDates(target, filing, submission)
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

    private fun refuse(
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw ContractViolation(message, cause)

    private fun parse(dataJson: String): JsonNode {
        val data =
            try {
                mapper.readTree(dataJson)
            } catch (e: JacksonException) {
                refuse("PeriodExtended data is not JSON", e)
            }
        if (data == null || !data.isObject) refuse("PeriodExtended data is not a JSON object")
        return data
    }

    private fun requireDates(
        target: ExtensionTarget,
        filing: LocalDate?,
        submission: LocalDate?,
    ) {
        val filingMissing = target != ExtensionTarget.SUBMISSION_DUE_DATE && filing == null
        val submissionMissing = target != ExtensionTarget.FILING_DUE_DATE && submission == null
        if (filingMissing || submissionMissing) refuse("PeriodExtended: the new date of target $target is missing")
    }

    private fun scopeOf(node: JsonNode?): PeriodExtendedScope {
        if (node == null || !node.isObject) refuse("PeriodExtended scope is missing or not an object")
        val kind = enumOf<ExtensionScopeKind>(node.text("kind"), "scope kind")
        return when (kind) {
            ExtensionScopeKind.PERIOD -> {
                PeriodExtendedScope(kind)
            }

            ExtensionScopeKind.DOCUMENT_SET -> {
                PeriodExtendedScope(
                    kind,
                    documentRefs = refsOf(node.get("documentRefs")),
                )
            }

            ExtensionScopeKind.DATE_RANGE -> {
                rangeOf(node)
            }
        }
    }

    private fun rangeOf(node: JsonNode): PeriodExtendedScope {
        val from = node.date("dateFrom")
        val to = node.date("dateTo")
        if (from == null || to == null) refuse("PeriodExtended scope DATE_RANGE needs both dates")
        return PeriodExtendedScope(ExtensionScopeKind.DATE_RANGE, dateFrom = from, dateTo = to)
    }

    private fun refsOf(refs: JsonNode?): List<String> {
        if (refs == null || !refs.isArray ||
            refs.isEmpty
        ) {
            refuse("PeriodExtended scope DOCUMENT_SET has no documentRefs")
        }
        return refs.values().map {
            if (!it.isString || it.asString().isEmpty()) refuse("PeriodExtended documentRefs holds a non-string entry")
            it.asString()
        }
    }

    private inline fun <reified E : Enum<E>> enumOf(
        text: String,
        member: String,
    ): E =
        enumValues<E>().firstOrNull { it.name == text }
            ?: refuse("PeriodExtended $member is not one of the allowed values")

    private fun JsonNode.text(name: String): String {
        val node = get(name)
        if (node == null || !node.isString || node.asString().isEmpty()) {
            refuse("PeriodExtended data member $name is missing or not a non-empty string")
        }
        return node.asString()
    }

    /** Null when absent or JSON null; a [ContractViolation] when present and not a date. */
    private fun JsonNode.date(name: String): LocalDate? {
        val node = get(name)
        if (node == null || node.isNull) return null
        if (!node.isString) refuse("PeriodExtended data member $name is not a date")
        return try {
            LocalDate.parse(node.asString())
        } catch (e: DateTimeParseException) {
            refuse("PeriodExtended data member $name is not a date", e)
        }
    }
}
