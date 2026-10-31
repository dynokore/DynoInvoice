package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.events.OutboxPackEventEmitter
import com.dynokore.gst.rules.adapters.events.RulesEventCatalogue
import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.adapters.persistence.ArmedPackLookup
import com.dynokore.gst.rules.adapters.persistence.PersistedPackRegistry
import com.dynokore.gst.rules.adapters.persistence.jooq.Rules
import com.dynokore.gst.rules.application.DocumentCoder
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.domain.evaluation.Evaluator
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
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

    /** The holder copy of the verified packs; the only reader of `rules.pack_artefact` (INV-Q-7438). */
    @Bean
    fun persistedPackRegistry(
        dsl: DSLContext,
        tenants: TenantContext,
    ): PersistedPackRegistry = PersistedPackRegistry(dsl, tenants)

    /** The ARMED version in force on the caller's date, its artefact decoded into domain types (INV-Q-11614). */
    @Bean
    fun packContentsReader(
        dsl: DSLContext,
        registry: PersistedPackRegistry,
    ): PackContentsReader = PackContentsReader(ArmedPackLookup(dsl), registry::artefactOf)

    /**
     * The evaluation core over the armed packs of rules-svc's own schema, with the per-line coding over the decoded
     * packs. The rules and the pack types an evaluation binds are registered by the tasks that specialise it.
     */
    @Bean
    fun evaluateDocument(
        dsl: DSLContext,
        reader: PackContentsReader,
    ): EvaluateDocument =
        EvaluateDocument(
            PackResolver(ArmedPackLookup(dsl)),
            Evaluator(emptyList()),
            emptyList(),
            DocumentCoder(reader, reader, reader),
        )
}
