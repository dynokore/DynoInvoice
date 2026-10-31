package com.dynokore.gst.controlPlane.application.pack

import com.dynokore.gst.controlPlane.application.release.ConformanceCorpus
import com.dynokore.gst.controlPlane.application.release.PackReleaseRecords
import com.dynokore.gst.controlPlane.application.release.describe
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.MinEngineVersion
import com.dynokore.gst.controlPlane.domain.pack.PackContents
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import com.dynokore.gst.controlPlane.domain.pack.PublicationGate
import com.dynokore.gst.controlPlane.domain.pack.PublicationRefusal
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.pack.SignOutcome
import com.dynokore.gst.controlPlane.domain.release.ConformanceRun
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.GateRun
import com.dynokore.gst.controlPlane.domain.release.GateVerdict
import com.dynokore.gst.controlPlane.domain.release.ReleaseGates
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import com.dynokore.platform.serviceruntime.operation.OperationAudit
import com.dynokore.platform.serviceruntime.operation.Reversibility
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.LocalDate

private const val SCOPE = "platform"

private fun auditContext(
    ref: PackRef,
    request: RequestContext,
) = EventContext(
    tenant = TenantId.PLATFORM,
    entity = EntityRef.PLATFORM,
    docUuid = null,
    transactionDate = null,
    aggregateKey = ref.value,
    aggregateVersion = null,
    correlation = CorrelationId(request.correlationId),
    causation = CausationId(request.causationId),
)

data class AuthorRegulatoryPackCommand(
    val actor: ActorId,
    val packType: PackType,
    val version: PackVersion,
    val citations: List<String>,
    val effectiveFrom: LocalDate?,
    val contents: PackContents?,
    val request: RequestContext,
) {
    val ref: PackRef get() = PackRef.of(packType, version)
}

/**
 * authorRegulatoryPack (SCR-36.1): creates a draft with citations. Every call is recorded: the caller becomes an
 * author of the version (INV-REQ-2590 b2). A repeat returns the original draft (repeat key: pack type and version).
 */
class AuthorRegulatoryPack(
    private val packs: PackRepository,
    private val roles: OperatorRoles,
) : DeclaredOperation<AuthorRegulatoryPackCommand, RegulatoryPack?, RegulatoryPack> {
    override val operationId = "authorRegulatoryPack"
    override val reversibility = Reversibility.UNDOABLE
    override val audited = true

    override fun permits(
        actor: String,
        command: AuthorRegulatoryPackCommand,
    ) = roles.holds(actor, OperatorRole.AUTHOR) && actor == command.actor.value

    override fun repeatKey(command: AuthorRegulatoryPackCommand) = command.ref.value

    override fun load(command: AuthorRegulatoryPackCommand) = packs.find(command.ref)

    /** A repeat by an identity that already authored the version, or on a version past draft, changes nothing. */
    override fun priorResult(
        command: AuthorRegulatoryPackCommand,
        state: RegulatoryPack?,
    ) = state?.takeIf { it.status != PackStatus.DRAFT || command.actor in it.authors }

    override fun unmetPrecondition(
        command: AuthorRegulatoryPackCommand,
        state: RegulatoryPack?,
    ): String? = null

    override fun perform(
        command: AuthorRegulatoryPackCommand,
        state: RegulatoryPack?,
    ): RegulatoryPack {
        val pack =
            state?.authoredBy(command.actor)
                ?: RegulatoryPack.draft(
                    command.packType,
                    command.version,
                    command.citations,
                    command.actor,
                    command.effectiveFrom,
                    command.contents,
                )
        packs.save(pack)
        return pack
    }

    override fun audit(
        command: AuthorRegulatoryPackCommand,
        state: RegulatoryPack?,
        result: RegulatoryPack,
    ) = OperationAudit(SCOPE, null, null, auditContext(command.ref, command.request))
}

data class SignPackCommand(
    val actor: ActorId,
    val ref: PackRef,
    val signingKeyRef: String,
    val minEngineVersion: MinEngineVersion?,
    val exposureReducing: Boolean,
    val request: RequestContext,
)

/**
 * signPack (SCR-36.3): a regulatory.approver who is not an author of the version signs it. The separation is
 * non-waivable (INV-REQ-2590): a person holding both roles signs only a version they did not edit. The full rule
 * conformance corpus runs first (INV-REQ-2660): a pack that breaks a golden document is rejected, and a rule-correction
 * pack is rejected only for a golden document its released predecessor passes (INV-AC-2674). The corpus and the
 * classification are recorded with the release (INV-REQ-2657).
 */
