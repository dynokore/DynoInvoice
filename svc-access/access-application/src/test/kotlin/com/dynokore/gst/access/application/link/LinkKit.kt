package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.application.alert.AccessAlerts
import com.dynokore.gst.access.application.approval.MappingAcknowledgement
import com.dynokore.gst.access.application.approval.RecordsMappingAcknowledgements
import com.dynokore.gst.access.application.consent.ConsentAuthorityRecord
import com.dynokore.gst.access.application.consent.ConsentChangePublisher
import com.dynokore.gst.access.application.consent.ConsentEffects
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.consent.ConsentWithdrawal
import com.dynokore.gst.access.application.consent.DocumentList
import com.dynokore.gst.access.application.consent.HandoverPorts
import com.dynokore.gst.access.application.consent.HandoverStatement
import com.dynokore.gst.access.application.consent.HandoverStatements
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ConsentTerms
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityMembership
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Fake ports for the signed-link use cases: in memory, one tenant, no database. */
class LinkKit(
    var now: Instant = Instant.parse("2026-11-06T02:00:00Z"),
) {
    val clock: Clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = now
        }
    val tenant = TenantRef("T-1")
    val clientA = EntityRef("E-A")
    val clientB = EntityRef("E-B")
    val envelope = EntityRef("E-ENV")
    val systemActor = "system:access-svc"

    val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    val stored = mutableMapOf<String, SignedLink>()
    val updates = mutableListOf<SignedLink>()
    val alerts = mutableListOf<Pair<String, String>>()
    val audits = mutableListOf<GrantAuditEntry>()
    val messages = mutableListOf<MessageRequest>()
    val reads = mutableListOf<String>()
    var setting = LinkPasscodeSetting.DEFAULT
    var smsBound = false

    val links =
        object : SignedLinks {
            override fun find(ref: LinkRef) = stored[ref.value]

            override fun lock(ref: LinkRef) = stored[ref.value]

            override fun findBySourceEvent(eventId: String) = stored.values.firstOrNull { it.sourceEventId == eventId }

            override fun insert(link: SignedLink) {
                check(link.ref.value !in stored) { "duplicate link ref" }
                stored[link.ref.value] = link
            }

            override fun update(
                before: SignedLink,
                after: SignedLink,
            ) {
                check(stored[before.ref.value] == before) { "stale" }
                stored[after.ref.value] = after
                updates += after
            }
        }

    val digests =
        object : PasscodeDigests {
            override fun digestOf(
                ref: LinkRef,
                passcode: String,
            ) = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("${ref.value}:$passcode".toByteArray()),
            )

            override fun matches(
                ref: LinkRef,
                passcode: String,
                digest: String,
            ) = MessageDigest.isEqual(digestOf(ref, passcode).toByteArray(), digest.toByteArray())
        }

    val audit =
        object : GrantAudit {
            override fun record(entry: GrantAuditEntry) {
                audits += entry
            }
        }
    val accessAlerts = AccessAlerts { code, scope -> alerts += code to scope }
    val settings = LinkPasscodeSettings { setting }

    // the test delivery key pair: 3072 bits, as the form requires
    val keyPair: java.security.KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    val privateKey = keyPair.private as RSAPrivateKey
    val publicKey = keyPair.public as RSAPublicKey
    val keyId = SealingKey.keyIdOf(publicKey)
    val sealer = OaepPasscodeSealer { SealingKey(keyId, publicKey) }

    val issue =
        IssueSignedLinkUseCase(
            tx,
            links,
            SecureLinkSecrets(),
            digests,
            sealer,
            settings,
            { smsBound },
            { messages += it },
            audit,
            systemActor,
            clock,
        )
    val verify = VerifySignatoryLinkUseCase(tx, links, digests, settings, accessAlerts, clock)

    // consents, grants-free decision, evidence
    val consents = mutableMapOf<String, Consent>()
    val withdrawals = mutableListOf<String>()
    val consentRepo =
        object : ConsentRepository {
            override fun find(ref: ConsentRef) = consents[ref.value].also { reads += "consent:${ref.value}" }

            override fun lock(ref: ConsentRef) = consents[ref.value].also { reads += "consent:${ref.value}" }

            override fun findLiveForClient(client: EntityRef) =
                consents.values
                    .firstOrNull { it.client == client && it.status == ConsentStatus.LIVE }
                    .also { reads += "client:${client.value}" }

            override fun insert(consent: Consent) {
                consents[consent.ref.value] = consent
            }

            override fun update(
                before: Consent,
                after: Consent,
            ) {
                consents[after.ref.value] = after
                withdrawals += after.ref.value
            }

            override fun nextAuthorityVersion() = 1L

            override fun nextRef() = ConsentRef("C-NEXT")
        }
    val statements =
        object : HandoverStatements {
            override fun insert(statement: HandoverStatement) = Unit

            override fun find(ref: String): HandoverStatement? = null

            override fun nextRef() = "HS-1"
        }
    val withdrawal =
        ConsentWithdrawal(
            consentRepo,
            HandoverPorts({ DocumentList.Listed(emptyList()) }, statements, { _, _ -> }),
            ConsentEffects(ConsentChangePublisher { _: ConsentAuthorityRecord -> }, audit),
            clock,
        )
    val acks = mutableListOf<List<String>>()
    val acknowledgements =
        object : RecordsMappingAcknowledgements {
            override fun find(
                packVersion: String,
                clientRef: String,
            ): MappingAcknowledgement? = null

            override fun record(
                packVersion: String,
                clientRef: String,
                by: String,
                at: Instant,
            ): MappingAcknowledgement {
                acks += listOf(packVersion, clientRef, by)
                return MappingAcknowledgement(packVersion, clientRef, at)
            }
        }
    val evidence = mutableListOf<SignatoryAction>()
    val actions =
        object : SignatoryActions {
            override fun find(
                linkRef: LinkRef,
                kind: SignatoryActionKind,
                packVersion: String,
            ) = evidence.firstOrNull { it.linkRef == linkRef && it.kind == kind && it.packVersion == packVersion }

            override fun insert(action: SignatoryAction) {
                evidence += action
            }
        }

    /** The catalogue cells that matter: client.signatory holds the two keys WITHIN_SCOPE and nothing else. */
    val matrix =
        PermissionMatrix(
            listOf(
                PermissionDefinition(LinkPermissions.CONSENT_WITHDRAW, PermissionKind.WRITE),
                PermissionDefinition(LinkPermissions.MAPPING_EFFECT_ACKNOWLEDGE, PermissionKind.WRITE),
                PermissionDefinition(Permission("grant.role"), PermissionKind.WRITE),
            ),
            mapOf(
                Role.CLIENT_SIGNATORY to
                    mapOf(
                        LinkPermissions.CONSENT_WITHDRAW to Allowance.WITHIN_SCOPE,
                        LinkPermissions.MAPPING_EFFECT_ACKNOWLEDGE to Allowance.WITHIN_SCOPE,
                    ),
                Role.TENANT_ADMIN to mapOf(Permission("grant.role") to Allowance.PERMITTED),
            ),
        )
    val directory =
        object : EntityDirectory {
            override fun membership(entity: EntityRef) =
                if (entity in setOf(clientA, clientB)) EntityMembership(tenant, null) else null

            override fun entitiesCoveredBy(scope: Scope) = emptySet<EntityRef>()
        }
    val record =
        RecordSignatoryActionUseCase(
            tx,
            links,
            LinkPermissions(matrix, directory),
            actions,
            consentRepo,
            withdrawal,
            acknowledgements,
            audit,
            clock,
        )

    fun consent(
        ref: String,
        client: EntityRef,
    ) = Consent(
        ConsentRef(ref),
        client,
        IdentityRef("agent-1"),
        ConsentTerms(setOf(ScopeItem.MAPPING_APPROVAL), LocalDate.parse("2026-01-01"), "sig@example.com"),
        ConsentStatus.LIVE,
        null,
        0,
    )

    fun command(
        consent: ConsentRef? = ConsentRef("C-A"),
        client: EntityRef? = clientA,
        mobile: String? = "+6591234567",
        eventId: String? = null,
        envelopeEntity: EntityRef? = null,
    ) = IssueLinkCommand(
        "SIGN_CONSENT",
        "SUBJ-1",
        "sig@example.com",
        mobile,
        now.plusSeconds(3600),
        consent,
        client,
        envelopeEntity,
        eventId,
    )

    /** Issues a link and returns it with its passcode opened by the test private key. */
    fun issued(command: IssueLinkCommand = command()): Pair<SignedLink, String> {
        val out = issue.handle(command) as IssueLinkOutcome.Issued
        val sealed = messages.last().sealedPasscode!!
        return stored.getValue(out.ref.value) to open(sealed, messages.last().recipient)
    }

    fun open(
        sealed: String,
        label: String,
    ): String {
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            privateKey,
            javax.crypto.spec.OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                java.security.spec.MGF1ParameterSpec.SHA256,
                javax.crypto.spec.PSource
                    .PSpecified(label.toByteArray()),
            ),
        )
        val ciphertext =
            java.util.Base64
                .getUrlDecoder()
                .decode(sealed.split(".")[2])
        return String(cipher.doFinal(ciphertext))
    }
}
