package com.dynokore.gst.ingestion.adapters.clients

import com.dynokore.gst.ingestion.application.recording.CanonicalRecorder
import com.dynokore.gst.ingestion.application.recording.RecordDocumentCommand
import com.dynokore.gst.ingestion.application.recording.RecordTransaction
import com.dynokore.gst.ingestion.domain.recording.CanonicalAnswer
import com.dynokore.gst.ingestion.domain.recording.RecordingOutcome
import com.dynokore.platform.serviceruntime.client.InternalRestClientAutoConfiguration
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenPropagationInterceptor
import com.dynokore.platform.serviceruntime.resilience.EdgeProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceProperties
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.RetryProperties
import com.dynokore.platform.serviceruntime.resilience.http.CurrentEndUserToken
import com.dynokore.platform.serviceruntime.resilience.http.DeadlinePropagationInterceptor
import com.dynokore.platform.serviceruntime.resilience.resilience4j.Resilience4jResilienceRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.client.ClientHttpRequestInterceptor
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The adapter of the `recordDocument` operation against a stub HTTP server of canonical-svc (INV-T-11023): the client
 * is the generated one, the builder is the bean `internalRestClientBuilder` with the interceptors service-runtime
 * contributes, and the policy is the registry's policy of the edge `ingestion-svc->canonical-svc`.
 */
class CanonicalRecorderAdapterTest {
    private val stub = CanonicalStub()
    private val clock = Clock.systemUTC()
    private val mapper = JsonMapper.builder().build()
    private val tenant = UUID.randomUUID()
    private val docUuid = UUID.randomUUID()

    @AfterEach
    fun stopStub() = stub.close()

    private val body =
        """
        {"tenantId":"$tenant","entityRef":"ENT-1","sourceSystem":"erp-acme","sourceKey":"INV-1","sourceRevision":3,
         "documentFunction":"INVOICE","transactionDate":"2026-10-01","currency":"SGD","sourceTimeZone":"Asia/Singapore",
         "lines":[{"lineNumber":1,"lineAmount":"10.00","taxCode":"SR"}]}
        """.trimIndent()

    private val command = RecordDocumentCommand(tenant, "erp-acme", "INV-1", 3, body)

    private fun registry(
        timeout: Duration = Duration.ofSeconds(5),
        retry: RetryProperties? = null,
    ): ResilienceRegistry =
        Resilience4jResilienceRegistry(
            ResilienceProperties(edges = mapOf(EDGE to EdgeProperties(timeout = timeout, retry = retry))),
            clock,
        )

