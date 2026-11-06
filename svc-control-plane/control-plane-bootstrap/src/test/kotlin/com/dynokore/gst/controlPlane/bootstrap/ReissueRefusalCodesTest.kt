package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.application.commercial.FirstAdministratorAnswer
import com.dynokore.gst.controlPlane.application.commercial.InvitationCode
import com.dynokore.gst.controlPlane.domain.commercial.ProvisioningStatus
import com.dynokore.gst.controlPlane.syncedge.ReissueDoubles
import io.kotest.matchers.shouldBe
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
import java.util.UUID

/**
 * requestFirstAdministratorInvitationReissue over HTTP (INV-AC-8281, INV-Q-16202 A, INV-Q-16204 A, INV-Q-16217 A),
 * through [ServletFixture]: the status and the `code` of the problem body are read from the response and compared by
 * equality. The permission stub admits the scoped key for named actors only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReissueRefusalCodesTest {
    private val json = JsonMapper.builder().build()
    private lateinit var context: ConfigurableApplicationContext
    private val doubles get() = context.getBean(ReissueDoubles::class.java)

    @BeforeAll
    fun start(
        @TempDir dir: Path,
    ) {
        val properties =
            arrayOf(
                "service-runtime.workload-identity.callers[0].service=console-bff",
                "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
            )
        context = ServletFixture.start(dir, listOf(ReissueDoubles::class.java), properties, javaClass.classLoader)
    }

    @AfterAll
    fun stop() = context.close()

    @BeforeEach
    fun reset() {
        doubles.status = ProvisioningStatus.CREATED
        doubles.answer = FirstAdministratorAnswer.Issued("INVN-NEW", InvitationCode("code-secret-new"))
        doubles.permitted.clear()
        doubles.permitted += "carol"
        doubles.accessCalls.clear()
        doubles.messages.clear()
    }

    private fun post(
        actor: String = "carol",
        contract: String = "CTR-2026-0091",
        key: UUID = UUID.randomUUID(),
    ): HttpResponse<String> {
        val port = context.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$ROOT/$contract/first-invitation-reissues"))
                .header("X-Workload-Token", "token-console-bff")
                .header("Authorization", "Bearer user-$actor")
                .header("Idempotency-Key", key.toString())
                .header("X-Correlation-Id", "corr-1")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun expect(
        answer: HttpResponse<String>,
        status: Int,
        code: String,
    ) {
        answer.statusCode() shouldBe status
        json.readValue(answer.body(), Map::class.java)["code"] shouldBe code
        answer.headers().firstValue("Content-Type").get() shouldBe "application/problem+json"
        doubles.messages.size shouldBe 0
        answer.body() shouldNotContain "code-secret"
    }

    @Test
    fun `INV-AC-8281 a CREATED request answers 200 without the invitation code and sends one invitation`() {
        val answer = post()
        answer.statusCode() shouldBe 200
        answer.body() shouldNotContain "code-secret"
        json.readValue(answer.body(), Map::class.java)["invitationRef"] shouldBe "INVN-NEW"
        doubles.messages.size shouldBe 1
        doubles.accessCalls.size shouldBe 1
    }

    @Test
    fun `INV-AC-8281 a 409 of access-svc answers 409 CPL-008`() {
        doubles.answer = FirstAdministratorAnswer.Refused
        expect(post(), 409, "CPL-008")
    }

    @Test
    fun `INV-AC-8281 a request that does not read CREATED answers 409 CPL-008 and calls nothing`() {
        doubles.status = ProvisioningStatus.PENDING
        expect(post(), 409, "CPL-008")
        doubles.accessCalls.size shouldBe 0
    }

    @Test
    fun `INV-AC-8281 an access-svc that gives no answer or answers 503 gives 503 CPL-009`() {
        doubles.answer = FirstAdministratorAnswer.Unavailable
        expect(post(), 503, "CPL-009")
    }

    @Test
    fun `INV-AC-8281 any other 5xx or an undefined answer of access-svc gives 500 SRT-010 without detail`() {
        doubles.answer = FirstAdministratorAnswer.Failed
        val answer = post()
        expect(answer, 500, "SRT-010")
        json.readValue(answer.body(), Map::class.java).containsKey("detail") shouldBe false
    }

    @Test
    fun `INV-AC-8281 an unknown contract reference answers 404`() {
        post(contract = "CTR-UNKNOWN").statusCode() shouldBe 404
        doubles.accessCalls.size shouldBe 0
    }

    @Test
    fun `INV-AC-8281 a caller denied the key is refused with 403 even if he holds commercial admin`() {
        // dave holds the role commercial.admin in the user store; AuthorityReplica.decide denies him the key.
        val answer = post(actor = "dave")
        answer.statusCode() shouldBe 403
        doubles.accessCalls.size shouldBe 0
        doubles.messages.size shouldBe 0
    }

    @Test
    fun `INV-AC-8281 a caller permitted the key is admitted`() {
        doubles.permitted += "erin"
        post(actor = "erin").statusCode() shouldBe 200
    }

    @Test
    fun `INV-AC-8281 a retry with the same key sends access-svc the same request`() {
        val key = UUID.randomUUID()
        doubles.answer = FirstAdministratorAnswer.Unavailable
        expect(post(key = key), 503, "CPL-009")
        post(key = key).statusCode() shouldBe 503
        doubles.accessCalls.size shouldBe 2
        doubles.accessCalls[0] shouldBe doubles.accessCalls[1]
    }

    private companion object {
        const val ROOT = "/internal/v1/control-plane/tenant-provisioning-requests"
    }
}