class SignPack(
    private val packs: PackRepository,
    private val roles: OperatorRoles,
    private val keys: PackSigningKeys,
    private val corpus: ConformanceCorpus,
    private val records: PackReleaseRecords,
    private val clock: Clock,
) : DeclaredOperation<SignPackCommand, RegulatoryPack?, RegulatoryPack> {
    override val operationId = "signPack"
    override val reversibility = Reversibility.COMPENSABLE
    override val audited = true

    override fun permits(
        actor: String,
        command: SignPackCommand,
    ) = roles.holds(actor, OperatorRole.APPROVER) && actor == command.actor.value

    override fun repeatKey(command: SignPackCommand) = command.ref.value

    override fun load(command: SignPackCommand) = packs.find(command.ref)

    override fun priorResult(
        command: SignPackCommand,
        state: RegulatoryPack?,
    ) = state?.takeIf { it.status != PackStatus.DRAFT }

    override fun unmetPrecondition(
        command: SignPackCommand,
        state: RegulatoryPack?,
    ): String? =
        when {
            state == null -> {
                Refusal(Refusal.PACK_NOT_FOUND, command.ref.value).encode()
            }

            command.signingKeyRef.isBlank() -> {
                Refusal(Refusal.SIGNING_KEY_MISSING, command.ref.value).encode()
            }

            command.actor in state.authors -> {
                Refusal(Refusal.SEPARATION_OF_DUTY, command.ref.value).encode()
            }

            keys.resolve(command.signingKeyRef) == null -> {
                Refusal(Refusal.SIGNING_KEY_MISSING, "${command.ref.value} unknown signingKeyRef").encode()
            }

            state.effectiveFrom == null -> {
                Refusal(Refusal.EFFECTIVE_FROM_MISSING, command.ref.value).encode()
            }

            state.contents == null -> {
                Refusal(Refusal.CONTENTS_MISSING, command.ref.value).encode()
            }

            else -> {
                corpusRefusal(command, state)
            }
        }

    /** The corpus gate: no complete corpus, no signature (fail closed); a blocking golden document, no signature. */
    private fun corpusRefusal(
        command: SignPackCommand,
        pack: RegulatoryPack,
    ): String? {
        val verdict = corpusVerdict(pack, command.exposureReducing)
        return when {
            verdict == null -> Refusal(Refusal.CORPUS_UNAVAILABLE, command.ref.value).encode()
            !verdict.releasable -> Refusal(Refusal.CORPUS_FAILED, "${command.ref.value} ${verdict.describe()}").encode()
            else -> null
        }
    }

    private fun corpusVerdict(
        pack: RegulatoryPack,
        exposureReducing: Boolean,
    ): GateVerdict? {
        val documents = corpus.documents()
        val candidate = ConformanceRun.run(documents) { corpus.classify(pack, it) }
        if (!candidate.complete) return null
        val released =
            packs.latestPublished(pack.type)?.let(packs::find)?.let { predecessor ->
                ConformanceRun.run(documents) { corpus.classify(predecessor, it) }
            }
        return ReleaseGates.evaluate(
            setOf(Gate.CORPUS),
            listOf(ConformanceRun.gateRun(candidate, released)),
            exposureReducing,
        )
    }

    override fun perform(
        command: SignPackCommand,
        state: RegulatoryPack?,
    ): RegulatoryPack {
        val outcome =
            checkNotNull(state).sign(
                command.actor,
                command.signingKeyRef,
                command.minEngineVersion,
                command.exposureReducing,
                clock.instant(),
            )
        val signed =
            (outcome as? SignOutcome.Signed)?.pack ?: error("signPack performed on a version the preconditions refuse")
        packs.save(signed)
        val verdict =
            checkNotNull(corpusVerdict(state, command.exposureReducing)) { "signPack performed without a corpus" }
        records.save(
            signed.ref,
            ReleaseRecord(verdict.runs, command.exposureReducing, command.actor, clock.instant()),
        )
        return signed
    }

    override fun audit(
        command: SignPackCommand,
        state: RegulatoryPack?,
        result: RegulatoryPack,
    ) = OperationAudit(SCOPE, null, null, auditContext(command.ref, command.request))
}

