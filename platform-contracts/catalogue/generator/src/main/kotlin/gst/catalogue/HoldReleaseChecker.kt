package gst.catalogue

/**
 * INV-REQ-1181 behaviours 1 and 3 (INV-Q-7282 A): every `HOLD` code names the event that
 * resolves it. A `HOLD` row with `AUTO_RELEASE` carries `AUTO_RELEASE on <event>` or
 * `AUTO_RELEASE when <event>` in its raw disposition string; the only `HOLD` row that is
 * not `AUTO_RELEASE` is [MANUAL_HOLD_CODE], with `MANUAL`.
 *
 * Fails closed: a catalogue with no `HOLD` row, or without [MANUAL_HOLD_CODE] as a `HOLD`
 * row with `MANUAL`, is a violation. The check proves an event is named in the row, not
 * that the owning service subscribes to it and releases the document (behaviour 2).
 */
object HoldReleaseChecker {
    const val MANUAL_HOLD_CODE = "ING-017"

    private val namedEvent = Regex("^AUTO_RELEASE (on|when) \\S")

    data class Result(
        val violations: List<String>,
        val checked: List<String>,
    ) {
        val passed: Boolean get() = violations.isEmpty()
    }

    fun check(rows: List<ParsedErrorCode>): Result {
        val violations = mutableListOf<String>()
        val checked = mutableListOf<String>()
        val holds = rows.filter { it.severity == Severity.HOLD }

        if (holds.isEmpty()) {
            violations += "catalogue has no HOLD row (INV-REQ-1181)"
        }
        holds.forEach { row ->
            val raw = row.dispositionRaw.trim()
            checked += "${row.code}: $raw"
            if (row.disposition == Disposition.AUTO_RELEASE) {
                if (!namedEvent.containsMatchIn(raw)) {
                    violations +=
                        "${row.code}: HOLD with AUTO_RELEASE does not name its resolving event " +
                            "after 'on' or 'when', disposition is '$raw' (INV-REQ-1181)"
                }
            } else if (!(row.code == MANUAL_HOLD_CODE && row.disposition == Disposition.MANUAL)) {
                violations +=
                    "${row.code}: HOLD with disposition ${row.disposition} — only $MANUAL_HOLD_CODE " +
                        "may be a HOLD that is not AUTO_RELEASE, and it is MANUAL (INV-REQ-1181)"
            }
        }

        val manual = rows.firstOrNull { it.code == MANUAL_HOLD_CODE }
        if (manual == null) {
            violations += "$MANUAL_HOLD_CODE is absent from the catalogue (INV-REQ-1181)"
        } else if (manual.severity != Severity.HOLD || manual.disposition != Disposition.MANUAL) {
            violations +=
                "$MANUAL_HOLD_CODE must be HOLD with MANUAL, catalogue has " +
                    "${manual.severity}/${manual.disposition} (INV-REQ-1181)"
        }
        return Result(violations, checked)
    }
}
