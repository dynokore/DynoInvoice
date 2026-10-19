package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.events.OutboxPackEventEmitter
import com.dynokore.gst.rules.adapters.events.RulesEventCatalogue
import com.dynokore.gst.rules.adapters.persistence.ArmedPackLookup
import com.dynokore.gst.rules.adapters.persistence.jooq.Rules
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.domain.evaluation.Evaluator
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import org.jooq.DSLContext
import org.jooq.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The only place a use case is constructed; `application` stays annotation-free (ARC-03, TPL-18). */
@Configuration(proxyBeanMethods = false)
class RulesConfiguration {
    /** The generated schema, checked at start-up against the table classes (INV-Q-4815, INV-ADR-3968 item 4a). */
    @Bean
    fun rulesSchema(): Schema = Rules.RULES

    /** The event types catalogued for rules; the emission guard refuses any other (INV-AC-1869). */
    @Bean
    fun eventCatalogue(): EventCatalogue = RulesEventCatalogue.catalogue()

    @Bean
    fun packEventEmitter(writer: OutboxWriter): PackEventEmitter = OutboxPackEventEmitter(writer)

    /**
     * The evaluation core over the armed packs of rules-svc's own schema. The rules and the pack types an evaluation
     * binds are registered by the tasks that specialise it (INV-T-4966 on); none is registered by the skeleton.
     */
    @Bean
    fun evaluateDocument(dsl: DSLContext): EvaluateDocument =
        EvaluateDocument(PackResolver(ArmedPackLookup(dsl)), Evaluator(emptyList()), emptyList())
}
