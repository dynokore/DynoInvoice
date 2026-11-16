package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.exceptions.ListExceptions
import com.dynokore.gst.consoleBff.application.override.OverridableRow
import com.dynokore.gst.consoleBff.application.override.OverrideAuthority
import com.dynokore.gst.consoleBff.application.override.OverrideException
import com.dynokore.gst.consoleBff.application.override.OverrideTargets
import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.gst.consoleBff.application.override.OwnerReply
import com.dynokore.gst.consoleBff.application.override.PayloadOverrides
import com.dynokore.gst.consoleBff.application.override.Release
import com.dynokore.gst.consoleBff.application.override.SubmissionOverrides
import com.dynokore.platform.serviceruntime.http.Problems
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.objenesis.ObjenesisStd
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** INV-AC-21206: ExceptionsController.overrideException over the real use case with fake ports. */
class OverridesControllerTest {
    private val json = JsonMapper.builder().build()
    private val id = UUID(1, 1).toString()
    private val at = Instant.parse("2026-11-16T01:02:03Z")
    private val ownerRefusal =
        OwnerRefusal(409, "PAY-017", "Owner title", "BLOCK", "MANUAL", "PAYLOAD_OWNER", "owner detail text")

    /** Counts what reaches the ports; the controller must never reach a write of the exception row (no such port). */
    private class Fakes(
        var allow: Boolean = true,
        var target: OverridableRow? =
            OverridableRow(UUID(1, 1).toString(), "E1", UUID(5, 5).toString(), "", "PAY-006", "OPEN"),
        var override: OwnerReply<Release> = OwnerReply.Ok(Release.Released(Instant.parse("2026-11-16T01:02:03Z"))),
    ) {
        var finds = 0
    }

    private fun mvc(fakes: Fakes): MockMvc {
        val targets =
            OverrideTargets {
                fakes.finds++
                fakes.target
            }
        val authority = OverrideAuthority { _, _, _ -> fakes.allow }
        val payload =
            object : PayloadOverrides {
                override fun latestVersion(docUuid: String): OwnerReply<Long> = OwnerReply.Ok(3)

                override fun override(
                    docUuid: String,
                    version: Long,
                    code: String,
                    reason: String,
                ) = fakes.override
            }
        val submission =
            object : SubmissionOverrides {
                override fun latestCheck(
                    entityRef: String,
                    periodRef: String,
                ): OwnerReply<String> = OwnerReply.Ok("c")

                override fun override(
                    entityRef: String,
                    periodRef: String,
                    checkId: String,
                    reason: String,
                ) = fakes.override
            }
        // The list use case is never reached by overrideException.
        val queue = ObjenesisStd(false).newInstance(ListExceptions::class.java)
        return MockMvcBuilders
            .standaloneSetup(
                ExceptionsController(
                    queue,
                    OverrideException(targets, authority, payload, submission),
                    OverridesProblemMapper(),
                ),
            ).setControllerAdvice(ExceptionsProblemAdvice())
            .build()
    }

