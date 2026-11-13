package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.link.LinkRecipient
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.link.LinkSource
import com.dynokore.gst.access.domain.link.LinkStatus
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64

/** Template of the message that carries the link (EMAIL). */
const val TEMPLATE_SIGNED_LINK = "signed-link"

/** Template of the separate message that carries the sealed passcode (INV-REQ-2762). */
const val TEMPLATE_SIGNED_LINK_PASSCODE = "signed-link-passcode"

/** One `MessageRequested` to append to the outbox; exactly one of [linkReference] and [sealedPasscode] is set. */
data class MessageRequest(
    val template: String,
    val recipient: String,
    val channel: MessageChannel,
    val linkReference: String?,
    val sealedPasscode: String?,
) {
    init {
        require((linkReference == null) != (sealedPasscode == null)) {
            "a message carries a link reference or a sealed passcode, never both and never neither"
        }
    }
}

enum class MessageChannel { EMAIL, SMS }

/**
 * Appends `MessageRequested` and `SignedLinkIssued` to the outbox in the transaction of the caller
 * (accessMessageRequested, accessSignedLinkIssued).
 */
interface LinkMessages {
    fun request(message: MessageRequest)

    /** Appends `SignedLinkIssued` for the stored [link]; it carries the link reference, never a secret. */
    fun issued(link: SignedLink)
}

/** The value of the property `access.signed-link.sms-gateway-bound`; an absent property is false (INV-Q-17203 A). */
fun interface SmsGatewayBinding {
    fun bound(): Boolean

    companion object {
        /**
         * Whether VP-75 may be set to SMS only: accepted only when the property is true. The same rule serves the
         * policy write and the issue.
         */
        fun acceptsSmsOnly(binding: SmsGatewayBinding): Boolean = binding.bound()
    }
}

/** Generates the one-time passcode and the opaque link reference. */
interface LinkSecrets {
    /** Six decimal digits. */
    fun newPasscode(): String

    fun newRef(): LinkRef
}

