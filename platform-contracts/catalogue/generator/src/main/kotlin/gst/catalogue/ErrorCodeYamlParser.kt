package gst.catalogue

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Parses `error-codes.yaml` (the vendored copy in `catalogue/errors/`, STK-61) into
 * [ParsedErrorCode] rows. The source file is free text authored for a person first
 * (model/README.md); this parser reduces each row's `severity`/`disposition` field to
 * its leading, fixed-vocabulary token (INV-REQ-1178 behaviour #2) and splits a
 * trailing revision-note clause out of `condition` (INV-REQ-1183 behaviour #3),
 * keeping the raw text alongside every reduction so nothing is silently discarded.
 */
object ErrorCodeYamlParser {
    private val codePattern = Regex("^([A-Z]+)-(\\d{3})$")
    private val severityPattern = Regex("^(BLOCK|HOLD|WARN|INFO)\\b")
    private val dispositionPattern = Regex("^(AUTO_RETRY|AUTO_RELEASE|MANUAL|OVERRIDABLE|TERMINAL)\\b")

    // A revision note documents a change already applied to this row (Amended),
    // a code newly introduced this round (Added) or a condition widened this round
    // (Extended) — never part of the condition's own meaning.
    private val revisionMarker = Regex("(?i)\\b(Amended|Added|Extended)\\b")

    private val pinnedCommitPattern = Regex("pinned at commit ([0-9a-f]{7,40})")

    /** Reads the pinned invoice-docs commit SHA from the vendored file's own header comment. */
    fun readPinnedCommit(file: File): String =
        pinnedCommitPattern.find(file.readText())?.groupValues?.get(1)
            ?: error("gst-catalogue: '$file' has no '# ... pinned at commit <sha> ...' header line.")

    fun parse(file: File): List<ParsedErrorCode> {
        val yaml = Yaml()
        val root = yaml.load<Map<String, Any?>>(file.readText())
        @Suppress("UNCHECKED_CAST")
        val rows = root["error_codes"] as? List<Map<String, Any?>>
            ?: error("gst-catalogue: '${file}' has no top-level 'error_codes' list.")
        return rows.map { parseRow(it, file) }
    }

    private fun parseRow(
        row: Map<String, Any?>,
        file: File,
    ): ParsedErrorCode {
        val code = (row["code"] as? String)?.trim() ?: error("gst-catalogue: row without a 'code' in $file: $row")
        val match =
            codePattern.matchEntire(code)
                ?: error(
                    "gst-catalogue: '$code' in $file is not of the form AREA-NNN " +
                        "(INV-NOTE-3 §21.2 / INV-REQ-1178 behaviour #1).",
                )
        val (area, numberText) = match.destructured

        val conditionRaw = ((row["condition"] as? String) ?: error("gst-catalogue: $code has no 'condition' in $file")).trim()
        val (conditionCore, revisionNote) = splitRevisionNote(conditionRaw)

        val severityRaw = ((row["severity"] as? String) ?: error("gst-catalogue: $code has no 'severity' in $file")).trim()
        val severity =
            severityPattern.find(severityRaw)?.groupValues?.get(1)?.let(Severity::valueOf)
                ?: error(
                    "gst-catalogue: $code's severity '$severityRaw' does not start with one of " +
                        "${Severity.entries.joinToString()} (INV-REQ-1178 behaviour #2).",
                )

        val dispositionRaw =
            ((row["disposition"] as? String) ?: error("gst-catalogue: $code has no 'disposition' in $file")).trim()
        val disposition = dispositionPattern.find(dispositionRaw)?.groupValues?.get(1)?.let(Disposition::valueOf)
        // An INFO code may carry no disposition at all (ING-005); every other severity
        // must reduce to one of the five fixed dispositions (INV-REQ-1178 behaviour #2).
        if (disposition == null && severity != Severity.INFO) {
            error(
                "gst-catalogue: $code (severity $severity) has no recognised disposition in '$dispositionRaw' " +
                    "(INV-REQ-1178 behaviour #2).",
            )
        }

        val owner = ((row["owner"] as? String) ?: error("gst-catalogue: $code has no 'owner' in $file")).trim()

        return ParsedErrorCode(
            code = code,
            area = area,
            number = numberText.toInt(),
            conditionRaw = conditionRaw,
            conditionCore = conditionCore,
            revisionNote = revisionNote,
            severity = severity,
            severityRaw = severityRaw,
            disposition = disposition,
            dispositionRaw = dispositionRaw,
            owner = owner,
        )
    }

    /**
     * Splits off a trailing "Amended/Added/Extended …" clause, if the row has one.
     * Best-effort: the result feeds generated KDoc and human review output, never a
     * pass/fail decision (the stability checker's own logic is proven against clean,
     * authored fixtures — see StabilityCheckerTest), so an imperfectly trimmed core
     * (a stray "(" left over from a clause nested inside parentheses, e.g. ACC-001) is
     * cosmetic, not a correctness risk.
     */
    internal fun splitRevisionNote(condition: String): Pair<String, String?> {
        val matches = revisionMarker.findAll(condition).toList()
        val last = matches.lastOrNull() ?: return condition to null
        val core = condition.substring(0, last.range.first).trimEnd(' ', '.', ',', ';', '(').trim()
        val note = condition.substring(last.range.first).trim()
        return core to note
    }
}
