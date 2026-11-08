package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.persistence.JooqEntityFactStore
import com.dynokore.gst.tenancy.adapters.persistence.JooqQuestionnaireAnswerStore
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.election.EntityFactSource
import com.dynokore.gst.tenancy.application.questionnaire.AnswerQuestionnaire
import com.dynokore.gst.tenancy.application.questionnaire.EntityFactStore
import com.dynokore.gst.tenancy.application.questionnaire.QuestionnaireAnswerStore
import com.dynokore.gst.tenancy.application.questionnaire.RecordEntityFacts
import com.dynokore.gst.tenancy.application.questionnaire.StoredEntityFacts
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/** TPL-18: the questionnaire use case (INV-T-4933), constructed here and nowhere else. */
@Configuration(proxyBeanMethods = false)
class QuestionnaireUseCases {
    @Bean
    fun questionnaireAnswerStore(
        dsl: DSLContext,
        tenants: TenantContext,
        mapper: ObjectMapper,
    ): QuestionnaireAnswerStore = JooqQuestionnaireAnswerStore(dsl, tenants, mapper)

    @Bean
    fun entityFactStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): EntityFactStore = JooqEntityFactStore(dsl, tenants)

    @Bean
    fun recordEntityFacts(
        tx: TransactionRunner,
        store: EntityFactStore,
        caller: CallerSource,
    ): RecordEntityFacts = RecordEntityFacts(tx, store, caller)

    /** The default binding of the election port: the stored facts; NOT_RECORDED when unrecorded (INV-Q-9696 B). */
    @Bean
    @ConditionalOnMissingBean(EntityFactSource::class)
    fun entityFactSource(store: EntityFactStore): EntityFactSource = StoredEntityFacts(store)

    @Bean
    fun answerQuestionnaire(
        tx: TransactionRunner,
        store: QuestionnaireAnswerStore,
    ): AnswerQuestionnaire = AnswerQuestionnaire(tx, store)
}
