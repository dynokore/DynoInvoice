package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqHoldStore
import com.dynokore.gst.submission.adapters.persistence.JooqPrecedingStates
import com.dynokore.gst.submission.adapters.policy.PolicyCreditNoteOptions
import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.gst.submission.application.arrival.ScopeFacts
import com.dynokore.gst.submission.application.ordering.AcknowledgedHook
import com.dynokore.gst.submission.application.ordering.CreditNoteHold
import com.dynokore.gst.submission.application.ordering.CreditNoteOptions
import com.dynokore.gst.submission.application.ordering.HeldRelease
import com.dynokore.gst.submission.application.ordering.HoldStore
import com.dynokore.gst.submission.application.ordering.PrecedingStates
import com.dynokore.gst.submission.application.ordering.ReleaseAloneAt
import com.dynokore.gst.submission.application.queue.AcknowledgementInputs
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.platform.configresolver.PolicyReader
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/** Wiring of the arrival hold of a credit note (INV-REQ-709, INV-AC-24521). */
@Configuration(proxyBeanMethods = false)
class HoldConfiguration {
    @Bean
    fun creditNoteOptions(reader: PolicyReader): CreditNoteOptions = PolicyCreditNoteOptions(reader)

    @Bean
    fun precedingStates(dsl: DSLContext): PrecedingStates = JooqPrecedingStates(dsl)

    @Bean
    fun holdStore(dsl: DSLContext): HoldStore = JooqHoldStore(dsl)

    @Bean
    fun releaseAloneAt(
        inputs: AcknowledgementInputs,
        filing: FilingProperties,
    ): ReleaseAloneAt = ReleaseAloneAt(inputs, ZoneId.of(filing.businessZone))

    @Bean
    @Suppress("LongParameterList") // composition root: the hold takes eight collaborators
    fun creditNoteHold(
        facts: ScopeFacts,
        options: CreditNoteOptions,
        states: PrecedingStates,
        releaseAt: ReleaseAloneAt,
        store: HoldStore,
        raising: CodeRaising,
        clock: Clock,
        filing: FilingProperties,
    ): CreditNoteHold =
        CreditNoteHold(facts, options, states, releaseAt, store, raising, clock, ZoneId.of(filing.businessZone))

    /** The release of the held credit notes at the acknowledgement of their preceding document (INV-AC-24522). */
    @Bean
    fun heldRelease(
        store: HoldStore,
        states: PrecedingStates,
        queue: SubmissionQueue,
    ): AcknowledgedHook = HeldRelease(store, states, queue)
}
