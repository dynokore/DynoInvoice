package com.dynokore.gst.tenancy.application.questionnaire

import com.dynokore.platform.tenancykernel.TransactionRunner

/** The questionnaire answers of the bound tenant, one record per version; runs in the caller's transaction. */
interface QuestionnaireAnswerStore {
    /** Inserts the answers of the version, or replaces the answers already held for it. */
    fun put(
        questionnaireVersion: String,
        answers: Map<String, String>,
    )

    /** The answers held for the version, or null when none were recorded. */
    fun find(questionnaireVersion: String): Map<String, String>?

    /** The number of answer records the tenant holds, one per questionnaire version. */
    fun count(): Int
}

/**
 * answerQuestionnaire for the bound tenant (INV-REQ-1246; INV-AC-9593): records the answers as submitted, replacing the
 * record of the same version, and returns the recommendation entries of the section 37.5 table. Nothing is applied: the
 * applied operating profile is not read or written here.
 */
class AnswerQuestionnaire(
    private val tx: TransactionRunner,
    private val store: QuestionnaireAnswerStore,
) {
    fun handle(
        questionnaireVersion: String,
        answers: Map<String, String>,
    ): List<Recommendation> =
        tx.inTransaction {
            store.put(questionnaireVersion, answers)
            QuestionnaireRecommendations.entries(questionnaireVersion, answers)
        }
}
