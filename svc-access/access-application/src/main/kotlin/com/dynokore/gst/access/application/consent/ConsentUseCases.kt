package com.dynokore.gst.access.application.consent

import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ConsentTerms
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock

/** What a consent change writes besides the consent: the AuthorityChanged event and the audit entry (INV-REQ-861). */
class ConsentEffects(
    val publisher: ConsentChangePublisher,
    val audit: GrantAudit,
)

data class GrantConsentCommand(
    val tenant: TenantRef,
    val actor: IdentityRef,
    val client: EntityRef,
    val agent: IdentityRef,
    val terms: ConsentTerms,
)

sealed interface GrantConsentOutcome {
    data class Granted(
        val consent: Consent,
        val repeated: Boolean,
    ) : GrantConsentOutcome

    /** The caller does not hold `consent.grant` on the client entity. */
    data object NotSignatory : GrantConsentOutcome

    /** Agent tenancy is off in this installation (INV-REQ-2757): no agent consent is recorded. */
    data object TenancyOff : GrantConsentOutcome

    /** The client entity already has a live consent that is not this one; nothing is recorded. */
    data class OtherConsentLive(
        val consent: Consent,
    ) : GrantConsentOutcome
}

/**
 * `grantConsent` (INV-REQ-713, INV-REQ-912): the client signatory records which of the three scope items the agent
 * may use for one client entity. Items not chosen stay withheld. A repeat of the same client, agent and items returns
 * the original consent (repeatKey of the contract). The AuthorityChanged event and the audit entry are written in the
 * same transaction.
 */
class GrantConsentUseCase(
    private val tx: TransactionRunner,
    private val consents: ConsentRepository,
    private val signatory: Authorise,
    private val tenancy: AgentTenancy,
    private val effects: ConsentEffects,
) {
    fun handle(command: GrantConsentCommand): GrantConsentOutcome =
        tx.inTransaction {
            when {
                signatory.handle(command.actor, CONSENT_GRANT, Target(command.client)) !is Decision.Allow -> {
                    GrantConsentOutcome.NotSignatory
                }

                !tenancy.enabled() -> {
                    GrantConsentOutcome.TenancyOff
                }

                else -> {
                    record(command)
                }
            }
        }

    private fun record(command: GrantConsentCommand): GrantConsentOutcome {
        val live = consents.findLiveForClient(command.client)
        if (live != null) {
            val same = live.agent == command.agent && live.items == command.terms.items
            return if (same) {
                GrantConsentOutcome.Granted(
                    live,
                    repeated = true,
                )
            } else {
                GrantConsentOutcome.OtherConsentLive(live)
            }
        }
        val consent =
            Consent(
                ref = consents.nextRef(),
                client = command.client,
                agent = command.agent,
                terms = command.terms,
                status = ConsentStatus.LIVE,
                handoverStatementRef = null,
                version = 0,
            )
        consents.insert(consent)
        effects.publisher.publish(ConsentAuthorityRecord(command.tenant, consent, consents.nextAuthorityVersion()))
        effects.audit.record(
            GrantAuditEntry(
                actor = command.actor.value,
                action = "grantConsent",
                scope = "entity:${command.client.value}",
                reason = null,
                priorStateJson = null,
            ),
        )
        return GrantConsentOutcome.Granted(consent, repeated = false)
    }

    private companion object {
        val CONSENT_GRANT = Permission("consent.grant")
    }
}

sealed interface WithdrawConsentOutcome {
    data class Withdrawn(
        val consent: Consent,
        val repeated: Boolean,
    ) : WithdrawConsentOutcome

    data object NotFound : WithdrawConsentOutcome

    /** The caller does not hold `consent.withdraw` on the client entity of the consent. */
    data object NotSignatory : WithdrawConsentOutcome
}

/** The ports a withdrawal reads and writes besides the consent itself. */
class HandoverPorts(
    val documents: UnsubmittedDocuments,
    val statements: HandoverStatements,
)

/**
 * `withdrawConsent` (INV-REQ-865, INV-REQ-955): withdraws the consent, which takes effect at the next authorisation
 * check, and produces the handover statement of the client's unsubmitted documents with their due dates and blocking
 * reasons. The consent row is locked first, so a repeat sees the first withdrawal and returns the same statement
 * reference. A document list that cannot be had makes the statement say so; it never blocks the withdrawal.
 */
class WithdrawConsentUseCase(
    private val tx: TransactionRunner,
    private val consents: ConsentRepository,
    private val signatory: Authorise,
    private val handover: HandoverPorts,
    private val effects: ConsentEffects,
    private val clock: Clock,
) {
    fun handle(
        tenant: TenantRef,
        actor: IdentityRef,
        ref: com.dynokore.gst.access.domain.consent.ConsentRef,
    ): WithdrawConsentOutcome =
        tx.inTransaction {
            val consent = consents.lock(ref)
            when {
                consent == null -> {
                    WithdrawConsentOutcome.NotFound
                }

                signatory.handle(actor, CONSENT_WITHDRAW, Target(consent.client)) !is Decision.Allow -> {
                    WithdrawConsentOutcome.NotSignatory
                }

                consent.status == ConsentStatus.WITHDRAWN -> {
                    WithdrawConsentOutcome.Withdrawn(consent, repeated = true)
                }

                else -> {
                    withdraw(tenant, actor, consent)
                }
            }
        }

    private fun withdraw(
        tenant: TenantRef,
        actor: IdentityRef,
        consent: Consent,
    ): WithdrawConsentOutcome {
        val statementRef = handover.statements.nextRef()
        handover.statements.insert(
            HandoverStatement(
                ref = statementRef,
                consentRef = consent.ref,
                client = consent.client,
                agent = consent.agent,
                documents = documentsOf(consent.client),
                producedAt = clock.instant(),
            ),
        )
        val after = consent.withdrawn(statementRef)
        consents.update(consent, after)
        effects.publisher.publish(ConsentAuthorityRecord(tenant, after, consents.nextAuthorityVersion()))
        effects.audit.record(
            GrantAuditEntry(
                actor = actor.value,
                action = "withdrawConsent",
                scope = "entity:${consent.client.value}",
                reason = null,
                priorStateJson = """{"status":"LIVE","consentRef":"${consent.ref.value}"}""",
            ),
        )
        return WithdrawConsentOutcome.Withdrawn(after, repeated = false)
    }

    private fun documentsOf(client: EntityRef): DocumentList =
        runCatching { handover.documents.forClient(client) }.getOrDefault(DocumentList.Unavailable)

    private companion object {
        val CONSENT_WITHDRAW = Permission("consent.withdraw")
    }
}
