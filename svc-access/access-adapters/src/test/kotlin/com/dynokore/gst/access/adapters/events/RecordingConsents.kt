package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef

/** A [ConsentRepository] double that serves stored consents by reference and counts every call it receives. */
class RecordingConsents(
    private val stored: Map<String, Consent> = emptyMap(),
) : ConsentRepository {
    var calls = 0
        private set

    override fun find(ref: ConsentRef): Consent? {
        calls++
        return stored[ref.value]
    }

    override fun lock(ref: ConsentRef): Consent? = unused()

    override fun findLiveForClient(client: com.dynokore.gst.access.domain.grant.EntityRef): Consent? = unused()

    override fun insert(consent: Consent) = unused()

    override fun update(
        before: Consent,
        after: Consent,
    ) = unused()

    override fun nextAuthorityVersion(): Long = unused()

    override fun nextRef(): ConsentRef = unused()

    private fun unused(): Nothing {
        calls++
        error("the consent check of a link request reads one consent by reference and nothing else")
    }
}
