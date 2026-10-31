package com.dynokore.gst.controlPlane.application.pack

import com.dynokore.gst.controlPlane.application.release.FakeCorpus
import com.dynokore.gst.controlPlane.application.release.MemoryRecords
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.ContentHash
import com.dynokore.gst.controlPlane.domain.pack.MinEngineVersion
import com.dynokore.gst.controlPlane.domain.pack.PackContents
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.pack.StagingEvidence
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class PackOperationsTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-12T08:30:00Z"), ZoneOffset.UTC)
    private val request = RequestContext("corr-1", "cause-1")
    private val store = LinkedHashMap<PackRef, RegulatoryPack>()
    private val evidence = ArrayList<StagingEvidence>()
    private val released = ArrayList<PackRef>()
    private val bothRoles = setOf("pat", "bob")
    private val roles =
        OperatorRoles { actor, role ->
            when (role) {
                OperatorRole.AUTHOR -> actor == "alice" || actor == "pat"
                OperatorRole.APPROVER -> actor == "bob" || actor in bothRoles
                else -> false
            }
        }
    private val repository =
        object : PackRepository {
            override fun find(ref: PackRef) = store[ref]

            override fun save(pack: RegulatoryPack) {
                store[pack.ref] = pack
            }

            override fun latestPublished(type: PackType) =
                store.values
                    .filter { it.type == type && it.status == PackStatus.PUBLISHED }
                    .maxByOrNull { it.publishedAt!! }
                    ?.ref
        }
    private var content = "content-v1"
    private val hasher = PackContentHasher { ContentHash(sha(content)) }
    private val author = AuthorRegulatoryPack(repository, roles)
    private val keys = PackSigningKeys { ref -> if (ref == "KEY-REG-01") StubKey else null }
    private val corpus = FakeCorpus()
    private val records = MemoryRecords()
    private val sign = SignPack(repository, roles, keys, corpus, records, clock)
    private val staging = StagingEvidenceReader { ref -> evidence.filter { it.packRef == ref } }
    private val publish =
        PublishPack(repository, roles, staging, hasher, { pack, _ -> released += pack.ref }, records, clock)

    private fun sha(text: String) =
        MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(text.toByteArray())
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun authorAs(
        actor: String,
        version: String = "2026.3",
        type: String = "SCOPE",
        effectiveFrom: LocalDate? = LocalDate.parse("2026-10-01"),
        contents: PackContents? = PackContents("{\"rules\":[]}"),
    ) = AuthorRegulatoryPackCommand(
        ActorId(actor),
        PackType(type),
        PackVersion(version),
        listOf("IRAS e-Tax Guide 2026-08"),
        effectiveFrom,
        contents,
        request,
    ).also {
        check(author.permits(actor, it))
        val state = author.load(it)
        if (author.priorResult(it, state) == null) author.perform(it, state)
    }

    private fun signAs(
        actor: String,
        engine: String? = "2026.10.0",
        ref: PackRef = PackRef("PACK-SCOPE-2026.3"),
        keyRef: String = "KEY-REG-01",
        exposureReducing: Boolean = false,
    ): String? {
        val cmd =
            SignPackCommand(
                ActorId(actor),
                ref,
                keyRef,
                engine?.let(::MinEngineVersion),
                exposureReducing,
                request,
            )
        val state = sign.load(cmd)
        val unmet = if (sign.priorResult(cmd, state) == null) sign.unmetPrecondition(cmd, state) else null
        if (unmet == null && sign.priorResult(cmd, state) == null) sign.perform(cmd, state)
        return unmet
    }

    private fun publishAs(
        actor: String,
        ref: PackRef = PackRef("PACK-SCOPE-2026.3"),
    ): String? {
        val cmd = PublishPackCommand(ActorId(actor), ref, request)
        val state = publish.load(cmd)
        val unmet = publish.unmetPrecondition(cmd, state)
        if (unmet == null) publish.perform(cmd, state)
        return unmet
    }

    private val ref = PackRef("PACK-SCOPE-2026.3")

    private fun passed(hash: String = sha(content)) =
        evidence.add(StagingEvidence(ref, StagingResult.PASSED, ContentHash(hash)))

    @Test
    fun `INV-AC-2591 an editor with both roles cannot sign and publishPack is refused as not signed`() {
        authorAs("alice")
        authorAs("pat") // pat holds both regulatory roles and edits through authorRegulatoryPack
        Refusal.parse(signAs("pat")!!).code shouldBe Refusal.SEPARATION_OF_DUTY
        store.getValue(ref).status shouldBe PackStatus.DRAFT
        Refusal.parse(publishAs("bob")!!).code shouldBe Refusal.NOT_SIGNED
    }

    @Test
    fun `INV-AC-2595 a person with both roles signs a version that only someone else edited`() {
        authorAs("alice")
        signAs("pat") shouldBe null
        val pack = store.getValue(ref)
        pack.status shouldBe PackStatus.SIGNED
        pack.signer shouldBe ActorId("pat")
        pack.authors shouldBe setOf(ActorId("alice"))
    }

    @Test
    fun `authorRegulatoryPack by a repeat caller returns the original draft and records the caller as author`() {
        authorAs("alice")
        authorAs("pat")
        store.getValue(ref).authors shouldBe setOf(ActorId("alice"), ActorId("pat"))
        store.getValue(ref).citations shouldBe listOf("IRAS e-Tax Guide 2026-08")
    }

    @Test
    fun `publishPack with no staging record is refused and the pack stays signed`() {
        authorAs("alice")
        signAs("bob")
        Refusal.parse(publishAs("bob")!!).code shouldBe Refusal.NO_STAGING_RECORD
        store.getValue(ref).status shouldBe PackStatus.SIGNED
        released shouldBe emptyList()
    }

    @Test
    fun `publishPack with a FAILED record is refused`() {
        authorAs("alice")
        signAs("bob")
        evidence.add(StagingEvidence(ref, StagingResult.FAILED, ContentHash(sha(content))))
        Refusal.parse(publishAs("bob")!!).code shouldBe Refusal.STAGING_NOT_PASSED
        store.getValue(ref).status shouldBe PackStatus.SIGNED
    }

    @Test
    fun `publishPack with a record of another version is refused`() {
        authorAs("alice")
        signAs("bob")
        evidence.add(StagingEvidence(PackRef("PACK-SCOPE-2026.4"), StagingResult.PASSED, ContentHash(sha(content))))
        Refusal.parse(publishAs("bob")!!).code shouldBe Refusal.NO_STAGING_RECORD
    }

    @Test
    fun `publishPack with a PASSED record after one byte of the content changed is refused naming both hashes`() {
        authorAs("alice")
        signAs("bob")
        passed()
        content = "content-v2"
        val refusal = Refusal.parse(publishAs("bob")!!)
        refusal.code shouldBe Refusal.STAGING_HASH_MISMATCH
        refusal.detail.contains("recorded=${sha("content-v1")}") shouldBe true
        refusal.detail.contains("current=${sha("content-v2")}") shouldBe true
        store.getValue(ref).status shouldBe PackStatus.SIGNED
    }

    @Test
    fun `INV-AC-2078 publishPack of a signed version without a minimum engine version is refused`() {
        authorAs("alice")
        signAs("bob", engine = null)
        passed()
        Refusal.parse(publishAs("bob")!!).code shouldBe Refusal.MIN_ENGINE_VERSION_MISSING
        released shouldBe emptyList()
    }

    @Test
    fun `publishPack with a PASSED record over the current content publishes and releases once`() {
        authorAs("alice")
        signAs("bob")
        passed()
        publishAs("bob") shouldBe null
        store.getValue(ref).status shouldBe PackStatus.PUBLISHED
        released shouldBe listOf(ref)
    }

    @Test
    fun `signPack of a version authored without effectiveFrom or without contents is refused and stays unsigned`() {
        authorAs("alice", version = "2026.6", effectiveFrom = null)
        Refusal.parse(signAs("bob", ref = PackRef("PACK-SCOPE-2026.6"))!!).code shouldBe Refusal.EFFECTIVE_FROM_MISSING
        store.getValue(PackRef("PACK-SCOPE-2026.6")).status shouldBe PackStatus.DRAFT
        authorAs("alice", version = "2026.7", contents = null)
        Refusal.parse(signAs("bob", ref = PackRef("PACK-SCOPE-2026.7"))!!).code shouldBe Refusal.CONTENTS_MISSING
        store.getValue(PackRef("PACK-SCOPE-2026.7")).status shouldBe PackStatus.DRAFT
    }

    @Test
    fun `signPack with an unknown signing key reference is refused and stays unsigned`() {
        authorAs("alice")
        Refusal.parse(signAs("bob", keyRef = "KEY-OTHER")!!).code shouldBe Refusal.SIGNING_KEY_MISSING
        store.getValue(ref).status shouldBe PackStatus.DRAFT
    }

    @Test
    fun `INV-AC-2594 a second version supersedes the latest published one and the first stays published`() {
        fun publishVersion(
            type: String,
            version: String,
        ): PackRef {
            val pack = PackRef("PACK-$type-$version")
            authorAs("alice", version = version, type = type)
            signAs("bob", ref = pack) shouldBe null
            evidence.add(StagingEvidence(pack, StagingResult.PASSED, ContentHash(sha(content))))
            publishAs("bob", pack) shouldBe null
            return pack
        }
        val first = publishVersion("SCOPE", "2026.1")
        store.getValue(first).supersedes shouldBe null
        publishVersion("MDE", "2026.1")
        val second = publishVersion("SCOPE", "2026.2")
        store.getValue(second).supersedes shouldBe first
        store.getValue(first).status shouldBe PackStatus.PUBLISHED
        store.getValue(PackRef("PACK-MDE-2026.1")).supersedes shouldBe null
    }

    private fun released(version: String): PackRef {
        val pack = PackRef("PACK-SCOPE-$version")
        authorAs("alice", version = version)
        signAs("bob", ref = pack) shouldBe null
        evidence.add(StagingEvidence(pack, StagingResult.PASSED, ContentHash(sha(content))))
        publishAs("bob", pack) shouldBe null
        return pack
    }

    private fun candidate(
        version: String,
        breaks: Set<String>,
        exposureReducing: Boolean,
    ): String? {
        val pack = PackRef("PACK-SCOPE-$version")
        authorAs("alice", version = version)
        corpus.broken[pack] = breaks
        return signAs("bob", ref = pack, exposureReducing = exposureReducing)
    }

    @Test
    fun `INV-AC-2661 a pack that breaks a golden document is rejected before any delta and stays unsigned`() {
        val refusal = Refusal.parse(candidate("2026.3", setOf("GOLD-1"), exposureReducing = false)!!)
        refusal.code shouldBe Refusal.CORPUS_FAILED
        refusal.detail.contains("GOLD-1") shouldBe true
        store.getValue(ref).status shouldBe PackStatus.DRAFT
        records.store shouldBe emptyMap()
        publishAs("bob").let { Refusal.parse(it!!).code shouldBe Refusal.NOT_SIGNED }
    }

    @Test
    fun `INV-AC-2661 a pack that passes every golden document is signed and the corpus run is recorded`() {
        candidate("2026.3", emptySet(), exposureReducing = false) shouldBe null
        val record = records.store.getValue(ref)
        record.runs.map { it.gate to it.candidate } shouldBe listOf(Gate.CORPUS to GateOutcome.PASSED)
        record.classifiedBy shouldBe ActorId("bob")
    }

    @Test
    fun `INV-AC-2674 a rule-correction pack is released despite a golden failure its released predecessor has`() {
        val first = released("2026.2")
        corpus.broken[first] = setOf("GOLD-3")
        candidate("2026.3", setOf("GOLD-3"), exposureReducing = true) shouldBe null
        val run =
            records.store
                .getValue(ref)
                .runs
                .single()
        run.candidate shouldBe GateOutcome.FAILED
        run.baseline shouldBe GateOutcome.FAILED
        run.findings shouldBe setOf("GOLD-3")
        run.baselineFindings shouldBe setOf("GOLD-3")
        records.store.getValue(ref).exposureReducing shouldBe true
        records.store.getValue(ref).classifiedBy shouldBe ActorId("bob")
    }

    @Test
    fun `INV-AC-2674 a rule-correction pack that breaks a second golden document is blocked and names it`() {
        val first = released("2026.2")
        corpus.broken[first] = setOf("GOLD-3")
        val refusal = Refusal.parse(candidate("2026.3", setOf("GOLD-3", "GOLD-4"), exposureReducing = true)!!)
        refusal.code shouldBe Refusal.CORPUS_FAILED
        refusal.detail.contains("introduced=[GOLD-4]") shouldBe true
        store.getValue(ref).status shouldBe PackStatus.DRAFT
    }

    @Test
    fun `INV-AC-2674 the same inherited failure blocks a pack that is not classified as exposure-reducing`() {
        val first = released("2026.2")
        corpus.broken[first] = setOf("GOLD-3")
        Refusal.parse(candidate("2026.3", setOf("GOLD-3"), exposureReducing = false)!!).code shouldBe
            Refusal.CORPUS_FAILED
    }

    @Test
    fun `an exposure-reducing pack whose predecessor passed the golden document it breaks is blocked`() {
        released("2026.2")
        Refusal.parse(candidate("2026.3", setOf("GOLD-2"), exposureReducing = true)!!).code shouldBe
            Refusal.CORPUS_FAILED
    }

    @Test
    fun `a corpus missing a section or empty refuses every signature`() {
        authorAs("alice")
        corpus.documents = FakeCorpus.standard().drop(1)
        Refusal.parse(signAs("bob")!!).code shouldBe Refusal.CORPUS_UNAVAILABLE
        corpus.documents = emptyList()
        Refusal.parse(signAs("bob")!!).code shouldBe Refusal.CORPUS_UNAVAILABLE
        store.getValue(ref).status shouldBe PackStatus.DRAFT
    }

    @Test
    fun `publishPack adds the staging run to the release record that holds the corpus run`() {
        candidate("2026.3", emptySet(), exposureReducing = false) shouldBe null
        passed()
        publishAs("bob") shouldBe null
        records.store
            .getValue(ref)
            .runs
            .map { it.gate } shouldBe listOf(Gate.CORPUS, Gate.STAGING)
    }
}

private object StubKey : com.dynokore.platform.crypto.SigningKey {
    override val algorithm = com.dynokore.platform.crypto.SignatureAlgorithm.ECDSA_P256_SHA256
    override val certificateDer = ByteArray(1)

    override fun sign(data: ByteArray) = data
}
