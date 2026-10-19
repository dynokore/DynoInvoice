package com.dynokore.gst.controlPlane.application.pack

import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.ContentHash
import com.dynokore.gst.controlPlane.domain.pack.MinEngineVersion
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
import java.time.ZoneOffset

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
        }
    private var content = "content-v1"
    private val hasher = PackContentHasher { ContentHash(sha(content)) }
    private val author = AuthorRegulatoryPack(repository, roles)
    private val sign = SignPack(repository, roles, clock)
    private val publish = PublishPack(repository, roles, { ref -> evidence.filter { it.packRef == ref } }, hasher, { released += it.ref }, clock)

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun authorAs(actor: String) =
        AuthorRegulatoryPackCommand(ActorId(actor), PackType("SCOPE"), PackVersion("2026.3"), listOf("IRAS e-Tax Guide 2026-08"), request).also {
            check(author.permits(actor, it))
            val state = author.load(it)
            if (author.priorResult(it, state) == null) author.perform(it, state)
        }

    private fun signAs(
        actor: String,
        engine: String? = "2026.10.0",
    ): String? {
        val cmd = SignPackCommand(ActorId(actor), PackRef("PACK-SCOPE-2026.3"), "KEY-REG-01", engine?.let(::MinEngineVersion), false, request)
        val state = sign.load(cmd)
        val unmet = if (sign.priorResult(cmd, state) == null) sign.unmetPrecondition(cmd, state) else null
        if (unmet == null && sign.priorResult(cmd, state) == null) sign.perform(cmd, state)
        return unmet
    }

    private fun publishAs(actor: String): String? {
        val cmd = PublishPackCommand(ActorId(actor), PackRef("PACK-SCOPE-2026.3"), request)
        val state = publish.load(cmd)
        val unmet = publish.unmetPrecondition(cmd, state)
        if (unmet == null) publish.perform(cmd, state)
        return unmet
    }

    private val ref = PackRef("PACK-SCOPE-2026.3")

    private fun passed(hash: String = sha(content)) = evidence.add(StagingEvidence(ref, StagingResult.PASSED, ContentHash(hash)))

    @Test
    fun `INV-AC-2591 a person with both roles who edited the version cannot sign it and publishPack is refused as not signed`() {
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
}
