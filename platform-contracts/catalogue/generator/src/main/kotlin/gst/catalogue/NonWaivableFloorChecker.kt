package gst.catalogue

/**
 * INV-AC-1786: no variation point offers an option looser than a force-of-law
 * requirement.
 *
 * This AC has two halves (INV-REQ-780 behaviour #1-2). Only the first is checked
 * here:
 *
 * 1. **Checked.** "A `CONTROL` marked non-waivable has no looser option; a looser
 *    option of any other `CONTROL` needs a recorded acknowledgement." This is
 *    structural — visible entirely from the vendored catalogue's own `class`,
 *    `class_modifiers`, `options`, `bounds` and `title` fields, with no archived
 *    document to consult.
 * 2. **Not checked.** "No variation point, override path or deployment variant
 *    offers an option that relaxes a row of INV-NOTE-2 §26.3" — the force-of-law
 *    table itself lives only in an archived document (SDS §26.3), which
 *    CODER-BRIEF's rules forbid this repository's coding agents from reading.
 *    Verifying this half needs that table made live Keel text first (a follow-up,
 *    named here rather than silently assumed — the same discipline
 *    [BlockDispositionChecker] applies to INV-NOTE-3 §21.2 for INV-AC-2173).
 *
 * For the checked half: a `CONTROL` whose modifiers include [VpClassModifier.TIGHTEN_ONLY]
 * passes structurally (every option is, by construction, at least as strict as the
 * default — there is no direction in which an option could be looser). A `CONTROL`
 * marked [VpClassModifier.NON_WAIVABLE] or [VpClassModifier.NON_WAIVABLE_IN_PART] is
 * checked against [nonWaivableCompliant], a small hardcoded map — exactly
 * [BlockDispositionChecker]'s own pattern — because "does option X loosen the fixed
 * part of this control" is a judgment call this checker makes explicit and citable
 * per id, not a general algorithm; a future non-waivable `CONTROL` not in the map is
 * listed for decision, not silently passed. A plain `CONTROL` (no such modifier) with
 * more than one option must show a recorded-acknowledgement marker somewhere in its
 * own text (`bounds`, `title` or an option string) — this part *is* a general,
 * re-runnable check, since INV-REQ-780 behaviour #1's rule for this case has no
 * archive-only classification to hide behind: every one of today's catalogue rows
 * that needs one states it in its own live Keel text.
 */
object NonWaivableFloorChecker {
    // Hardcoded, cited judgment for each CONTROL modified non-waivable (in full or in
    // part) as of this task (INV-T-2852): every option each offers keeps the control's
    // fixed part intact, per that row's own `bounds` text quoted in the comment.
    private val nonWaivableCompliant: Map<String, String> =
        mapOf(
            // "Always two different people (INV-REQ-1007)" — both options name a
            // different second person; neither drops the two-person requirement.
            "VP-09" to "both options keep \"always two different people\" (bounds)",
            // "Always two people, a restricted action set and ACC-002" — the "in
            // part" duration (1-8h) is the waivable part; the two-person control
            // itself is unconditional across both options.
            "VP-08" to "both options keep \"always two people\" (bounds); only the duration varies (non-waivable in part)",
        )

    private val acknowledgementMarker = Regex("acknowledg|approv|confirm|consent|sign-?off", RegexOption.IGNORE_CASE)

    data class Result(
        val violations: List<String>,
        val classified: List<String>,
        val pendingDecision: List<String>,
    ) {
        val passed: Boolean get() = violations.isEmpty()
    }

    fun check(rows: List<ParsedVariationPoint>): Result {
        val violations = mutableListOf<String>()
        val classified = mutableListOf<String>()
        val pending = mutableListOf<String>()

        rows.filter { it.vpClass == VpClass.CONTROL }.forEach { row ->
            when {
                VpClassModifier.TIGHTEN_ONLY in row.classModifiers -> {
                    classified += "${row.id}: tighten-only — no option can be looser than the default by construction"
                }
                VpClassModifier.NON_WAIVABLE in row.classModifiers ||
                    VpClassModifier.NON_WAIVABLE_IN_PART in row.classModifiers -> {
                    val note = nonWaivableCompliant[row.id]
                    if (note != null) {
                        classified += "${row.id}: non-waivable, compliant — $note"
                    } else {
                        pending +=
                            "${row.id}: marked non-waivable but not in NonWaivableFloorChecker's cited map — " +
                                "listed for decision, not guessed"
                    }
                }
                row.options.size <= 1 -> {
                    classified += "${row.id}: one option, nothing looser to offer"
                }
                else -> {
                    val text = listOfNotNull(row.bounds, row.title).joinToString(" ") + " " + row.options.joinToString(" ")
                    if (acknowledgementMarker.containsMatchIn(text)) {
                        classified += "${row.id}: looser option carries a recorded-acknowledgement marker in its own text"
                    } else {
                        violations +=
                            "${row.id}: CONTROL with ${row.options.size} options and no recorded-acknowledgement " +
                                "marker for the looser one (INV-REQ-780 behaviour #1)"
                    }
                }
            }
        }
        return Result(violations, classified, pending)
    }
}
