package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqCreditNoteVersions
import com.dynokore.gst.submission.adapters.persistence.JooqNetFindings
import com.dynokore.gst.submission.application.correction.CreditNoteTransitions
import com.dynokore.gst.submission.application.correction.CreditNoteVersions
import com.dynokore.gst.submission.application.correction.NetFindings
import com.dynokore.gst.submission.application.correction.NettedAmendmentGuard
import com.dynokore.gst.submission.application.correction.OriginalVersionSource
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The guard against an amendment of a credit note netted into a transmitted original (INV-REQ-558 behavior 6,
 * INV-T-25531): the findings store, the register version reader and the guard built from the beans of
 * [CreditNoteCorrectionConfiguration]. Every adapter runs in the caller's transaction. No role name is checked
 * (INV-Q-10027).
 */
@Configuration(proxyBeanMethods = false)
class NettedAmendmentConfiguration {
    @Bean
    fun netFindings(
        dsl: DSLContext,
        tenants: TenantContext,
    ): NetFindings = JooqNetFindings(dsl, tenants)

    @Bean
    fun creditNoteVersions(
        dsl: DSLContext,
        tenants: TenantContext,
    ): CreditNoteVersions = JooqCreditNoteVersions(dsl, tenants)

    @Bean
    fun nettedAmendmentGuard(
        originals: OriginalVersionSource,
        transitions: CreditNoteTransitions,
        creditNoteVersions: CreditNoteVersions,
        netFindings: NetFindings,
    ): NettedAmendmentGuard = NettedAmendmentGuard(originals, transitions, creditNoteVersions, netFindings)
}
