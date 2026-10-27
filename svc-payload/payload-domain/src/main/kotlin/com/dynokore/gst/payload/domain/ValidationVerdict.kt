package com.dynokore.gst.payload.domain

/** The Peppol flag of a PINT-SG Schematron rule. */
enum class PeppolFlag {
    FATAL,
    WARNING,
}

/** The severity of a finding; the names equal the `severity` values BLOCK and WARN of the error-code catalogue. */
enum class Severity {
    BLOCK,
    WARN,
}

/** One failed Schematron assertion. */
data class SchematronFailure(
    val ruleId: String,
    val flag: PeppolFlag,
)

/** One entry of the severity override pack: the rule is escalated for the listed specification versions. */
data class SeverityEscalation(
    val ruleId: String,
    val specificationVersions: Set<String>,
)

/** A Schematron failure with its severity and error code; the code is null for a warning. */
data class ClassifiedFinding(
    val ruleId: String,
    val severity: Severity,
    val code: String?,
)

/** Layer 1 severity (INV-REQ-498, INV-REQ-470 behavior 1; INV-Q-6786 A), a pure function. */
object LayerOneSeverity {
    /** Catalogue code of a Schematron assertion failure (error-codes.yaml at platform-contracts 10ad8c2). */
    const val PAY_002 = "PAY-002"

    /** Catalogue code of an absent mandatory data element (error-codes.yaml at platform-contracts 10ad8c2). */
    const val PAY_001 = "PAY-001"

    /**
     * FATAL is BLOCK with PAY-002 whatever [escalations] hold. WARNING is BLOCK with PAY-001 when an escalation has an
     * equal rule id and lists [specificationVersion] (exact, case-sensitive); any other WARNING is WARN without a code.
     */
    fun classify(
        failure: SchematronFailure,
        specificationVersion: String,
        escalations: List<SeverityEscalation>,
    ): ClassifiedFinding =
        when (failure.flag) {
            PeppolFlag.FATAL -> ClassifiedFinding(failure.ruleId, Severity.BLOCK, PAY_002)
            PeppolFlag.WARNING ->
                if (escalations.any { it.ruleId == failure.ruleId && specificationVersion in it.specificationVersions }) {
                    ClassifiedFinding(failure.ruleId, Severity.BLOCK, PAY_001)
                } else {
                    ClassifiedFinding(failure.ruleId, Severity.WARN, null)
                }
        }
}

/** The verdict that holds both validation layers (INV-REQ-470 behavior 3). */
sealed interface ValidationVerdict {
    data class Passed(
        val warnings: List<ClassifiedFinding>,
    ) : ValidationVerdict

    data class Blocked(
        val blocks: List<ClassifiedFinding>,
        val missing: List<String>,
        val warnings: List<ClassifiedFinding>,
    ) : ValidationVerdict {
        /** The distinct codes, PAY-002 before PAY-001: PAY-001 for a missing MDE or an escalated block. */
        fun codes(): List<String> {
            val present = blocks.mapNotNull { it.code }.toMutableSet()
            if (missing.isNotEmpty()) present += LayerOneSeverity.PAY_001
            return listOf(LayerOneSeverity.PAY_002, LayerOneSeverity.PAY_001).filter { it in present }
        }
    }

    companion object {
        /** Passed when no layer-one finding is BLOCK and [layerTwo] is Complete; else Blocked. */
        fun of(
            layerOne: List<ClassifiedFinding>,
            layerTwo: MdeResult,
        ): ValidationVerdict {
            val blocks = layerOne.filter { it.severity == Severity.BLOCK }
            val warnings = layerOne.filter { it.severity == Severity.WARN }
            val missing = (layerTwo as? MdeResult.Missing)?.businessTermIds.orEmpty()
            return if (blocks.isEmpty() && layerTwo is MdeResult.Complete) {
                Passed(warnings)
            } else {
                Blocked(blocks, missing, warnings)
            }
        }
    }
}
