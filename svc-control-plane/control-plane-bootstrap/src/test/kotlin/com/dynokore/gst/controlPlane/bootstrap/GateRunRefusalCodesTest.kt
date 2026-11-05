package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import com.dynokore.gst.controlPlane.syncedge.GateRunDoubles
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * The refusals of recordGateRun over HTTP (INV-Q-14041 A), through the existing [ServletFixture]: the status and the
 * envelope `code` are read from the response. The permission binding is the stub of this task (it allows exactly
 * `release-train.certify`), the repositories are memory, and the runner applies the operation's own steps without a
 * transaction (no database is started, as for the other tests of this fixture).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GateRunRefusalCodesTest {
    private val json = JsonMapper.builder().build()
    private lateinit var context: ConfigurableApplicationContext
    private val version = "2026.10.0"

    @BeforeAll
    fun start(
        @TempDir dir: Path,
    ) {
        val properties =
            arrayOf(
                "service-runtime.workload-identity.callers[0].service=console-bff",
                "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
            )
        context = ServletFixture.start(dir, listOf(GateRunDoubles::class.java), properties, javaClass.classLoader)
    }

    @AfterAll
    fun stop() = context.close()

    @BeforeEach
    fun clear() {
        context.getBean(GateRunDoubles::class.java).runs.clear()
        context.getBean(GateRunDoubles::class.java).trains.clear()
    }

    private fun post(
        body: String,
        train: String = version,
    ): HttpResponse<String> {
        val port = context.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$ROOT/$train/gate-runs"))
                .header("Content-Type", "application/json")
                .header("X-Workload-Token", "token-console-bff")
                .header("Authorization", "Bearer ${GateRunDoubles.USER_TOKEN}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Correlation-Id", "corr-1")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun expect(
        answer: HttpResponse<String>,
        status: Int,
        code: String,
        field: String? = null,
    ) {
        val body = json.readValue(answer.body(), Map::class.java)
        answer.statusCode() shouldBe status
        body["code"] shouldBe code
        if (field != null) {
            (body["errors"] as List<*>).map { (it as Map<*, *>)["field"] } shouldBe listOf(field)
        }
    }

    private val scenarios = """"gate":"SCENARIOS","environment":"SAAS","candidateResult":"PASSED""""

    @Test
    fun `a valid record answers 201 with the caller's user ID`() {
        val answer = post("{$scenarios}")
        answer.statusCode() shouldBe 201
        answer.body() shouldContain "\"recordedBy\":\"${GateRunDoubles.ACTOR}\""
    }

    @Test
    fun `an unreadable body answers 400 SRT-001 and echoes neither the value nor the exception`() {
        listOf(
            """{"gate":"CORPUS","candidateResult":"PASSED"}""",
            """{"gate":"STAGING","candidateResult":"PASSED"}""",
            """{"gate":"scenarios","environment":"SAAS","candidateResult":"PASSED"}""",
            "{",
        ).forEach { body ->
            val answer = post(body)
            expect(answer, 400, "SRT-001")
            answer.body() shouldNotContain "IllegalArgumentException"
            listOf("CORPUS", "STAGING", "scenarios").forEach { value ->
                if (body.contains(value)) answer.body() shouldNotContain value
            }
        }
    }

    @Test
    fun `a missing environment for SCENARIOS answers 422 CPL-006 on the field environment`() {
        expect(post("""{"gate":"SCENARIOS","candidateResult":"PASSED"}"""), 422, "CPL-006", "/environment")
    }

    @Test
    fun `an environment for SECURITY answers 422 CPL-006 on the field environment`() {
        val body = """{"gate":"SECURITY","environment":"SAAS","candidateResult":"PASSED"}"""
        expect(post(body), 422, "CPL-006", "/environment")
    }

    @Test
    fun `a baselineResult with a PASSED candidate answers 422 CPL-006 on the field baselineResult`() {
        expect(post("""{$scenarios,"baselineResult":"FAILED"}"""), 422, "CPL-006", "/baselineResult")
    }

    @Test
    fun `baselineFindings alone with a PASSED candidate answer 422 CPL-006 on the field baselineFindings`() {
        expect(post("""{$scenarios,"baselineFindings":["F-1"]}"""), 422, "CPL-006", "/baselineFindings")
    }

    @Test
    fun `a train that is already published answers 409 CPL-004`() {
        context.getBean(GateRunDoubles::class.java).trains[TrainVersion(version)] =
            ReleaseTrain(
                TrainVersion(version),
                TrainKind.TRAIN,
                false,
                ReleaseRecord(emptyList(), false, ActorId("rita"), Instant.parse("2026-10-12T08:45:00Z")),
                AccreditationCheck(false, null, null),
            )
        expect(post("{$scenarios}"), 409, "CPL-004")
    }

    @Test
    fun `a key that holds a different record answers 409 CPL-005`() {
        post("{$scenarios}").statusCode() shouldBe 201
        expect(post("""{"gate":"SCENARIOS","environment":"SAAS","candidateResult":"FAILED"}"""), 409, "CPL-005")
    }

    @Test
    fun `501 findings answer 400 SRT-001`() {
        val findings = (1..501).joinToString(",") { "\"F-$it\"" }
        val body = """{"gate":"SECURITY","candidateResult":"FAILED","candidateFindings":[$findings]}"""
        expect(post(body), 400, "SRT-001")
    }

    private companion object {
        const val ROOT = "/internal/v1/control-plane/release-trains"
    }
}