    private fun call(
        mvc: MockMvc,
        content: String = """{"reason":"checked with the customer"}""",
        authenticated: Boolean = true,
    ): MvcResult {
        val request =
            post("/bff/exceptions/$id/override")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.ALL)
                .content(content)
        if (authenticated) {
            request.requestAttr(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, "U1")
            request.requestAttr(ConsoleSessionFilter.TENANT_ATTRIBUTE, "T1")
        }
        return mvc.perform(request).andReturn()
    }

    private fun body(result: MvcResult): JsonNode = json.readTree(result.response.contentAsString)

    private fun problem(
        result: MvcResult,
        status: Int,
        code: String,
    ) {
        result.response.status shouldBe status
        result.response.contentType shouldContain Problems.CONTENT_TYPE
        body(result)["code"].asString() shouldBe code
    }

    @Test
    fun `INV-AC-21206 Overridden gives 200 OverrideResult with id, OVERRIDDEN, the reason and the time`() {
        val result = call(mvc(Fakes()))
        result.response.status shouldBe 200
        val b = body(result)
        b["exceptionId"].asString() shouldBe id
        b["state"].asString() shouldBe "OVERRIDDEN"
        b["reason"].asString() shouldBe "checked with the customer"
        Instant.parse(b["overriddenAt"].asString()) shouldBe at
    }

    @Test
    fun `INV-AC-21206 PendingApproval gives 202 PendingOverride with id, reason, time and PENDING`() {
        val result = call(mvc(Fakes(override = OwnerReply.Ok(Release.Pending(at)))))
        result.response.status shouldBe 202
        val b = body(result)
        b["exceptionId"].asString() shouldBe id
        b["reason"].asString() shouldBe "checked with the customer"
        Instant.parse(b["requestedAt"].asString()) shouldBe at
        b["status"].asString() shouldBe "PENDING"
        b.has("state") shouldBe false
    }

    @Test
    fun `INV-AC-21206 Relayed gives the owner status and the relay envelope with the correlation id of this request`() {
        val result = call(mvc(Fakes(override = OwnerReply.Refused(ownerRefusal))))
        result.response.status shouldBe 409
        result.response.contentType shouldContain Problems.CONTENT_TYPE
        val b = body(result)
        b["code"].asString() shouldBe "PAY-017"
        b["title"].asString() shouldBe "Owner title"
        b["severity"].asString() shouldBe "BLOCK"
        b["disposition"].asString() shouldBe "MANUAL"
        b["remediationOwner"].asString() shouldBe "PAYLOAD_OWNER"
        b["detail"].asString() shouldBe "owner detail text"
        b["correlationId"].asString() shouldContain "http:"
        result.response.contentAsString shouldBe
            Problems
                .renderRelayed(
                    com.dynokore.platform.serviceruntime.http.RelayedRefusal(
                        409,
                        "PAY-017",
                        "Owner title",
                        "BLOCK",
                        "MANUAL",
                        "PAYLOAD_OWNER",
                        "owner detail text",
                    ),
                    b["correlationId"].asString(),
                ).body
    }

    @Test
    fun `INV-AC-21206 NotFound gives 404 SRT-005`() {
        problem(call(mvc(Fakes(target = null))), 404, "SRT-005")
    }

    @Test
    fun `INV-AC-21206 Forbidden gives 403 SRT-004`() {
        problem(call(mvc(Fakes(allow = false))), 403, "SRT-004")
    }

    @Test
    fun `INV-AC-21206 Unavailable gives 503 SRT-013 with no member of the owner's text`() {
        val owner = OwnerReply.Unavailable("owner reason: connection refused to payload-svc")
        val result = call(mvc(Fakes(override = owner)))
        problem(result, 503, "SRT-013")
        result.response.contentAsString shouldNotContain "owner reason"
        result.response.contentAsString shouldNotContain "payload-svc"
    }

    @Test
    fun `INV-AC-21206 a body without reason gives 400 SRT-001 and the use case is not called`() {
        val fakes = Fakes()
        val mvc = mvc(fakes)
        problem(call(mvc, content = "{}"), 400, "SRT-001")
        problem(call(mvc, content = """{"reason":"   "}"""), 400, "SRT-001")
        problem(call(mvc, content = """{"reason":"${"x".repeat(501)}"}"""), 400, "SRT-001")
        fakes.finds shouldBe 0
    }

    @Test
    fun `INV-AC-21206 a request without a session gives 401 SRT-003 and the use case is not called`() {
        val fakes = Fakes()
        problem(call(mvc(fakes), authenticated = false), 401, "SRT-003")
        fakes.finds shouldBe 0
    }

    @Test
    fun `INV-AC-21206 every case of this class carries the criterion token as a whole word`() {
        val names =
            OverridesControllerTest::class.java.declaredMethods
                .filter { it.isAnnotationPresent(Test::class.java) }
                .map { it.name }
        names.size shouldBe 9
        names.filterNot { it.startsWith("INV-AC-21206 ") } shouldBe emptyList()
    }
}
