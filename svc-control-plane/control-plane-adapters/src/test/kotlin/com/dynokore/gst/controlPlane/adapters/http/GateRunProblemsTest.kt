package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.platform.serviceruntime.http.Problems
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** The envelope of the three recordGateRun refusals of the catalogue (INV-Q-14041 A), compared by equality. */
class GateRunProblemsTest {
    private val json = JsonMapper.builder().build()

    private fun body(code: String): Map<*, *> =
        json.readValue(GateRunProblems.render(code, "/gate", "m", "corr-1").body, Map::class.java)

    private fun check(
        code: String,
        status: Int,
    ) {
        val rendered = GateRunProblems.render(code, "/gate", "m", "corr-1")
        rendered.status shouldBe status
        val body = body(code)
        body["status"] shouldBe status
        body["code"] shouldBe code
        body["type"] shouldBe Problems.typeOf(code)
        body["severity"] shouldBe "BLOCK"
        body["disposition"] shouldBe "TERMINAL"
        body["remediationOwner"] shouldBe "Platform"
        body["correlationId"] shouldBe "corr-1"
        (body["errors"] as List<*>).single() shouldBe mapOf("code" to code, "field" to "/gate", "message" to "m")
    }

    @Test
    fun `CPL-004 renders 409 with its own code`() = check("CPL-004", 409)

    @Test
    fun `CPL-005 renders 409 with its own code`() = check("CPL-005", 409)

    @Test
    fun `CPL-006 renders 422`() = check("CPL-006", 422)

    @Test
    fun `look-alikes differ - CPL-004 is not CPL-005, and CPL-006 is neither 409 nor 400`() {
        body("CPL-004")["code"] shouldNotBe body("CPL-005")["code"]
        body("CPL-004")["type"] shouldNotBe body("CPL-005")["type"]
        GateRunProblems.statusOf("CPL-004") shouldBe GateRunProblems.statusOf("CPL-005")
        GateRunProblems.statusOf("CPL-006") shouldNotBe 409
        GateRunProblems.statusOf("CPL-006") shouldNotBe 400
    }
}
