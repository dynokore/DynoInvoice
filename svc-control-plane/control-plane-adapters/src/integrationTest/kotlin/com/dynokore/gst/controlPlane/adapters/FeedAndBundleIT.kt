package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.feed.FeedEntry
import com.dynokore.gst.controlPlane.adapters.feed.FeedIndexInvalid
import com.dynokore.gst.controlPlane.adapters.feed.PackFeedIndex
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.platform.rulepackkernel.BouncyCastlePackVerifier
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackRejectionReason
import com.dynokore.platform.rulepackkernel.PackVerificationOutcome
import com.dynokore.platform.rulepackkernel.TrustAnchor
import com.dynokore.platform.rulepackkernel.TrustAnchors
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/** The bundle, the feed index and the release history over the real tables (INV-REQ-1077, INV-REQ-1079). */
class FeedAndBundleIT {
    private val json = JsonMapper.builder().build()
    private val verifier = BouncyCastlePackVerifier()

    private fun sha256(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun anchors(flow: PackReleaseFlow) =
        TrustAnchors(listOf(TrustAnchor("platform", flow.signing.certificateDer)))

    private fun entriesOf(flow: PackReleaseFlow) =
        json
            .readTree(checkNotNull(flow.reader.index()))
            .get("entries")
            .filter { it.get("packRef").asString().startsWith("PACK-${flow.type.value}-") }

    @Test
    fun `INV-AC-2071 a published bundle carries every required field and a signature that verifies`() {
        val flow = PackReleaseFlow()
        val pack = flow.release("2026.1")
        val bundle = checkNotNull(flow.reader.bundle(pack.ref))
        val manifest = json.readTree(bundle.manifest)
        manifest.get("packType").asString() shouldBe flow.type.value
        manifest.get("version").asString() shouldBe "2026.1"
        manifest.get("effectiveFrom").asString() shouldBe "2026-11-01"
        manifest.get("minEngineVersion").asString() shouldBe "2026.10.0"
        manifest.get("sourceCitation")[0].asString() shouldBe "IRAS e-Tax Guide 2026-08"
        json.readTree(bundle.checksums).get("contents").asString() shouldBe sha256(bundle.contents)
        val outcome = verifier.verify(bundle, anchors(flow))
        (outcome is PackVerificationOutcome.Verified) shouldBe true
    }

    @Test
    fun `INV-AC-2071 a bundle whose contents changed, or whose signer is not the platform key, is refused`() {
        val flow = PackReleaseFlow()
        val bundle = checkNotNull(flow.reader.bundle(flow.release("2026.1").ref))
        val tampered = PackArtefact(bundle.manifest, bundle.checksums, bundle.signature, bundle.contents + 1)
        val changed = verifier.verify(tampered, anchors(flow)) as PackVerificationOutcome.Rejected
        changed.reason shouldBe PackRejectionReason.CHECKSUM_MISMATCH
        val stranger =
            TrustAnchors(
                listOf(
                    TrustAnchor(
                        "other",
                        com.dynokore.gst.controlPlane.adapters.testing.SigningFixtures
                            .create()
                            .certificateDer,
                    ),
                ),
            )
        val untrusted = verifier.verify(bundle, stranger) as PackVerificationOutcome.Rejected
        untrusted.reason shouldBe PackRejectionReason.UNTRUSTED_SIGNER
    }

    @Test
    fun `INV-AC-2074 the feed entry and the offline bundle of one pack version agree on version and hash`() {
        val flow = PackReleaseFlow()
        val pack = flow.release("2026.1")
        val bundle = checkNotNull(flow.reader.bundle(pack.ref))
        val entry = entriesOf(flow).single()
        entry.get("packRef").asString() shouldBe pack.ref.value
        entry.get("packType").asString() shouldBe pack.type.value
        entry.get("version").asString() shouldBe "2026.1"
        entry.get("effectiveFrom").asString() shouldBe "2026-11-01"
        entry.get("minEngineVersion").asString() shouldBe "2026.10.0"
        entry.get("packContentHash").asString() shouldBe sha256(bundle.manifest, bundle.checksums, bundle.contents)
        // the staging gate hashed the same content: the release history holds the same hash
        flow.reader
            .history()
            .single { it.packRef == pack.ref }
            .packContentHash shouldBe
            entry.get("packContentHash").asString()
        // the offline file is the stored members: reading it twice gives the same bytes
        val again = checkNotNull(flow.reader.bundle(pack.ref))
        again.manifest.contentEquals(bundle.manifest) shouldBe true
        again.contents.contentEquals(bundle.contents) shouldBe true
        again.signature.contentEquals(bundle.signature) shouldBe true
    }

    @Test
    fun `INV-AC-2594 a superseding version leaves the earlier one in the feed, the bundles and the history`() {
        val flow = PackReleaseFlow()
        val first = flow.release("2026.1")
        val second = flow.release("2026.2", """{"rules":[{"code":"SR","rate":10}]}""")
        val entries = entriesOf(flow)
        entries.map { it.get("packRef").asString() } shouldBe listOf(first.ref.value, second.ref.value)
        entries[0].has("supersedes") shouldBe false
        entries[1].get("supersedes").asString() shouldBe first.ref.value
        (flow.reader.bundle(first.ref) != null && flow.reader.bundle(second.ref) != null) shouldBe true
        flow.reader
            .history()
            .filter { it.packRef in setOf(first.ref, second.ref) }
            .map { it.packRef } shouldBe
            listOf(first.ref, second.ref)
        checkNotNull(flow.packs.find(first.ref)).status shouldBe PackStatus.PUBLISHED
    }

    @Test
    fun `INV-AC-2594 the published control-plane contract offers no operation that unpublishes or withdraws a pack`() {
        val spec = File(System.getProperty("gst.contracts.dir"), "openapi.yaml").readText()
        val paths = (Yaml().load<Map<String, Any?>>(spec)["paths"] as Map<*, *>).values
        val operationIds =
            paths.flatMap { (it as Map<*, *>).values }.mapNotNull { (it as? Map<*, *>)?.get("operationId") as? String }
        operationIds.isNotEmpty() shouldBe true
        operationIds.filter { id ->
            listOf("unpublish", "withdraw", "retract").any { id.lowercase().contains(it) }
        } shouldBe
            emptyList<String>()
    }

    @Test
    fun `an index entry with an uppercase hash is refused and the previous index stays served`() {
        val flow = PackReleaseFlow()
        flow.release("2026.1")
        val before = checkNotNull(flow.reader.index())
        val error =
            shouldThrow<FeedIndexInvalid> {
                flow.index.write(
                    listOf(FeedEntry("PACK-X-1", "X", "1", "2026-11-01", "2026.10.0", "A".repeat(64), null)),
                )
            }
        error.message!! shouldContain "does not validate"
        flow.reader.index() shouldBe before
    }

    @Test
    fun `the index schema on the classpath is the file of the pinned platform-contracts release`() {
        val pinned = File(System.getProperty("gst.contracts.dir"), "schemas/pack-feed-index.schema.json").readText()
        PackFeedIndex.schemaFromClasspath() shouldBe pinned
    }

    @Test
    fun `a schema copy with one property renamed refuses a valid entry, so the pinned file is what the test needs`() {
        val flow = PackReleaseFlow()
        val pinned = PackFeedIndex.schemaFromClasspath()
        val renamed = pinned.replace("packContentHash", "packContentDigest")
        (renamed == pinned) shouldBe false
        val copy = PackFeedIndex(PostgresDb.dsl(), java.time.Clock.systemUTC(), renamed)
        shouldThrow<FeedIndexInvalid> {
            copy.write(listOf(FeedEntry("PACK-X-1", "X", "1", "2026-11-01", "2026.10.0", "a".repeat(64), null)))
        }
        flow.index.current() // the pinned schema's index is untouched by the copy
    }
}
