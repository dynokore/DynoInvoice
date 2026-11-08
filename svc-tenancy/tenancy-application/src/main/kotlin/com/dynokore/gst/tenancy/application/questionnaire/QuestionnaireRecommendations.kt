package com.dynokore.gst.tenancy.application.questionnaire

/** One recommendation entry of the questionnaire response (INV-T-9951): a reference with its reason, never a value. */
data class Recommendation(
    val kind: Kind,
    val ref: String,
    val reason: String,
) {
    enum class Kind { VARIATION_POINT, PROFILE }
}

/**
 * The recommendation entries of the onboarding questionnaire (owner answer INV-Q-9831 B; INV-NOTE-3 section 37.5,
 * column "Recommends", with the profile statuses and member variation points of section 37.4). The table is keyed by
 * `questionnaireVersion`; it holds the single version [VERSION]. Planned profiles (PRF-3, PRF-4, PRF-7) never appear as
 * entries: each is replaced, at its place, by its member variation points, a variation point already listed for the
 * same question being kept at its first place only (the lists below are that expansion).
 *
 * The entries depend on the question key only: the answer is neither validated nor used to choose an entry, and it
 * is the `reason` as submitted. A key that is not one of the fourteen gives no entry; another version gives none.
 */
object QuestionnaireRecommendations {
    const val VERSION = "2026.1"

    private val REFS: Map<String, List<String>> =
        linkedMapOf(
            "OQ-1" to listOf("PRF-1", "PRF-2", "VP-01", "VP-02", "VP-28", "VP-33", "VP-15", "VP-03", "VP-40"),
            "OQ-2" to listOf("VP-03", "PRF-5", "PRF-6", "VP-10", "VP-11"),
            "OQ-3" to listOf("VP-11", "VP-14", "VP-32", "VP-15", "VP-46"),
            "OQ-4" to listOf("VP-01", "VP-02"),
            "OQ-5" to listOf("VP-28", "VP-33", "VP-51", "VP-64", "VP-01", "VP-02", "VP-15"),
            "OQ-6" to listOf("VP-25", "VP-26", "VP-49"),
            "OQ-7" to listOf("VP-58"),
            "OQ-8" to listOf("VP-24", "VP-37"),
            "OQ-9" to listOf("VP-43", "VP-44", "VP-52", "VP-01", "VP-02", "VP-05", "VP-08", "VP-32"),
            "OQ-10" to listOf("VP-53"),
            "OQ-11" to listOf("VP-54"),
            "OQ-12" to listOf("VP-48", "VP-49", "VP-50", "VP-71"),
            "OQ-13" to listOf("VP-51"),
            "OQ-14" to listOf("VP-22", "VP-23"),
        )

    /** The refs of a question key in table order, empty for an unknown key. */
    fun refs(questionKey: String): List<String> = REFS[questionKey].orEmpty()

    /** The entries for the submitted [answers], per key in the numeric order of the keys; empty for another version. */
    fun entries(
        questionnaireVersion: String,
        answers: Map<String, String>,
    ): List<Recommendation> {
        if (questionnaireVersion != VERSION) return emptyList()
        return answers.entries
            .filter { it.key in REFS }
            .sortedBy { it.key.removePrefix("OQ-").toInt() }
            .flatMap { (key, answer) ->
                REFS.getValue(key).map {
                    val kind =
                        if (it.startsWith(
                                "PRF-",
                            )
                        ) {
                            Recommendation.Kind.PROFILE
                        } else {
                            Recommendation.Kind.VARIATION_POINT
                        }
                    Recommendation(kind, it, "$key=$answer")
                }
            }
    }
}
