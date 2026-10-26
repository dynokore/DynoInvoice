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
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate
import java.util.UUID

/**
 * The seed calendar pack authored through the author step of authorRegulatoryPack over the real tables (INV-T-5056):
 * the draft is accepted with packType calendar. The pack is neither signed nor published: author and signer are
 * separate people (INV-REQ-2590). A scratch copy with other thresholds and margin is accepted as it is, so nothing in
 * code overrides the file (INV-REQ-1350, INV-Q-10904, INV-Q-10906).
 */
class CalendarSeedAuthoringIT {
    private val mapper = JsonMapper.builder().build()
    private val flow = PackReleaseFlow()
    private val request = RequestContext("corr-seed", "cause-seed")
    private val author = AuthorRegulatoryPack(flow.packs, OperatorRoles { actor, role -> actor == "alice" && role == OperatorRole.AUTHOR })

    private fun seed(): String =
        checkNotNull(CalendarSeedAuthoringIT::class.java.classLoader.getResource("packs/calendar/sg-gst/contents.json")).readText()

    private fun authored(contents: String): String {
        val command =
            AuthorRegulatoryPackCommand(
                ActorId("alice"),
                PackType("calendar"),
                PackVersion("seed-" + UUID.randomUUID().toString().take(8)),
                listOf("INV-AC-113", "INV-AC-153"),
                LocalDate.parse("2026-11-01"),
                PackContents(contents),
                request,
            )
        check(author.permits("alice", command)) { "alice may not author" }
        val draft = author.perform(command, author.load(command))
        draft.status shouldBe PackStatus.DRAFT
        draft.type shouldBe PackType("calendar")
        return checkNotNull(flow.packs.find(command.ref)).contents!!.json
    }

    private fun thresholds(
        json: String,
        key: String,
    ) = mapper.readTree(json).path("alertThresholds").path(key).iterator().asSequence().map { it.asInt() }.toList()

    @Test
    fun `INV-AC-1727 the seed calendar pack is accepted as a draft of packType calendar and holds the shipped defaults`() {
        val stored = mapper.readTree(authored(seed()))
        stored.path("safetyMargin").path("value").asInt() shouldBe 3
        stored.path("safetyMargin").path("unit").asString() shouldBe "DAYS"
        thresholds(stored.toString(), "QUARTERLY") shouldBe listOf(10)
        thresholds(stored.toString(), "MONTHLY") shouldBe listOf(5)
        stored.has("pinnedRule") shouldBe false
    }

    @Test
    fun `INV-AC-1727 value probe a scratch copy with 7 DAYS and thresholds 9 and 4 is stored as it is`() {
        val scratch = mapper.readTree(seed()) as ObjectNode
        (scratch.path("safetyMargin") as ObjectNode).put("value", 7)
        (scratch.path("alertThresholds").get("QUARTERLY") as ArrayNode).removeAll().add(9)
        (scratch.path("alertThresholds").get("MONTHLY") as ArrayNode).removeAll().add(4)
        val stored = authored(scratch.toString())
        mapper.readTree(stored).path("safetyMargin").path("value").asInt() shouldBe 7
        mapper.readTree(stored).path("safetyMargin").path("unit").asString() shouldBe "DAYS"
        thresholds(stored, "QUARTERLY") shouldBe listOf(9)
        thresholds(stored, "MONTHLY") shouldBe listOf(4)
    }
}
