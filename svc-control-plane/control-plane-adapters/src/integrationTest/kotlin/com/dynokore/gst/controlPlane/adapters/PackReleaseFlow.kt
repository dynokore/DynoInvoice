package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.bundle.PackBundles
import com.dynokore.gst.controlPlane.adapters.feed.PackFeedIndex
import com.dynokore.gst.controlPlane.adapters.feed.PackFeedReader
import com.dynokore.gst.controlPlane.adapters.feed.PackReleaserJooq
import com.dynokore.gst.controlPlane.adapters.persistence.PackRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.StagingEvidenceReaderJooq
import com.dynokore.gst.controlPlane.adapters.stage.StagingOutcome
import com.dynokore.gst.controlPlane.adapters.stage.StagingRecordWriter
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
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.MinEngineVersion
import com.dynokore.gst.controlPlane.domain.pack.PackContents
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
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
    private val sign = SignPack(packs, roles, signing.keys, clock)
    private val publish =
        PublishPack(
            packs,
            roles,
            staging,
            bundles.hasher(),
            PackReleaserJooq(dsl, clock, bundles, signing.keys, index),
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

    /** The PASSED record the stage of INV-T-4985 writes, called here through the sys-owned function. */
    fun recordPassed(ref: PackRef) {
        val pack = checkNotNull(packs.find(ref))
        StagingRecordWriter(dsl).record(
            StagingOutcome(
                ref,
                StagingResult.PASSED,
                bundles.hasher().hash(pack),
                "SIMULATOR",
                "evidence/$ref",
                "2026-Q3",
                "stage",
            ),
        )
    }

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
