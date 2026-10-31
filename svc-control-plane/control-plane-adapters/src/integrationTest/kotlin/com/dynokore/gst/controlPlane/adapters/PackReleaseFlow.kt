package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.bundle.PackBundles
import com.dynokore.gst.controlPlane.adapters.feed.PackFeedIndex
import com.dynokore.gst.controlPlane.adapters.feed.PackFeedReader
import com.dynokore.gst.controlPlane.adapters.feed.PackReleaserJooq
import com.dynokore.gst.controlPlane.adapters.persistence.PackReleaseRecordsJooq
import com.dynokore.gst.controlPlane.adapters.persistence.PackRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.StagingEvidenceReaderJooq
import com.dynokore.gst.controlPlane.adapters.stage.PackPipeline
import com.dynokore.gst.controlPlane.adapters.stage.PackStage
import com.dynokore.gst.controlPlane.adapters.stage.PeriodReplay
import com.dynokore.gst.controlPlane.adapters.stage.SandboxSubmitter
import com.dynokore.gst.controlPlane.adapters.stage.SandboxVerdict
import com.dynokore.gst.controlPlane.adapters.stage.StageOutcome
import com.dynokore.gst.controlPlane.adapters.stage.StagedSubmission
import com.dynokore.gst.controlPlane.adapters.stage.UnboundPackPipeline
import com.dynokore.gst.controlPlane.adapters.testing.SigningFixtures
import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPack
import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPackCommand
import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.PublishPack
import com.dynokore.gst.controlPlane.application.pack.PublishPackCommand
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.pack.SignPack
import com.dynokore.gst.controlPlane.application.pack.SignPackCommand
import com.dynokore.gst.controlPlane.application.release.ConformanceCorpus
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.MinEngineVersion
import com.dynokore.gst.controlPlane.domain.pack.PackContents
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.release.CorpusSection
import com.dynokore.gst.controlPlane.domain.release.DocumentId
import com.dynokore.gst.controlPlane.domain.release.GoldenDocument
import com.dynokore.platform.crypto.BouncyCastleCms
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A clock that moves one minute on every reading, so publications have an order. */
class TickingClock : Clock() {
    private var now = Instant.parse("2026-10-12T08:30:00Z")

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    @Synchronized
    override fun instant(): Instant {
        now = now.plus(Duration.ofMinutes(1))
        return now
    }
}

/** A golden corpus with one document per section, which every pack passes. */
class PassingCorpus : ConformanceCorpus {
    override fun documents() =
        CorpusSection.entries.mapIndexed { i, section -> GoldenDocument(DocumentId("GOLD-${i + 1}"), section, "SR") }

    override fun classify(
        pack: RegulatoryPack,
        document: GoldenDocument,
    ) = document.expected
}

/** The pipeline a test binds: replays a period of two submissions, or fails as [failure] says. */
class TestPipeline(
    var failure: Exception? = null,
    var rejected: Boolean = false,
    var during: () -> Unit = {},
    var submissions: List<StagedSubmission>? = null,
) : PackPipeline {
    override fun replay(pack: RegulatoryPack): PeriodReplay {
        failure?.let { throw it }
        during()
        val sent =
            submissions
                ?: listOf(StagedSubmission("DOC-1", "ok"), StagedSubmission("DOC-2", if (rejected) "REJECT" else "ok"))
        return PeriodReplay("2026-Q3", sent)
    }
}

/** The AP simulator of the certification kit, as a test double: accepts every payload but a `REJECT` one. */
class SimulatorSandbox : SandboxSubmitter {
    override val target = "SIMULATOR"

    override fun submit(submission: StagedSubmission) =
        if (submission.payload == "REJECT") SandboxVerdict.Rejected("schema") else SandboxVerdict.Accepted
}

/**
 * The author, sign and publish path over the real tables, as the use cases of bootstrap compose it. Nothing here is
 * production code: it drives each [com.dynokore.platform.serviceruntime.operation.DeclaredOperation] the way the
 * operation runner does (permits, load, preconditions, perform) in a test.
 */
class PackReleaseFlow(
    val signing: SigningFixtures = SigningFixtures.create(),
) {
    private val dsl = PostgresDb.dsl()
    private val clock = TickingClock()
    private val request = RequestContext("corr-flow", "cause-flow")
    private val roles =
        OperatorRoles { actor, role ->
            when (role) {
                OperatorRole.AUTHOR -> actor == "alice"
                OperatorRole.APPROVER -> actor == "bob"
                else -> false
            }
        }
    val packs = PackRepositoryJooq(dsl, clock)
    val bundles = PackBundles(BouncyCastleCms())
    val index = PackFeedIndex(dsl, clock)
    val reader = PackFeedReader(dsl, index)
    private val staging = StagingEvidenceReaderJooq(dsl)
    private val author = AuthorRegulatoryPack(packs, roles)
    val records = PackReleaseRecordsJooq(dsl)
    val pipeline = TestPipeline()
    val sandbox = SimulatorSandbox()
    val stage = PackStage(dsl, packs, bundles.hasher(), pipeline, sandbox)
    private val sign = SignPack(packs, roles, signing.keys, PassingCorpus(), records, clock)
    private val publish =
        PublishPack(
            packs,
            roles,
            staging,
            bundles.hasher(),
            PackReleaserJooq(dsl, clock, bundles, signing.keys, index),
            records,
            clock,
        )

    /** A pack type no other test uses, so concurrent tests do not supersede each other's versions. */
    val type =
        PackType(
            "T" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
                    .uppercase(),
        )

    fun author(
        version: String,
        contents: String = """{"rules":[{"code":"SR","rate":9}]}""",
        effectiveFrom: LocalDate? = LocalDate.parse("2026-11-01"),
    ): PackRef {
        val command =
            AuthorRegulatoryPackCommand(
                ActorId("alice"),
                type,
                PackVersion(version),
                listOf("IRAS e-Tax Guide 2026-08"),
                effectiveFrom,
                PackContents(contents),
                request,
            )
        author.perform(command, author.load(command))
        return command.ref
    }

    fun sign(
        ref: PackRef,
        engine: String? = "2026.10.0",
    ): String? {
        val command =
            SignPackCommand(ActorId("bob"), ref, signing.keyRef, engine?.let(::MinEngineVersion), false, request)
        val state = sign.load(command)
        return sign.unmetPrecondition(command, state) ?: run {
            sign.perform(command, state)
            null
        }
    }

    /** Runs the real stage of INV-T-4985 over the signed version; the only writer of a staging record. */
    fun recordPassed(ref: PackRef): StageOutcome = stage.run(ref)

    val dslContext get() = dsl

    /** A stage with no pipeline bound, as the service starts. */
    fun unboundStage() = PackStage(dsl, packs, bundles.hasher(), UnboundPackPipeline, sandbox)

    fun publish(ref: PackRef): String? {
        val command = PublishPackCommand(ActorId("bob"), ref, request)
        val state = publish.load(command)
        return publish.unmetPrecondition(command, state) ?: run {
            publish.perform(command, state)
            null
        }
    }

    /** Authors, signs, records a PASSED stage and publishes [version]; every step must be accepted. */
    fun release(
        version: String,
        contents: String = """{"rules":[{"code":"SR","rate":9}]}""",
    ): RegulatoryPack {
        val ref = author(version, contents)
        check(sign(ref) == null) { "signing $ref was refused" }
        recordPassed(ref)
        check(publish(ref) == null) { "publishing $ref was refused" }
        return checkNotNull(packs.find(ref))
    }
}
