package com.dynokore.gst.payload.domain

/** The IRAS obligation of a business term (INV-REQ-463, INV-REQ-483); the names equal the MDE pack contract values. */
enum class IrasStatus {
    MANDATORY,
    MANDATORY_IN_SCENARIOS,
    NOT_MDE,
}

/** The InvoiceNow submission type an MDE row is bound to; the names equal the MDE pack contract values. */
enum class SubmissionType {
    TYPE_1A,
    TYPE_1B,
    TYPE_2,
    TYPE_3,
}

/** One row of the MDE pack, as a plain value. No PINT-SG cardinality and no specification version is carried. */
data class MdeRow(
    val businessTermId: String,
    val documentTypeCodes: Set<String>,
    val submissionTypes: Set<SubmissionType>,
    val irasStatus: IrasStatus,
    val requiredInScenarios: Set<String>,
)

/** The outcome of the Layer 2 check. */
sealed interface MdeResult {
    data object Complete : MdeResult

    data class Missing(
        val businessTermIds: List<String>,
    ) : MdeResult
}

/** Layer 2, the IRAS MDE completeness check (INV-REQ-463, INV-REQ-464, INV-REQ-483), a pure function. */
object MdeCompleteness {
    private val DOCUMENT_TYPE_CODES = setOf("380", "381")

    /**
     * The business terms the IRAS obligation requires for [documentTypeCode], [submissionType] and [scenarios] that
     * [values] does not populate. A term is populated when its entry is not null and not blank.
     *
     * @throws IllegalArgumentException when [documentTypeCode] is not "380" or "381"
     */
    fun check(
        rows: List<MdeRow>,
        documentTypeCode: String,
        submissionType: SubmissionType,
        scenarios: Set<String>,
        values: Map<String, String?>,
    ): MdeResult {
        require(documentTypeCode in DOCUMENT_TYPE_CODES) { "documentTypeCode must be 380 or 381" }
        val missing =
            rows
                .asSequence()
                .filter { documentTypeCode in it.documentTypeCodes && submissionType in it.submissionTypes }
                .filter { isRequired(it, scenarios) }
                .map { it.businessTermId }
                .filterNot { values[it]?.isNotBlank() == true }
                .distinct()
                .sorted()
                .toList()
        return if (missing.isEmpty()) MdeResult.Complete else MdeResult.Missing(missing)
    }

    private fun isRequired(
        row: MdeRow,
        scenarios: Set<String>,
    ): Boolean =
        when (row.irasStatus) {
            IrasStatus.MANDATORY -> true
            IrasStatus.MANDATORY_IN_SCENARIOS -> row.requiredInScenarios.any { it in scenarios }
            IrasStatus.NOT_MDE -> false
        }
}
