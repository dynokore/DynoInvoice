package com.dynokore.gst.controlPlane.adapters.corpus

import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.PackRepository
import com.dynokore.gst.controlPlane.application.pack.PackSigningKeys
import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.pack.SignPack
import com.dynokore.gst.controlPlane.application.pack.SignPackCommand
import com.dynokore.gst.controlPlane.application.release.ConformanceCorpus
import com.dynokore.gst.controlPlane.application.release.PackReleaseRecords
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.MinEngineVersion
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.release.CorpusSection
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.GoldenDocument
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord
import com.dynokore.platform.crypto.SignatureAlgorithm
import com.dynokore.platform.crypto.SigningKey
import com.dynokore.platform.rulesevaluation.EvaluationRefusal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * signPack against the real in-process corpus (INV-AC-2661, INV-AC-2674, INV-AC-9790): the criteria that the fakes of
 * the application tests prove now run against the real binding.
 */
class SignPackWithCorpusTest {
    @TempDir
    lateinit var tmp: Path

    private val tree by lazy { CorpusTree(tmp) }
    private val clock = Clock.fixed(Instant.parse("2026-10-12T08:30:00Z"), ZoneOffset.UTC)
    private val store = LinkedHashMap<PackRef, RegulatoryPack>()
    private val recorded = LinkedHashMap<PackRef, ReleaseRecord>()
    private val roles = OperatorRoles { actor, role -> role == OperatorRole.APPROVER && actor == "bob" }
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
    private val records =
        object : PackReleaseRecords {
            override fun find(ref: PackRef) = recorded[ref]

            override fun save(
                ref: PackRef,
                record: ReleaseRecord,
            ) {
                recorded[ref] = record
            }
        }
    private val keys = PackSigningKeys { if (it == "KEY-REG-01") StubKey else null }

    private object StubKey : SigningKey {
        override val algorithm = SignatureAlgorithm.ECDSA_P256_SHA256
        override val certificateDer = ByteArray(1)

        override fun sign(data: ByteArray) = data
    }

    private fun scope() = tree.text("scope-pack.json")

    private fun breaking(vararg codes: String): String =
        codes.fold(scope()) { text, code ->
            text.replace(Regex("(\"categoryCode\": \"$code\",\\s*\"lineVerdict\": )\"[A-Z_]+\""), "$1\"IN_SCOPE\"")
        }

    /** signPack as the operation runs it: preconditions, then perform when none is unmet. Returns the refusal text. */
    private fun sign(
        corpus: ConformanceCorpus,
        version: String,
        contents: String,
        exposureReducing: Boolean = false,
    ): String? {
        val pack = tree.pack("SCOPE", contents, version)
        store[pack.ref] = pack
        val operation = SignPack(repository, roles, keys, corpus, records, clock)
        val command =
            SignPackCommand(
                ActorId("bob"),
                pack.ref,
                "KEY-REG-01",
                MinEngineVersion("2026.10.0"),
                exposureReducing,
                true,
                RequestContext("corr", "cause"),
            )
        val state = operation.load(command)
        val unmet = operation.unmetPrecondition(command, state)
        if (unmet == null) operation.perform(command, state)
        return unmet
    }

    private fun ref(version: String) = PackRef("PACK-SCOPE-$version")

    private fun publishedPredecessor(
        version: String,
        contents: String,
    ) {
        val pack = tree.pack("SCOPE", contents, version)
        val signed =
            (
                pack.sign(
                    ActorId("bob"),
                    "KEY-REG-01",
                    null,
                    false,
                    clock.instant(),
                ) as com.dynokore.gst.controlPlane.domain.pack.SignOutcome.Signed
            ).pack
        store[pack.ref] = signed.published(clock.instant(), null)
    }

    @Test
    fun `INV-AC-2661 a pack that breaks a golden document is rejected and stays unsigned`() {
        val refusal = Refusal.parse(sign(tree.corpus(), "2026.3", breaking("EP"))!!)
        refusal.code shouldBe Refusal.CORPUS_FAILED
        refusal.detail.contains("sc-exempt-purchase") shouldBe true
        store.getValue(ref("2026.3")).status shouldBe PackStatus.DRAFT
        recorded shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-2661 a pack that passes every golden document is signed and the corpus run is recorded`() {
        sign(tree.corpus(), "2026.3", scope()) shouldBe null
        store.getValue(ref("2026.3")).status shouldBe PackStatus.SIGNED
        recorded
            .getValue(ref("2026.3"))
            .runs
            .single()
            .candidate shouldBe GateOutcome.PASSED
    }

    @Test
    fun `INV-AC-2674 a rule-correction pack is released despite a failure its released predecessor also has`() {
        publishedPredecessor("2026.2", breaking("EP"))
        sign(tree.corpus(), "2026.3", breaking("EP"), exposureReducing = true) shouldBe null
        val run = recorded.getValue(ref("2026.3")).runs.single()
        run.candidate shouldBe GateOutcome.FAILED
        run.baseline shouldBe GateOutcome.FAILED
        run.findings shouldBe setOf("sc-exempt-purchase")
        run.baselineFindings shouldBe setOf("sc-exempt-purchase")
    }

    @Test
    fun `INV-AC-2674 a rule-correction pack that breaks a second golden document is blocked`() {
        publishedPredecessor("2026.2", breaking("EP"))
        val refusal = Refusal.parse(sign(tree.corpus(), "2026.3", breaking("EP", "NR"), exposureReducing = true)!!)
        refusal.code shouldBe Refusal.CORPUS_FAILED
        refusal.detail.contains("introduced=[sc-non-gst-supplier-purchase]") shouldBe true
    }

    @Test
    fun `INV-AC-9790 one corpus file changed by one byte makes signPack refuse with no signature`() {
        val dir = tree.copy()
        val bytes = Files.readAllBytes(dir.resolve("cm-supply-sr.json"))
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        Files.write(dir.resolve("cm-supply-sr.json"), bytes)
        shouldThrow<IllegalStateException> { sign(tree.corpus(dir), "2026.3", scope()) }
        store.getValue(ref("2026.3")).status shouldBe PackStatus.DRAFT
        recorded shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-9790 the binding replaced by the unbound default refuses every signature`() {
        val unbound =
            object : ConformanceCorpus {
                override fun documents(): List<GoldenDocument> = emptyList()

                override fun classify(
                    pack: RegulatoryPack,
                    document: GoldenDocument,
                ): String? = null
            }
        Refusal.parse(sign(unbound, "2026.3", scope())!!).code shouldBe Refusal.CORPUS_UNAVAILABLE
        store.getValue(ref("2026.3")).status shouldBe PackStatus.DRAFT
    }

    @Test
    fun `INV-AC-9790 signPack gives no signature when the evaluation of a golden document throws`() {
        // the cancellation row removed: the evaluation of ed-cancellation-381 refuses with NoClassificationRow
        val withoutRow =
            scope().replace("\"upstreamKind\": \"cancellation\"", "\"upstreamKind\": \"cancelled\"")
        shouldThrow<EvaluationRefusal> { sign(tree.corpus(), "2026.3", withoutRow) }
        store.getValue(ref("2026.3")).status shouldBe PackStatus.DRAFT
        recorded shouldBe emptyMap()
    }

    @Test
    fun `INV-AC-9790 a pack whose evaluation gives a result is not refused`() {
        sign(tree.corpus(), "2026.3", scope()) shouldBe null
    }

    @Test
    fun `INV-AC-9790 the corpus holds a document of every section`() {
        tree
            .corpus()
            .documents()
            .map { it.section }
            .toSet() shouldBe CorpusSection.entries.toSet()
    }
}
