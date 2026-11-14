package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.exceptions.EntityScope
import com.dynokore.gst.consoleBff.application.exceptions.ExceptionQueue
import com.dynokore.gst.consoleBff.application.exceptions.ListExceptions
import com.dynokore.gst.consoleBff.application.exceptions.QueuedException
import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionSortKey
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** INV-AC-21107: GET /v1/exceptions over the real ListExceptions with in-memory ports. */
class ExceptionsControllerTest {
    private val json = JsonMapper.builder().build()

    private fun row(
        n: Int,
        entity: String = "E-001",
        owner: String = "",
        disposition: String = "",
        period: String = "",
    ) = ExceptionRow(
        tenantId = "T1",
        exceptionId = UUID(0, n.toLong()),
        entityRef = entity,
        docUuid = UUID(1, n.toLong()).toString(),
        code = "GST-001",
        remediationOwner = owner,
        disposition = disposition,
        state = ItemState.OPEN,
        raisedAt = Instant.parse("2026-11-01T00:00:00Z").plusSeconds(n.toLong()),
        periodRef = period,
        docsequence = n.toLong(),
    )

    private fun mvc(
        queued: List<QueuedException>,
        scope: Set<String> = setOf("E-001"),
        maxLimit: Int = 500,
    ): MockMvc {
        val queue =
            object : ExceptionQueue {
                override fun page(
                    tenantId: String,
                    entityRefs: Set<String>,
                    state: ItemState?,
                    deferred: Boolean,
                    after: ExceptionSortKey?,
                    limit: Int,
                ) = if (deferred) {
                    emptyList()
                } else {
                    queued
                        .filter { it.row.entityRef in entityRefs && (state == null || it.row.state == state) }
                        .take(limit)
                }
            }
        val entityScope =
            object : EntityScope {
                override fun entitiesOf(
                    tenantId: String,
                    principalId: String,
                ) = scope
            }
        return MockMvcBuilders
            .standaloneSetup(ExceptionsController(ListExceptions(entityScope, queue, maxLimit)))
            .setControllerAdvice(ExceptionsProblemAdvice())
            .build()
    }

    private val two =
        listOf(
            QueuedException(row(1), LocalDate.parse("2026-11-21")),
            QueuedException(row(2, period = "2026-Q3"), null),
        )

    private fun call(
        mvc: MockMvc,
        query: String = "",
        authenticated: Boolean = true,
    ): MvcResult {
        val request = get("/v1/exceptions$query").accept(MediaType.ALL)
        if (authenticated) {
            request.requestAttr(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, "P1")
            request.requestAttr(ConsoleSessionFilter.TENANT_ATTRIBUTE, "T1")
        }
        return mvc.perform(request).andReturn()
    }

    private fun body(result: MvcResult): JsonNode = json.readTree(result.response.contentAsString)

    @Test
    fun `INV-AC-21107 the page carries the stored members only`() {
        val result = call(mvc(two))
        result.response.status shouldBe 200
        result.response.contentType shouldContain "application/json"
        val items = body(result)["items"]
        items.size() shouldBe 2
        val first = items[0]
        first["exceptionId"].asString() shouldBe UUID(0, 1).toString()
        first["remediationOwner"].asString() shouldBe ""
        first["disposition"].asString() shouldBe ""
        first["state"].asString() shouldBe "OPEN"
        first["dueDate"].asString() shouldBe "2026-11-21"
        first.has("periodRef") shouldBe false
        val second = items[1]
        second.has("dueDate") shouldBe false
        second["periodRef"].asString() shouldBe "2026-Q3"
        val absent =
            listOf(
                "docVersion",
                "documentNumber",
                "counterpartyName",
                "transactionDate",
                "taxableAmount",
                "severity",
                "inputKey",
                "deferralId",
                "rank",
            )
        for (item in listOf(first, second)) {
            for (member in absent) item.has(member) shouldBe false
        }
        body(result).has("nextCursor") shouldBe false
        body(result).has("deadlinePeriods") shouldBe false
    }

    @Test
    fun `INV-AC-21107 disposition is the stored text, the empty string and an unlisted value included`() {
        val stored = listOf("", "MANUAL", "FIX")
        val rows = stored.mapIndexed { i, d -> QueuedException(row(i + 1, disposition = d), null) }
        val items = body(call(mvc(rows)))["items"]
        (0 until items.size()).map { items[it]["disposition"].asString() } shouldBe stored
    }

    @Test
    fun `INV-AC-21107 nextCursor is present only when the use case returned one`() {
        val rows = (1..3).map { QueuedException(row(it), null) }
        val paged = body(call(mvc(rows), "?limit=2"))
        paged["items"].size() shouldBe 2
        paged.has("nextCursor") shouldBe true
        body(call(mvc(rows), "?limit=3")).has("nextCursor") shouldBe false
    }

    @Test
    fun `INV-AC-21107 deferred true lists none and an unknown state is a contract violation`() {
        body(call(mvc(two), "?deferred=true"))["items"].size() shouldBe 0
        val bad = call(mvc(two), "?state=BOGUS")
        bad.response.status shouldBe ClosedErrorCode.CONTRACT_VIOLATION.httpStatus
    }

    @Test
    fun `INV-AC-21107 an entity outside the scope is answered like accessDenied`() {
        val result = call(mvc(two), "?entityRef=E-009")
        result.response.status shouldBe ClosedErrorCode.ACCESS_DENIED.httpStatus
        result.response.contentType shouldContain Problems.CONTENT_TYPE
        body(result)["code"].asString() shouldBe ClosedErrorCode.ACCESS_DENIED.code
    }

    @Test
    fun `INV-AC-21107 a malformed cursor and a limit out of bounds are contract violations`() {
        for (query in listOf("?cursor=x", "?limit=501", "?limit=0")) {
            val result = call(mvc(two), query)
            result.response.status shouldBe ClosedErrorCode.CONTRACT_VIOLATION.httpStatus
            result.response.contentType shouldContain Problems.CONTENT_TYPE
            body(result)["code"].asString() shouldBe ClosedErrorCode.CONTRACT_VIOLATION.code
        }
    }

    @Test
    fun `INV-AC-21107 a request without the principal is answered like unauthenticated`() {
        val result = call(mvc(two), authenticated = false)
        result.response.status shouldBe ClosedErrorCode.UNAUTHENTICATED.httpStatus
        result.response.contentType shouldContain Problems.CONTENT_TYPE
        body(result)["code"].asString() shouldBe ClosedErrorCode.UNAUTHENTICATED.code
    }
}
