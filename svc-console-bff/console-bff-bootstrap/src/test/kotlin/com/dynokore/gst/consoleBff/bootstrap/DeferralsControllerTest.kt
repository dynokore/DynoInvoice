package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.deferral.DeferAuthority
import com.dynokore.gst.consoleBff.application.deferral.DeferTarget
import com.dynokore.gst.consoleBff.application.deferral.DeferralStore
import com.dynokore.gst.consoleBff.application.deferral.DeferralTargets
import com.dynokore.gst.consoleBff.application.deferral.UndeferTarget
import com.dynokore.gst.consoleBff.domain.deferral.Deferral
import com.dynokore.gst.consoleBff.domain.deferral.DeferralState
import com.dynokore.gst.consoleBff.domain.deferral.DeferralTarget
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-21164: deferDocument and undeferDocument over the real use cases with in-memory ports. */
class DeferralsControllerTest {
    private val json = JsonMapper.builder().build()
    private val docId = UUID(1, 1).toString()

    private class MemoryStore : DeferralStore {
        val rows = mutableListOf<Deferral>()

        override fun deferIfAbsent(candidate: Deferral): Deferral =
            findDeferred(candidate.entityRef, candidate.target) ?: candidate.also { rows += it }

        override fun undo(
            entityRef: String,
            deferralId: UUID,
        ): Deferral? {
            val i =
                rows.indexOfFirst {
                    it.deferralId == deferralId && it.entityRef == entityRef &&
                        it.state == DeferralState.DEFERRED
                }
            if (i < 0) return null
            return rows[i].copy(state = DeferralState.UNDONE).also { rows[i] = it }
        }

        override fun findDeferred(
            entityRef: String,
            target: DeferralTarget,
        ) = rows.firstOrNull { it.entityRef == entityRef && it.target == target && it.state == DeferralState.DEFERRED }
    }

    private val store = MemoryStore()

    private fun mvc(allow: Boolean = true): MockMvc {
        val authority = DeferAuthority { _, _, _, _ -> allow }
        val targets =
            object : DeferralTargets {
                override fun openDocument(
                    entityRef: String,
                    docUuid: String,
                ) = entityRef == "E1" && docUuid == docId

                override fun openGroup(
                    entityRef: String,
                    groupKey: String,
                ) = false
            }
        val clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC)
        val ids = sequenceOf(UUID(2, 1), UUID(2, 2)).iterator()
        val defer = DeferTarget(authority, targets, store, clock) { ids.next() }
        return MockMvcBuilders
            .standaloneSetup(DeferralsController(defer, UndeferTarget(authority, store)))
            .setControllerAdvice(DeferralsProblemAdvice())
            .build()
    }

    private fun defer(
        mvc: MockMvc,
        kind: String = "DOCUMENT",
        ref: String = docId,
        authenticated: Boolean = true,
    ): MvcResult {
        val request =
            post("/bff/entities/E1/deferrals")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.ALL)
                .content("""{"target":{"kind":"$kind","ref":"$ref"}}""")
        if (authenticated) {
            request.requestAttr(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, "U1")
            request.requestAttr(ConsoleSessionFilter.TENANT_ATTRIBUTE, "T1")
        }
        return mvc.perform(request).andReturn()
    }

    private fun undo(
        mvc: MockMvc,
        id: String,
    ): MvcResult =
        mvc
            .perform(
                post("/bff/entities/E1/deferrals/$id/undo")
                    .accept(MediaType.ALL)
                    .requestAttr(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, "U1")
                    .requestAttr(ConsoleSessionFilter.TENANT_ATTRIBUTE, "T1"),
            ).andReturn()

    private fun body(result: MvcResult): JsonNode = json.readTree(result.response.contentAsString)

    private fun problem(
        result: MvcResult,
        code: ClosedErrorCode,
    ) {
        result.response.status shouldBe code.httpStatus
        result.response.contentType shouldContain Problems.CONTENT_TYPE
        body(result)["code"].asString() shouldBe code.code
    }

    @Test
    fun `INV-AC-21164 the first defer answers the stored Deferral and the repeat the same body`() {
        val mvc = mvc()
        val first = defer(mvc)
        first.response.status shouldBe 200
        first.response.contentType shouldContain "application/json"
        val b = body(first)
        b["deferralId"].asString() shouldBe UUID(2, 1).toString()
        b["entityRef"].asString() shouldBe "E1"
        b["target"]["kind"].asString() shouldBe "DOCUMENT"
        b["target"]["ref"].asString() shouldBe docId
        b["state"].asString() shouldBe "DEFERRED"
        b["deferredBy"].asString() shouldBe "U1"
        b.has("deferredAt") shouldBe true
        val again = defer(mvc)
        again.response.status shouldBe 200
        again.response.contentAsString shouldBe first.response.contentAsString
    }

    @Test
    fun `INV-AC-21164 a missing target is answered like notFound SRT-005`() {
        val result = defer(mvc(), ref = UUID(9, 9).toString())
        problem(result, ClosedErrorCode.NOT_FOUND)
        body(result)["code"].asString() shouldBe "SRT-005"
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-21164 a denied call is answered like accessDenied SRT-004`() {
        problem(defer(mvc(allow = false)), ClosedErrorCode.ACCESS_DENIED)
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-21164 a request without the principal is answered like unauthenticated SRT-003`() {
        problem(defer(mvc(), authenticated = false), ClosedErrorCode.UNAUTHENTICATED)
    }

    @Test
    fun `INV-AC-21164 target kind ROW is a contract violation SRT-001 and nothing is stored`() {
        problem(defer(mvc(), kind = "ROW"), ClosedErrorCode.CONTRACT_VIOLATION)
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-21164 undo answers UNDONE, the repeat and an unknown id answer 404 SRT-005`() {
        val mvc = mvc()
        val id = body(defer(mvc))["deferralId"].asString()
        val done = undo(mvc, id)
        done.response.status shouldBe 200
        body(done)["state"].asString() shouldBe "UNDONE"
        problem(undo(mvc, id), ClosedErrorCode.NOT_FOUND)
        problem(undo(mvc, UUID(7, 7).toString()), ClosedErrorCode.NOT_FOUND)
    }

    @Test
    fun `INV-AC-21164 a denied undo is answered like accessDenied`() {
        problem(undo(mvc(allow = false), UUID(7, 7).toString()), ClosedErrorCode.ACCESS_DENIED)
    }
}
