package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.persistence.CanonicalDocumentWriter
import com.dynokore.gst.canonical.adapters.persistence.DocumentRowFactsFromRecord
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentPartyRefs
import com.dynokore.gst.canonical.adapters.persistence.JooqEntityIdentities
import com.dynokore.gst.canonical.adapters.persistence.JooqPeriodSpans
import com.dynokore.gst.canonical.adapters.persistence.RowFactUnknown
import com.dynokore.gst.canonical.application.document.ScopeAnswerCapture
import com.dynokore.gst.canonical.application.document.ScopeEvaluation
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * The beans of the row facts source (INV-AC-21604). The capture decorates the bean named `scopeEvaluation` (of
 * [ScopeEvaluationConfiguration]) and is primary, so the result builder and the facts source hold the same instance.
 */
@Configuration(proxyBeanMethods = false)
class DocumentRowFactsConfiguration {
    @Bean
    fun canonicalDocumentWriter(): CanonicalDocumentWriter = CanonicalDocumentWriter()

    @Bean
    @Primary
    fun scopeAnswerCapture(
        @Qualifier("scopeEvaluation") delegate: ObjectProvider<ScopeEvaluation>,
    ): ScopeAnswerCapture =
        // resolved at the first call, not at start: the wrapped bean needs the entity constitution store, whose
        // registration order must not decide whether the context starts
        ScopeAnswerCapture { command, periodRef, transactionDate ->
            delegate.getObject().evaluate(command, periodRef, transactionDate)
        }

    @Bean
    fun documentRowFactsFromRecord(
        dsl: DSLContext,
        tenants: TenantContext,
        entityIdentities: JooqEntityIdentities,
        capture: ScopeAnswerCapture,
        writer: CanonicalDocumentWriter,
    ): DocumentRowFactsFromRecord {
        val periods = JooqPeriodSpans(dsl)
        val partyRefs = JooqDocumentPartyRefs(dsl, tenants)
        return DocumentRowFactsFromRecord(
            entityIdOf = entityIdentities::entityIdOf,
            spansOf = periods,
            periodIdOf = periods::periodIdOf,
            partyRefOf = partyRefs::partyRefOf,
            capture = capture,
            writer = writer,
            actingUser = actingUser(tenants),
        )
    }

    /** The acting user ID bound for the request; none bound is an unknown fact, never a constant or a system user. */
    fun actingUser(tenants: TenantContext): () -> String =
        { tenants.currentActor() ?: throw RowFactUnknown("createdBy") }
}
