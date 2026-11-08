package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.QUESTIONNAIRE_ANSWERS
import com.dynokore.gst.tenancy.application.questionnaire.QuestionnaireAnswerStore
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The questionnaire answers of the bound tenant (jOOQ only, CS-18); forced RLS hides every other tenant's rows. */
class JooqQuestionnaireAnswerStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) : QuestionnaireAnswerStore {
    override fun put(
        questionnaireVersion: String,
        answers: Map<String, String>,
    ) {
        val json = mapper.writeValueAsString(answers)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        dsl
            .insertInto(QUESTIONNAIRE_ANSWERS)
            .set(QUESTIONNAIRE_ANSWERS.ID, UUID.randomUUID())
            .set(QUESTIONNAIRE_ANSWERS.TENANT_ID, tenants.current().value)
            .set(QUESTIONNAIRE_ANSWERS.QUESTIONNAIRE_VERSION, questionnaireVersion)
            .set(QUESTIONNAIRE_ANSWERS.ANSWERS, json)
            .set(QUESTIONNAIRE_ANSWERS.RECORDED_AT, now)
            .onConflict(QUESTIONNAIRE_ANSWERS.TENANT_ID, QUESTIONNAIRE_ANSWERS.QUESTIONNAIRE_VERSION)
            .doUpdate()
            .set(QUESTIONNAIRE_ANSWERS.ANSWERS, json)
            .set(QUESTIONNAIRE_ANSWERS.RECORDED_AT, now)
            .execute()
    }

    override fun find(questionnaireVersion: String): Map<String, String>? =
        dsl
            .selectFrom(QUESTIONNAIRE_ANSWERS)
            .where(QUESTIONNAIRE_ANSWERS.QUESTIONNAIRE_VERSION.eq(questionnaireVersion))
            .fetchOne()
            ?.let {
                mapper.readValue(
                    it.answers.column("answers"),
                    object : TypeReference<LinkedHashMap<String, String>>() {},
                )
            }

    override fun count(): Int = dsl.fetchCount(QUESTIONNAIRE_ANSWERS)
}
