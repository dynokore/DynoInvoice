package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.NetDerivationRefusedReader
import com.dynokore.gst.submission.adapters.events.OutboxNetDerivationRequestPublisher
import com.dynokore.gst.submission.adapters.persistence.JooqCreditNoteCorrectionPorts
import com.dynokore.gst.submission.adapters.persistence.RefusedCreditNotes
import com.dynokore.gst.submission.application.correction.CreditNoteCorrectionHandler
import com.dynokore.gst.submission.application.correction.CreditNoteTransitions
import com.dynokore.gst.submission.application.correction.NetDerivationRequestPort
import com.dynokore.gst.submission.application.correction.NetRefusedMarks
import com.dynokore.gst.submission.application.correction.NettingDeclarationRecorder
import com.dynokore.gst.submission.application.correction.NettingDeclarations
import com.dynokore.gst.submission.application.correction.OriginalVersionSource
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The credit note correction path (INV-REQ-558 behavior 6, INV-T-25354): the register adapter exposed under each port
 * of the application module, the net derivation request publisher, the handler and the reader of
 * `NetDerivationRefused`. Each bean is the register adapter seen through one port only (delegation), so Spring
 * finds exactly one bean of each port type. No role name is checked (INV-Q-10027). Every adapter runs in the
 * caller's transaction.
 */
@Configuration(proxyBeanMethods = false)
class CreditNoteCorrectionConfiguration {
    private fun ports(
        dsl: DSLContext,
        tenants: TenantContext,
    ) = JooqCreditNoteCorrectionPorts(dsl, tenants)

    @Bean
    fun originalVersionSource(
        dsl: DSLContext,
        tenants: TenantContext,
    ): OriginalVersionSource = ports(dsl, tenants).let { p -> object : OriginalVersionSource by p {} }

    @Bean
    fun nettingDeclarations(
        dsl: DSLContext,
        tenants: TenantContext,
    ): NettingDeclarations = ports(dsl, tenants).let { p -> object : NettingDeclarations by p {} }

    @Bean
    fun nettingDeclarationRecorder(
        dsl: DSLContext,
        tenants: TenantContext,
    ): NettingDeclarationRecorder = ports(dsl, tenants).let { p -> object : NettingDeclarationRecorder by p {} }

    @Bean
    fun creditNoteTransitions(
        dsl: DSLContext,
        tenants: TenantContext,
    ): CreditNoteTransitions = ports(dsl, tenants).let { p -> object : CreditNoteTransitions by p {} }

    @Bean
    fun netRefusedMarks(
        dsl: DSLContext,
        tenants: TenantContext,
    ): NetRefusedMarks = ports(dsl, tenants).let { p -> object : NetRefusedMarks by p {} }

    @Bean
    fun refusedCreditNotes(
        dsl: DSLContext,
        tenants: TenantContext,
    ): RefusedCreditNotes = ports(dsl, tenants).let { p -> object : RefusedCreditNotes by p {} }

    @Bean
    fun netDerivationRequestPort(
        outbox: OutboxWriter,
        tenants: TenantContext,
        dsl: DSLContext,
    ): NetDerivationRequestPort = OutboxNetDerivationRequestPublisher(outbox, tenants, dsl)

    @Bean
    @Suppress("LongParameterList") // composition root: the handler takes six ports
    fun creditNoteCorrectionHandler(
        originals: OriginalVersionSource,
        declarations: NettingDeclarations,
        transitions: CreditNoteTransitions,
        requests: NetDerivationRequestPort,
        refusals: NetRefusedMarks,
        recorder: NettingDeclarationRecorder,
    ): CreditNoteCorrectionHandler =
        CreditNoteCorrectionHandler(originals, declarations, transitions, requests, refusals, recorder)

    @Bean
    fun netDerivationRefusedHandler(
        handler: CreditNoteCorrectionHandler,
        awaiting: RefusedCreditNotes,
    ): EventHandler = NetDerivationRefusedReader(handler, awaiting)
}