/** The generator in production: SecureRandom; the reference is `LNK-` and 128 random bits as base64url. */
class SecureLinkSecrets(
    private val random: SecureRandom = SecureRandom(),
) : LinkSecrets {
    override fun newPasscode(): String = random.nextInt(PASSCODE_SPACE).toString().padStart(PASSCODE_DIGITS, '0')

    override fun newRef(): LinkRef {
        val bytes = ByteArray(REF_BYTES)
        random.nextBytes(bytes)
        return LinkRef(PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
    }

    companion object {
        const val PREFIX = "LNK-"
        const val PASSCODE_DIGITS = 6
        private const val PASSCODE_SPACE = 1_000_000
        private const val REF_BYTES = 16
    }
}

/** What the use case is asked to issue. The consent and the client entity are both given or neither. */
data class IssueLinkCommand(
    val purpose: String,
    val subject: String?,
    val email: String,
    val mobile: String?,
    val expiresAt: Instant,
    val consent: ConsentRef?,
    val client: EntityRef?,
    /** The envelope entity of the event for an event-issued link; ignored when [client] is given. */
    val envelopeEntity: EntityRef?,
    /** The id of the event that triggered the issue; absent for a call. */
    val originEventId: String?,
)

enum class IssueRefusal {
    /** Only one of the consent and the client entity was given. */
    PAIR_INCOMPLETE,

    /** No entity to file ACC-004 under: neither a client entity nor an envelope entity. */
    NO_ALERT_ENTITY,

    EXPIRY_NOT_IN_FUTURE,

    /** The recipient address is empty or the purpose is blank. */
    RECIPIENT_OR_PURPOSE_MISSING,

    /** SMS only (VP-75) and no mobile number is given. */
    SMS_ONLY_NO_MOBILE,

    /** SMS only (VP-75) while the property `access.signed-link.sms-gateway-bound` is not true. */
    SMS_ONLY_GATEWAY_NOT_BOUND,

    /** The caller identity is blank or is not the system actor (a role name, a person): issuing is system-only. */
    ACTOR_NOT_SYSTEM,
}

sealed interface IssueLinkOutcome {
    /** The link is stored and its two messages are in the outbox. The passcode is not part of the result. */
    data class Issued(
        val ref: LinkRef,
    ) : IssueLinkOutcome

    /** The event was delivered before: the link stored for it stays, and nothing is appended. */
    data class Duplicate(
        val ref: LinkRef,
    ) : IssueLinkOutcome

    /** Nothing was stored and nothing was published. */
    data class Refused(
        val reason: IssueRefusal,
    ) : IssueLinkOutcome
}

/**
 * Issues a signed link (INV-REQ-899, INV-REQ-2758, INV-REQ-2762). The link is issued on the event SignedLinkRequested
 * and by no call from a person (DR-11): the use case decides no permission, reads no role or agent scope, and its audit
 * entry names the system actor. It stores the link with the digest of the passcode, then appends the two messages in
 * the same transaction: the link by e-mail, and the passcode sealed to notification-svc's delivery key, in a separate
 * message by SMS where a mobile number is on file and VP-75 permits, otherwise by a second e-mail to the same address.
 */
@Suppress("LongParameterList") // one collaborator per port
class IssueSignedLinkUseCase(
    private val tx: TransactionRunner,
    private val links: SignedLinks,
    private val secrets: LinkSecrets,
    private val digests: PasscodeDigests,
    private val sealer: PasscodeSealer,
    private val settings: LinkPasscodeSettings,
    private val smsGateway: SmsGatewayBinding,
    private val messages: LinkMessages,
    private val audit: GrantAudit,
    private val systemActor: String,
    private val clock: Clock,
) {
    /** The call of the system: the caller identity is the configured system actor. */
    fun handle(command: IssueLinkCommand): IssueLinkOutcome = handle(systemActor, command)

    /**
     * [actor] is the caller identity. Only the system actor id issues a link (INV-Q-17201): a blank id, a role name
     * or any other id is refused before anything is read, stored or audited. No permission is decided.
     */
    fun handle(
        actor: String,
        command: IssueLinkCommand,
    ): IssueLinkOutcome =
        tx.inTransaction {
            if (actor.isBlank() || actor != systemActor) {
                return@inTransaction IssueLinkOutcome.Refused(IssueRefusal.ACTOR_NOT_SYSTEM)
            }
            val origin = command.originEventId
            val earlier = origin?.let { links.findBySourceEvent(it) }
            if (earlier != null) return@inTransaction IssueLinkOutcome.Duplicate(earlier.ref)
            val now = clock.instant()
            val setting = settings.current()
            val refusal = refusalOf(command, setting, now)
            if (refusal != null) return@inTransaction IssueLinkOutcome.Refused(refusal)
            val alertEntity = requireNotNull(command.client ?: command.envelopeEntity)
            val ref = secrets.newRef()
            val passcode = secrets.newPasscode()
            val link = newLink(command, ref, alertEntity, digests.digestOf(ref, passcode))
            links.insert(link)
            messages.issued(link)
            requestMessages(command, ref, passcode)
            audit.record(
                GrantAuditEntry(
                    actor = actor,
                    action = "issueSignedLink",
                    scope = "entity:${alertEntity.value}",
                    reason = origin,
                    priorStateJson = PRIOR_STATE_NONE,
                ),
            )
            IssueLinkOutcome.Issued(ref)
        }

    private fun newLink(
        command: IssueLinkCommand,
        ref: LinkRef,
        alertEntity: EntityRef,
        digest: String,
    ) = SignedLink(
        ref = ref,
        purpose = command.purpose,
        client = command.client,
        consent = command.consent,
        alertEntity = alertEntity,
        subject = command.subject,
        recipient = LinkRecipient(command.email, command.mobile),
        expiresAt = command.expiresAt,
        passcodeDigest = digest,
        status = LinkStatus.ACTIVE,
        wrongEntries = 0,
        verifiedAt = null,
        invalidatedAt = null,
        source = if (command.originEventId != null) LinkSource.EVENT else LinkSource.CALL,
        sourceEventId = command.originEventId,
        version = 0,
    )

    /** The link by e-mail, then the sealed passcode apart: SMS where a mobile number is on file, else e-mail. */
    private fun requestMessages(
        command: IssueLinkCommand,
        ref: LinkRef,
        passcode: String,
    ) {
        val mobile = command.mobile?.takeIf { it.isNotBlank() }
        val passcodeTo = mobile ?: command.email
        messages.request(MessageRequest(TEMPLATE_SIGNED_LINK, command.email, MessageChannel.EMAIL, ref.value, null))
        messages.request(
            MessageRequest(
                TEMPLATE_SIGNED_LINK_PASSCODE,
                passcodeTo,
                if (mobile != null) MessageChannel.SMS else MessageChannel.EMAIL,
                null,
                sealer.seal(passcode, passcodeTo),
            ),
        )
    }

    private fun refusalOf(
        command: IssueLinkCommand,
        setting: LinkPasscodeSetting,
        now: Instant,
    ): IssueRefusal? =
        when {
            (command.consent == null) != (command.client == null) -> IssueRefusal.PAIR_INCOMPLETE
            command.client == null && command.envelopeEntity == null -> IssueRefusal.NO_ALERT_ENTITY
            command.purpose.isBlank() || command.email.isBlank() -> IssueRefusal.RECIPIENT_OR_PURPOSE_MISSING
            !command.expiresAt.isAfter(now) -> IssueRefusal.EXPIRY_NOT_IN_FUTURE
            setting.smsOnly && command.mobile.isNullOrBlank() -> IssueRefusal.SMS_ONLY_NO_MOBILE
            setting.smsOnly && !SmsGatewayBinding.acceptsSmsOnly(smsGateway) -> IssueRefusal.SMS_ONLY_GATEWAY_NOT_BOUND
            else -> null
        }
}

/** A new link has no earlier state: the prior state of its audit entry says so. */
private const val PRIOR_STATE_NONE = """{"link":null}"""