data class PublishPackCommand(
    val actor: ActorId,
    val ref: PackRef,
    val request: RequestContext,
)

/**
 * publishPack (SCR-36.4): publishes a signed version to the feed and as a bundle. Irreversible; a correction is a new
 * version that supersedes this one. The staging gate (coordinator ruling INV-Q-4878) runs here, fails closed and has
 * no waiver: no staging record with result PASSED over the hash of the content computed now, no publication.
 */
@Suppress("LongParameterList") // each parameter is one port of the publication, composed in bootstrap (TPL-18)
class PublishPack(
    private val packs: PackRepository,
    private val roles: OperatorRoles,
    private val staging: StagingEvidenceReader,
    private val hasher: PackContentHasher,
    private val releaser: PackReleaser,
    private val records: PackReleaseRecords,
    private val clock: Clock,
) : DeclaredOperation<PublishPackCommand, RegulatoryPack?, RegulatoryPack> {
    override val operationId = "publishPack"
    override val reversibility = Reversibility.IRREVERSIBLE
    override val audited = true

    override fun permits(
        actor: String,
        command: PublishPackCommand,
    ) = roles.holds(actor, OperatorRole.APPROVER) && actor == command.actor.value

    override fun repeatKey(command: PublishPackCommand) = command.ref.value

    override fun load(command: PublishPackCommand) = packs.find(command.ref)

    override fun priorResult(
        command: PublishPackCommand,
        state: RegulatoryPack?,
    ) = state?.takeIf { it.status == PackStatus.PUBLISHED }

    override fun unmetPrecondition(
        command: PublishPackCommand,
        state: RegulatoryPack?,
    ): String? {
        if (state == null) return Refusal(Refusal.PACK_NOT_FOUND, command.ref.value).encode()
        val refusal =
            if (state.status == PackStatus.SIGNED) {
                PublicationGate.check(state, staging.forPack(state.ref), hasher.hash(state))
            } else {
                PublicationRefusal.NotSigned(state.ref)
            }
        return refusal?.let(::refusalOf)?.encode()
    }

    override fun perform(
        command: PublishPackCommand,
        state: RegulatoryPack?,
    ): RegulatoryPack {
        val signed = checkNotNull(state)
        val published = signed.published(clock.instant(), packs.latestPublished(signed.type))
        // the hash the gate accepted, over the same content, as at the moment of the check (INV-Q-4878)
        releaser.release(published, hasher.hash(published))
        packs.save(published)
        // the staging gate passed, or the version would not be here (INV-Q-4878): it joins the corpus run on the record
        records.find(published.ref)?.let { record ->
            val staged = GateRun(Gate.STAGING, GateOutcome.PASSED)
            records.save(published.ref, record.copy(runs = record.runs.filter { it.gate != Gate.STAGING } + staged))
        }
        return published
    }

    override fun audit(
        command: PublishPackCommand,
        state: RegulatoryPack?,
        result: RegulatoryPack,
    ) = OperationAudit(SCOPE, null, null, auditContext(command.ref, command.request))

    private fun refusalOf(refusal: PublicationRefusal): Refusal =
        when (refusal) {
            is PublicationRefusal.NotSigned -> {
                Refusal(Refusal.NOT_SIGNED, refusal.packRef.value)
            }

            is PublicationRefusal.MinEngineVersionMissing -> {
                Refusal(Refusal.MIN_ENGINE_VERSION_MISSING, refusal.packRef.value)
            }

            is PublicationRefusal.NoStagingRecord -> {
                Refusal(Refusal.NO_STAGING_RECORD, refusal.packRef.value)
            }

            is PublicationRefusal.StagingNotPassed -> {
                Refusal(Refusal.STAGING_NOT_PASSED, "${refusal.packRef} results=${refusal.results.joinToString(",")}")
            }

            is PublicationRefusal.StagingHashMismatch -> {
                Refusal(
                    Refusal.STAGING_HASH_MISMATCH,
                    "${refusal.packRef} recorded=${refusal.recordedHash} current=${refusal.currentHash}",
                )
            }
        }
}

/** getPack: reads a draft, signed or published version. */
class GetPack(
    private val packs: PackRepository,
) {
    fun get(ref: PackRef): RegulatoryPack? = packs.find(ref)
}
