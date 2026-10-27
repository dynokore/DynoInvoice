package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPack
import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPackCommand
import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.PackContents
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * The seed code catalogue pack authored through the author step over the real tables, with the PackReleaseFlow
 * helper of INV-T-4984 (INV-AC-2431, INV-AC-10207): the draft is accepted with packType code-catalogue and the
 * effectiveFrom of release.json, passed explicitly (the helper default 2026-11-01 is a test value). The pack is
 * neither signed nor published: author and signer are separate people (INV-REQ-2590).
 */
class CodeCatalogueSeedAuthoringIT {
    private val mapper = JsonMapper.builder().build()
    private val flow = PackReleaseFlow()
    private val request = RequestContext("corr-seed", "cause-seed")
    private val roles = OperatorRoles { actor, role -> actor == "alice" && role == OperatorRole.AUTHOR }
    private val author = AuthorRegulatoryPack(flow.packs, roles)

    private fun resource(name: String): String =
        checkNotNull(
            CodeCatalogueSeedAuthoringIT::class.java.classLoader.getResource("packs/code-catalogue/sg-gst/$name"),
        ).readText()

    @Test
    fun `INV-AC-2431 the seed code catalogue is a draft of packType code-catalogue with the release date`() {
        val effectiveFrom = LocalDate.parse(mapper.readTree(resource("release.json")).path("effectiveFrom").asString())
        val command =
            AuthorRegulatoryPackCommand(
                ActorId("alice"),
                PackType("code-catalogue"),
                PackVersion("seed-" + UUID.randomUUID().toString().take(8)),
                listOf("INV-REQ-2429", "INV-REQ-418"),
                effectiveFrom,
                PackContents(resource("contents.json")),
                request,
            )
        check(author.permits("alice", command)) { "alice may not author" }
        val draft = author.perform(command, author.load(command))
        draft.status shouldBe PackStatus.DRAFT
        draft.type shouldBe PackType("code-catalogue")
        draft.effectiveFrom shouldBe LocalDate.parse("2024-01-01")
        val stored = checkNotNull(flow.packs.find(command.ref))
        stored.effectiveFrom shouldBe effectiveFrom
        val codes = mapper.readTree(stored.contents!!.json)
        codes.path("complete").asBoolean() shouldBe true
        codes.path("codes").size() shouldBe 35
    }

    @Test
    fun `INV-AC-10207 the seed catalogue is stored whole, the NA submission rate included`() {
        val command =
            AuthorRegulatoryPackCommand(
                ActorId("alice"),
                PackType("code-catalogue"),
                PackVersion("seed-" + UUID.randomUUID().toString().take(8)),
                listOf("INV-REQ-2429"),
                LocalDate.parse("2024-01-01"),
                PackContents(resource("contents.json")),
                request,
            )
        author.perform(command, author.load(command))
        val stored = mapper.readTree(checkNotNull(flow.packs.find(command.ref)).contents!!.json)
        stored
            .path("codes")
            .first { it.path("code").asString() == "NA" }
            .path("submissionRate")
            .asString() shouldBe "0"
    }
}
