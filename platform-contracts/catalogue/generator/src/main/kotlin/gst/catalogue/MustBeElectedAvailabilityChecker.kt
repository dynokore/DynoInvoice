package gst.catalogue

/**
 * INV-AC-2236: every must-be-elected variation point, and every one that closes an
 * open question, is `available`.
 *
 * [mustBeElected] is structural — [ParsedVariationPoint.mustBeElected] itself, read
 * straight off the vendored catalogue's own `must_be_elected` field (INV-REQ-1251
 * behaviour #5). The open-question-closing set is not: unlike a `must_be_elected`
 * flag, "closes a source open question (INV-NOTE-2 §30.2)" is not itself a field the
 * vendored catalogue carries, and INV-NOTE-4 (the open-question register the AC's
 * `row:Q-n` anchors point into) is an archived document this task does not read
 * (CODER-BRIEF). So, exactly as [BlockDispositionChecker] does for INV-AC-2173's
 * cause classification, [namedQuestionClosingRequirements] is limited to only the
 * requirement keys **INV-AC-2236's own live Keel text names**: INV-REQ-1309 to
 * INV-REQ-1315 (closing INV-NOTE-4#row:Q-5/Q-8/Q-9/Q-14/Q-23) and INV-REQ-1323
 * (closing INV-NOTE-4#row:Q-25). A future variation point that closes some other open
 * question this AC does not name is not checked here — nothing in live Keel text
 * would tell this checker to.
 */
object MustBeElectedAvailabilityChecker {
    // INV-AC-2236's own named set — do not extend this from inference (e.g. a
    // behaviour text's "Recommended through INV-NOTE-3#row:OQ-n": many more variation
    // points carry that same recommendation without being named by this AC).
    val namedQuestionClosingRequirements: Set<String> =
        setOf(
            "INV-REQ-1309",
            "INV-REQ-1310",
            "INV-REQ-1311",
            "INV-REQ-1312",
            "INV-REQ-1313",
            "INV-REQ-1314",
            "INV-REQ-1315",
            "INV-REQ-1323",
        )

    data class Violation(val id: String, val reason: String)

    fun check(rows: List<ParsedVariationPoint>): List<Violation> {
        val violations = mutableListOf<Violation>()
        rows.forEach { row ->
            val mustCheck = row.mustBeElected || row.requirement in namedQuestionClosingRequirements
            if (mustCheck && row.status != VpStatus.AVAILABLE) {
                val reason =
                    if (row.mustBeElected) {
                        "must be elected (INV-REQ-1251 behaviour #5) but status is ${row.status}"
                    } else {
                        "closes a source open question named by INV-AC-2236 but status is ${row.status}"
                    }
                violations += Violation(row.id, reason)
            }
        }
        return violations
    }
}
