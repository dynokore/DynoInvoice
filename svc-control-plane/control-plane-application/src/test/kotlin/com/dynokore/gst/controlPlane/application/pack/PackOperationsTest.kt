package com.dynokore.gst.controlPlane.application.pack

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
    private val sign = SignPack(repository, roles, keys, clock)
    private val staging = StagingEvidenceReader { ref -> evidence.filter { it.packRef == ref } }
    private val publish = PublishPack(repository, roles, staging, hasher, { pack, _ -> released += pack.ref }, clock)

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
    ): String? {
        val cmd =
            SignPackCommand(
                ActorId(actor),
                ref,
                keyRef,
                engine?.let(::MinEngineVersion),
                false,
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
}

private object StubKey : com.dynokore.platform.crypto.SigningKey {
    override val algorithm = com.dynokore.platform.crypto.SignatureAlgorithm.ECDSA_P256_SHA256
    override val certificateDer = ByteArray(1)

    override fun sign(data: ByteArray) = data
}
