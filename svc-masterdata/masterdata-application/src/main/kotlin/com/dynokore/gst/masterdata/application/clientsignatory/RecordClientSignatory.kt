package com.dynokore.gst.masterdata.application.clientsignatory

import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.application.request.LinkValidityLimits
import com.dynokore.gst.masterdata.domain.request.LinkValidity
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The state of the consent link of a client signatory (INV-Q-17431): PENDING until access-svc has answered. */
enum class LinkState { PENDING, ISSUED, REFUSED }

/** The closed reason codes of a REFUSED outcome (platform-contracts `signed-link-outcome-recorded-payload`). */
enum class LinkRefusalReason { MOBILE_NUMBER_REQUIRED, CONSENT_NOT_LIVE, RECIPIENT_INVALID, EXPIRY_INVALID }

/** The link status of a record: [reasonCode] is set exactly when [state] is REFUSED. */
data class LinkStatus(
    val state: LinkState,
    val reasonCode: LinkRefusalReason? = null,
) {
    init {
        require((state == LinkState.REFUSED) == (reasonCode != null)) { "a reason code goes with REFUSED only" }
    }
}

/** A client signatory the service recorded (INV-REQ-899). [requestEventId] is the id of its SignedLinkRequested. */
data class ClientSignatory(
    val signatoryRef: String,
    val clientRef: String,
    val name: String,
    val email: String,
    val mobile: String?,
    val consentRef: String,
    val linkStatus: LinkStatus,
    val requestEventId: String?,
    val recordedAt: Instant,
)

/** The client signatories of the bound tenant (TPL-11); every call runs inside the caller's transaction. */
interface ClientSignatories {
    fun findByClientAndEmail(
        clientRef: String,
        email: String,
    ): ClientSignatory?

    fun findByRequestEventId(requestEventId: String): ClientSignatory?

    fun insert(signatory: ClientSignatory)

    /** Stores the id of the SignedLinkRequested written for [signatoryRef]. */
    fun saveRequestEventId(
        signatoryRef: String,
        requestEventId: String,
    )

    fun saveLinkStatus(
        signatoryRef: String,
        status: LinkStatus,
    )

    /** A new signatory reference, unique within the tenant. */
    fun nextRef(): String
}

/** The payload of SignedLinkRequested for a client signatory (`linkKind` clientSignatory). */
data class ClientSignatoryLinkRequest(
    val clientRef: String,
    val consentRef: String,
    val signatoryRef: String,
    val sourceRecordId: String,
    val purpose: String,
    val subjectReference: String,
    val email: String,
    val mobileNumber: String?,
    val channel: String,
    val expiry: Instant,
)

/** Writes SignedLinkRequested in the transaction of the command (TPL-05, CS-17) and returns its CloudEvents id. */
fun interface ClientSignatoryLinkPublisher {
    fun publish(request: ClientSignatoryLinkRequest): String
}

data class RecordClientSignatoryCommand(
    val caller: String,
    val clientRef: String,
    val signatoryName: String,
    val signatoryEmail: String,
    val signatoryMobile: String?,
    val consentRef: String?,
)

sealed interface RecordClientSignatoryOutcome {
    /** [created] is false when (clientRef, signatoryEmail) already had a record: it is returned and nothing emitted. */
    data class Recorded(
        val signatory: ClientSignatory,
        val created: Boolean,
    ) : RecordClientSignatoryOutcome

    /** The caller does not hold `client-signatory.record`; nothing is stored. */
    data object Forbidden : RecordClientSignatoryOutcome

    /** A required value is blank or absent (`consentRef` first among them); nothing is stored. */
    data class Invalid(
        val member: String,
    ) : RecordClientSignatoryOutcome

    /** The effective link validity is out of range; fails closed. */
    data object InvalidValidity : RecordClientSignatoryOutcome
}

/**
 * INV-REQ-899, INV-FLOW-255 step 1, INV-AC-17444. Records the client signatory with link status PENDING and, in the
 * same transaction, writes SignedLinkRequested to the outbox (DR-11: never a call to access-svc). The permission
 * `client-signatory.record` is decided first, per request, through [TenantPermission] (INV-Q-10027, INV-Q-17446 A).
 */
