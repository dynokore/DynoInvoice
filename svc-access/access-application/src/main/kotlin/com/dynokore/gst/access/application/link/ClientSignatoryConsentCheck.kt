package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.grant.EntityRef
import java.time.Clock
import java.time.LocalDate

/** The answer of [ClientSignatoryConsentCheck]. */
sealed interface ConsentCheck {
    /** The consent is live and belongs to the client entity: the pair the link is issued with. */
    data class Live(
        val consent: ConsentRef,
        val client: EntityRef,
    ) : ConsentCheck

    /** One answer for an unknown consent, a consent of another client entity and a consent that is not live. */
    data object NotLive : ConsentCheck
}

/**
 * The consent check of a `clientSignatory` link request (INV-Q-17030 A, INV-AC-2687 refusal part). It runs in the
 * consumer of SignedLinkRequested, outside [IssueSignedLinkUseCase], which gains no consent collaborator. It reads
 * only the status and the client entity of the consent through [ConsentRepository], and only for events whose
 * `linkKind` is `clientSignatory`; it never reads the scope items. The three failures give one answer so the sender
 * learns nothing about which one applied.
 */
class ClientSignatoryConsentCheck(
    private val consents: ConsentRepository,
    private val clock: Clock,
) {
    fun check(
        consentRef: String,
        clientRef: String,
    ): ConsentCheck {
        val ref = runCatching { ConsentRef(consentRef) }.getOrNull() ?: return ConsentCheck.NotLive
        val client = runCatching { EntityRef(clientRef) }.getOrNull() ?: return ConsentCheck.NotLive
        val consent = consents.find(ref) ?: return ConsentCheck.NotLive
        val live = consent.isLiveOn(LocalDate.now(clock))
        return if (live && consent.client == client) ConsentCheck.Live(ref, client) else ConsentCheck.NotLive
    }
}
