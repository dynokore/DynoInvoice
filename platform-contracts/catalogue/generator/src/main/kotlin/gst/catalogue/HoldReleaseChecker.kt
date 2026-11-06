package gst.catalogue

/**
 * INV-REQ-1181 behaviours 1 and 3 (INV-Q-7282 A): every `HOLD` code names the event that
 * resolves it. A `HOLD` row with `AUTO_RELEASE` carries `AUTO_RELEASE on <event>` or
 * `AUTO_RELEASE when <event>` in its raw disposition string; the only `HOLD` row that is
 * not `AUTO_RELEASE` is one of [MANUAL_HOLD_CODES], each with `MANUAL` (INV-Q-17422 A).
 *
 * Fails closed: a catalogue with no `HOLD` row, or without every member of [MANUAL_HOLD_CODES] as a
 * `HOLD` row with `MANUAL`, is a violation. The check proves an event is named in the row, not
 * that the owning service subscribes to it and releases the document (behaviour 2).
 */
object HoldReleaseChecker {
    /** Exact allowlist (string equality, no prefix or pattern): a document held with a MANUAL code (INV-Q-16154). */
    val MANUAL_HOLD_CODES: Set<String> = setOf("ING-017", "ING-024")

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
            } else if (!(row.code in MANUAL_HOLD_CODES && row.disposition == Disposition.MANUAL)) {
                violations +=
                    "${row.code}: HOLD with disposition ${row.disposition} — only ${MANUAL_HOLD_CODES.joinToString(", ")} " +
                        "may be a HOLD that is not AUTO_RELEASE, and each is MANUAL (INV-REQ-1181)"
            }
        }

        MANUAL_HOLD_CODES.forEach { code ->
            val manual = rows.firstOrNull { it.code == code }
            if (manual == null) {
                violations += "$code is absent from the catalogue (INV-REQ-1181)"
            } else if (manual.severity != Severity.HOLD || manual.disposition != Disposition.MANUAL) {
                violations +=
                    "$code must be HOLD with MANUAL, catalogue has " +
                        "${manual.severity}/${manual.disposition} (INV-REQ-1181)"
            }
        }
        return Result(violations, checked)
    }
}
