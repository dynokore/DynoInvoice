package gst.catalogue

import org.yaml.snakeyaml.Yaml
import java.io.File

/** One row of a rendered catalogue artefact, as compared across two trains. */
data class CatalogueRow(
    val code: String,
    val condition: String,
    val severity: String,
    val disposition: String?,
    val revisionNote: String?,
)

/**
 * INV-AC-2170: between two release trains, no code changes its number or condition.
 * A severity or disposition change is allowed only when it comes with a revision note
 * (INV-REQ-1183 behaviour #3, e.g. SUB-010); anything else — a missing code, a changed
 * condition under the same number, or a severity/disposition change with no note — is
 * a repurposing and fails the check.
 *
 * [check] is pure (no I/O), so [StabilityCheckerTest] proves the rule itself against
 * small, authored fixtures; [readArtefact] is the only piece that touches a real file,
 * for the Gradle task to load two real trains' artefacts when a previous one exists.
 */
object StabilityChecker {
    data class Violation(val code: String, val reason: String)

    fun check(
        previousTrain: List<CatalogueRow>,
        currentTrain: List<CatalogueRow>,
    ): List<Violation> {
        val currentByCode = currentTrain.associateBy { it.code }
        val violations = mutableListOf<Violation>()

        for (old in previousTrain) {
            val current = currentByCode[old.code]
            if (current == null) {
                violations += Violation(old.code, "present in the earlier train, absent from this one (removed or renumbered)")
                continue
            }
            if (current.condition != old.condition) {
                violations += Violation(
                    old.code,
                    "condition changed (was \"${old.condition}\", now \"${current.condition}\") — " +
                        "a new condition needs a new code (INV-REQ-1183)",
                )
                continue
            }
            val revised = current.severity != old.severity || current.disposition != old.disposition
            if (revised && current.revisionNote.isNullOrBlank()) {
                violations += Violation(
                    old.code,
                    "severity/disposition changed (${old.severity}/${old.disposition} -> " +
                        "${current.severity}/${current.disposition}) with no revision note (INV-REQ-1183 behaviour #3)",
                )
            }
        }
        return violations
    }

    /** Reads a catalogue artefact JSON file (§[CatalogueArtefact]'s own shape) back into rows. */
    fun readArtefact(file: File): List<CatalogueRow> {
        val root = Yaml().load<Map<String, Any?>>(file.readText())
        @Suppress("UNCHECKED_CAST")
        val codes = root["codes"] as? List<Map<String, Any?>> ?: error("gst-catalogue: '$file' has no 'codes' array.")
        return codes.map { row ->
            CatalogueRow(
                code = row["code"] as String,
                condition = row["condition"] as String,
                severity = row["severity"] as String,
                disposition = row["disposition"] as String?,
                revisionNote = row["revisionNote"] as String?,
            )
        }
    }
}
