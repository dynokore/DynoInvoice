package gst.catalogue

/** How a `BLOCK` code's cause is classified (INV-REQ-1186 / FR-O.9). */
enum class Cause {
    OWN_CONTROL,
    FIX_AT_SOURCE,
    STRUCTURAL_INVALIDITY,
}

/**
 * INV-AC-2173: every `BLOCK` code's disposition follows its cause — `OVERRIDABLE` for
 * the System's own control, `MANUAL` where the fix is at source, `TERMINAL` where
 * proceeding would corrupt the record at IRAS (INV-REQ-1186).
 *
 * [knownCauses] holds only the codes INV-REQ-1186's live Keel text names explicitly
 * (behaviour #1-4: MDM-003, PAY-006, PAY-001, PAY-010, SUB-006, SUB-009, RUL-005).
 * INV-NOTE-3 §21.2, which classifies every other `BLOCK` row's cause, is an archived
 * document this task does not read (CODER-BRIEF); a `BLOCK` code outside this map is
 * exactly the acceptance criterion's "any other combination" — [Result.pendingDecision]
 * lists it rather than guessing its cause, so the check stays honest about what it
 * actually verified. Widening [knownCauses] is a follow-up once that classification is
 * itself live Keel text (or a decision record) rather than archive-only.
 *
 * A structural invariant holds for every `BLOCK` row regardless of classification:
 * INV-REQ-1186 only ever calls for `OVERRIDABLE`, `MANUAL` or `TERMINAL` on `BLOCK` —
 * `AUTO_RETRY`/`AUTO_RELEASE` pair with `WARN`/`HOLD`, never `BLOCK`. A row breaking
 * that invariant is a genuine violation, not a pending decision.
 */
object BlockDispositionChecker {
    private val knownCauses: Map<String, Cause> =
        mapOf(
            "MDM-003" to Cause.OWN_CONTROL,
            "PAY-006" to Cause.OWN_CONTROL,
            "PAY-001" to Cause.FIX_AT_SOURCE,
            "PAY-010" to Cause.FIX_AT_SOURCE,
            "SUB-006" to Cause.STRUCTURAL_INVALIDITY,
            "SUB-009" to Cause.STRUCTURAL_INVALIDITY,
            "RUL-005" to Cause.FIX_AT_SOURCE,
        )

    private val expectedDisposition =
        mapOf(
            Cause.OWN_CONTROL to Disposition.OVERRIDABLE,
            Cause.FIX_AT_SOURCE to Disposition.MANUAL,
            Cause.STRUCTURAL_INVALIDITY to Disposition.TERMINAL,
        )

    private val validBlockDispositions = setOf(Disposition.OVERRIDABLE, Disposition.MANUAL, Disposition.TERMINAL)

    data class Result(
        val violations: List<String>,
        val classified: List<String>,
        val pendingDecision: List<String>,
    ) {
        val passed: Boolean get() = violations.isEmpty()
    }

    fun check(rows: List<ParsedErrorCode>): Result {
        val violations = mutableListOf<String>()
        val classified = mutableListOf<String>()
        val pending = mutableListOf<String>()

        rows.filter { it.severity == Severity.BLOCK }.forEach { row ->
            val disposition = row.disposition
            if (disposition !in validBlockDispositions) {
                violations +=
                    "${row.code}: BLOCK with disposition $disposition — not one of " +
                        "OVERRIDABLE/MANUAL/TERMINAL (INV-REQ-1186)"
                return@forEach
            }
            val cause = knownCauses[row.code]
            if (cause == null) {
                pending += "${row.code}: BLOCK/$disposition — cause not classified in live Keel text, listed for decision"
                return@forEach
            }
            val expected = expectedDisposition.getValue(cause)
            if (disposition != expected) {
                violations += "${row.code}: classified $cause calls for $expected, catalogue has $disposition (INV-REQ-1186)"
            } else {
                classified += "${row.code}: $cause -> $disposition"
            }
        }
        return Result(violations, classified, pending)
    }
}