class RecordClientSignatory(
    private val tx: TransactionRunner,
    private val store: ClientSignatories,
    private val permission: TenantPermission,
    private val limits: LinkValidityLimits,
    private val clock: Clock,
    private val publisher: ClientSignatoryLinkPublisher,
) {
    fun handle(command: RecordClientSignatoryCommand): RecordClientSignatoryOutcome =
        tx.inTransaction {
            if (!permission.holds(command.caller, PERMISSION_KEY)) {
                RecordClientSignatoryOutcome.Forbidden
            } else {
                val clean = clean(command)
                val missing = clean.missing()
                when {
                    missing != null -> RecordClientSignatoryOutcome.Invalid(missing)
                    else -> existingOrNew(clean)
                }
            }
        }

    /** The command with every text trimmed; `consentRef` absent reads as empty. */
    private class Clean(
        val clientRef: String,
        val name: String,
        val email: String,
        val mobile: String?,
        val consentRef: String,
    ) {
        /** The first required member that is blank, `consentRef` first; null when none. */
        fun missing(): String? =
            when {
                consentRef.isEmpty() -> "consentRef"
                clientRef.isEmpty() -> "clientRef"
                name.isEmpty() -> "signatoryName"
                email.isEmpty() -> "signatoryEmail"
                else -> null
            }
    }

    private fun clean(command: RecordClientSignatoryCommand) =
        Clean(
            command.clientRef.trim(),
            command.signatoryName.trim(),
            command.signatoryEmail.trim(),
            command.signatoryMobile?.trim()?.takeIf { it.isNotEmpty() },
            command.consentRef?.trim().orEmpty(),
        )

    private fun existingOrNew(clean: Clean): RecordClientSignatoryOutcome {
        val existing = store.findByClientAndEmail(clean.clientRef, clean.email)
        val validity = LinkValidity.effective(limits.defaultDays, limits.maxDays, null)
        return when {
            existing != null -> RecordClientSignatoryOutcome.Recorded(existing, false)
            validity == null -> RecordClientSignatoryOutcome.InvalidValidity
            else -> RecordClientSignatoryOutcome.Recorded(recordAndRequest(clean, validity), true)
        }
    }

    /** Inserts the PENDING record and writes SignedLinkRequested, keeping its id on the record, in one transaction. */
    private fun recordAndRequest(
        clean: Clean,
        validity: Duration,
    ): ClientSignatory {
        val now = clock.instant()
        val record =
            ClientSignatory(
                signatoryRef = store.nextRef(),
                clientRef = clean.clientRef,
                name = clean.name,
                email = clean.email,
                mobile = clean.mobile,
                consentRef = clean.consentRef,
                linkStatus = LinkStatus(LinkState.PENDING),
                requestEventId = null,
                recordedAt = now,
            )
        store.insert(record)
        val eventId = publisher.publish(linkRequest(record, now.plus(validity)))
        store.saveRequestEventId(record.signatoryRef, eventId)
        return record.copy(requestEventId = eventId)
    }

    private fun linkRequest(
        record: ClientSignatory,
        expiry: Instant,
    ) = ClientSignatoryLinkRequest(
        clientRef = record.clientRef,
        consentRef = record.consentRef,
        signatoryRef = record.signatoryRef,
        sourceRecordId = record.signatoryRef,
        purpose = PURPOSE,
        subjectReference = record.consentRef,
        email = record.email,
        mobileNumber = record.mobile,
        channel = CHANNEL,
        expiry = expiry,
    )

    companion object {
        /** The scoped permission key of `recordClientSignatory` (catalogue key, INV-Q-17446 A). */
        const val PERMISSION_KEY = "client-signatory.record"

        /** The `purpose` of the SignedLinkRequested of a client signatory. */
        const val PURPOSE = "client-signatory-consent"

        /** The link is delivered by e-mail; the mobile number is carried only as the recipient's second contact. */
        const val CHANNEL = "EMAIL"
    }
}

/** What the SignedLinkOutcomeRecorded consumer applied. */
enum class OutcomeResult { APPLIED, NO_CHANGE, UNKNOWN_REQUEST }

/**
 * INV-Q-17431: sets the link status of the record whose `requestEventId` equals the event's. Only a PENDING record
 * changes, so a repeat, a stale outcome or an outcome after another one changes nothing; an unknown request id changes
 * no record.
 */
class ApplyLinkOutcome(
    private val tx: TransactionRunner,
    private val store: ClientSignatories,
) {
    fun apply(
        requestEventId: String,
        outcome: LinkStatus,
    ): OutcomeResult =
        tx.inTransaction {
            val record = store.findByRequestEventId(requestEventId)
            when {
                record == null -> {
                    OutcomeResult.UNKNOWN_REQUEST
                }

                record.linkStatus.state != LinkState.PENDING || outcome.state == LinkState.PENDING -> {
                    OutcomeResult.NO_CHANGE
                }

                else -> {
                    store.saveLinkStatus(record.signatoryRef, outcome)
                    OutcomeResult.APPLIED
                }
            }
        }
}