    private fun runner(
        url: String?,
        registry: ResilienceRegistry,
    ): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    InternalRestClientAutoConfiguration::class.java,
                    PropertyPlaceholderAutoConfiguration::class.java,
                ),
            ).withBean("deadline", ClientHttpRequestInterceptor::class.java, { DeadlinePropagationInterceptor(clock) })
            .withBean(
                "workload",
                ClientHttpRequestInterceptor::class.java,
                { WorkloadTokenPropagationInterceptor { WorkloadToken("workload-token-of-ingestion") } },
            ).withBean(
                "endUser",
                ClientHttpRequestInterceptor::class.java,
                {
                    com.dynokore.platform.serviceruntime.resilience.http
                        .EndUserTokenPropagationInterceptor()
                },
            ).withBean(ResilienceRegistry::class.java, { registry })
            .withBean(Clock::class.java, { clock })
            .withUserConfiguration(CanonicalRecorderAdapter::class.java)
            .withPropertyValues(*listOfNotNull(url?.let { "ingestion.canonical-svc-url=$it" }).toTypedArray())

    private fun <T> withAdapter(
        registry: ResilienceRegistry = registry(),
        block: (CanonicalRecorder) -> T,
    ): T {
        var result: T? = null
        runner(stub.url, registry).run { context ->
            context.startupFailure shouldBe null
            result = block(context.getBean(CanonicalRecorder::class.java))
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun replyJson(
        status: Int,
        reasons: String = "",
    ) = CanonicalStub.Reply(
        status,
        """{"docUuid":"$docUuid","version":2,"scopeVerdict":"SUBMITTABLE","transactionDate":"2026-10-01",
           "prescribedPeriodRef":"2026Q3"$reasons}""",
    )

    @Test
    fun `INV-AC-2178 a replay answered 200 returns REPLAY with the docUuid of canonical-svc, one request`() {
        stub.reply = replyJson(200)
        val outcome = withAdapter { RecordTransaction(it).execute(command) }
        outcome shouldBe RecordingOutcome.Replay(docUuid)
        stub.seen.size shouldBe 1
    }

    @Test
    fun `one POST carries the command body and the headers of an end-user operation on a sync edge`() {
        stub.reply = replyJson(201)
        withAdapter { adapter -> CurrentEndUserToken.callWith("Bearer end-user-1") { adapter.record(command) } }
        val request = stub.seen.single()
        request.method shouldBe "POST"
        request.path shouldBe "/internal/v1/canonical/documents"
        mapper.readTree(request.body) shouldBe mapper.readTree(body)
        request.headers["authorization"] shouldBe "Bearer end-user-1"
        request.headers["x-workload-token"] shouldBe "workload-token-of-ingestion"
        (request.headers["x-deadline-ms"]!!.toLong() in 1..2000L) shouldBe true
    }

    @Test
    fun `201 gives Created, 200 gives Replay and 202 gives Held with the reasons in the stub order`() {
        stub.reply = replyJson(201)
        withAdapter { it.record(command) } shouldBe CanonicalAnswer.Created(docUuid, 2)
        stub.reply = replyJson(200)
        withAdapter { it.record(command) } shouldBe CanonicalAnswer.Replay(docUuid, 2)
        stub.reply =
            replyJson(
                202,
                ""","reasons":[{"code":"B-2","message":"b"},{"code":"A-1","field":"lines","message":"a"}]""",
            )
        withAdapter { it.record(command) } shouldBe CanonicalAnswer.Held(docUuid, 2, listOf("B-2", "A-1"))
    }

    @Test
    fun `a 4xx with the Error envelope gives its code and status, anything else gives the HTTP status only`() {
        val envelope =
            """{"type":"https://errors/ING-002","title":"Stale","status":409,"code":"ING-002","correlationId":"c-1"}"""
        stub.reply = CanonicalStub.Reply(409, envelope)
        withAdapter { it.record(command) } shouldBe CanonicalAnswer.Rejected("ING-002", 409)
        stub.reply = CanonicalStub.Reply(422, """{"message":"not the envelope"}""")
        withAdapter { it.record(command) } shouldBe CanonicalAnswer.Rejected(null, 422)
        stub.reply = CanonicalStub.Reply(400, "plain text")
        withAdapter { it.record(command) } shouldBe CanonicalAnswer.Rejected(null, 400)
        stub.reply = CanonicalStub.Reply(503, envelope.replace("409", "503"))
        withAdapter { it.record(command) } shouldBe CanonicalAnswer.Rejected(null, 503)
        stub.reply = CanonicalStub.Reply(204)
        withAdapter { it.record(command) } shouldBe CanonicalAnswer.Rejected(null, 204)
    }

    @Test
    fun `an answer after the read timeout throws, one request when the edge has no retry`() {
        stub.reply = CanonicalStub.Reply(201, delayMillis = 1500)
        shouldThrow<CanonicalUnavailableException> {
            withAdapter(registry(timeout = Duration.ofMillis(300))) { it.record(command) }
        }.message shouldContain "timed out"
        stub.seen.size shouldBe 1
    }

    @Test
    fun `an edge that retries makes as many requests as its policy allows and then throws`() {
        stub.reply = CanonicalStub.Reply(201, delayMillis = 1500)
        val retry =
            RetryProperties(maxAttempts = 2, initialBackoff = Duration.ofMillis(10), maxBackoff = Duration.ofMillis(20))
        shouldThrow<CanonicalUnavailableException> {
            withAdapter(registry(timeout = Duration.ofMillis(300), retry = retry)) { it.record(command) }
        }
        stub.seen.size shouldBe 2
    }

    @Test
    fun `a refused connection throws`() {
        val closed = ServerSocket(0).use { it.localPort }
        runner("http://127.0.0.1:$closed", registry()).run { context ->
            shouldThrow<CanonicalUnavailableException> {
                context
                    .getBean(
                        CanonicalRecorder::class.java,
                    ).record(command)
            }.message shouldContain "refused"
        }
    }

    @Test
    fun `the context does not start without ingestion canonical-svc-url or with a blank one`() {
        runner(null, registry()).run { (it.startupFailure != null) shouldBe true }
        runner(" ", registry()).run { (it.startupFailure != null) shouldBe true }
    }

    @Test
    fun `a body with a member the generated request cannot carry is refused, not silently cut`() {
        withAdapter { adapter ->
            shouldThrow<Exception> {
                adapter.record(command.copy(body = body.replace("\"currency\"", "\"unknownMember\":1,\"currency\"")))
            }
        }
        stub.seen.size shouldBe 0
    }

    private companion object {
        const val EDGE = "ingestion-svc->canonical-svc"
    }
}
