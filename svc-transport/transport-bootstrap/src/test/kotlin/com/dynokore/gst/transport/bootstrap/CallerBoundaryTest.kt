package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.AttemptEgress.Companion.ATTEMPT_EGRESS
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.SubmissionAttempt.Companion.SUBMISSION_ATTEMPT
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Clock
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val NS = "system:serviceaccount:platform:"

/** Workload token -> verified subject; a token not listed is refused (401). */
private val SUBJECTS =
    mapOf(
        "W-submission" to "${NS}submission-svc",
        "W-bff" to "${NS}console-bff",
        "W-tenancy" to "${NS}tenancy-svc",
        "W-unmapped" to "${NS}nobody",
        "W-submission-svcs" to "${NS}submission-svcs",
        "W-submission-short" to "${NS}submission",
    )

/** The test map of the calling services (`service-runtime.workload-identity.callers`). */
private val CALLERS =
    mapOf(
        "submission-svc" to "${NS}submission-svc",
        "console-bff" to "${NS}console-bff",
        "tenancy-svc" to "${NS}tenancy-svc",
        "submission-svcs" to "${NS}submission-svcs",
        "submission" to "${NS}submission",
    )

/** The authority-replica double: every request that reaches its fallback is counted (and answered "unavailable"). */
internal object Decisions {
    val decide = AtomicInteger()
}

@Configuration(proxyBeanMethods = false)
internal class CallerTestConfig {
    @Bean
    @Primary
    fun testWorkloadVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token: WorkloadToken? ->
            SUBJECTS[token?.value]?.let { WorkloadVerification.Verified(it) } ?: WorkloadVerification.Refused("invalid")
        }

    @Bean
    @Primary
    fun testEndUserResolver(): EndUserPrincipalResolver = TestPrincipals.resolver

    @Bean
    @Primary
    fun recordingAuthority(): AuthorityReplica =
        AuthorityReplica(
            Clock.systemUTC(),
            fallback = { _, _ ->
                Decisions.decide.incrementAndGet()
                FallbackResult.Unavailable
            },
        )
}

/**
 * The calling-service and tenant checks of the despatch operations over real HTTP (INV-Q-8339 D, INV-Q-8387;
 * INV-AC-846, INV-AC-7857): the full TPL-18 context as a servlet application, the runtime's own workload and
 * sync-edge filters in front of the generated controller.
 */
class CallerBoundaryTest {
    @BeforeEach
    fun reset() {
        ApSimulator.reset()
        Decisions.decide.set(0)
    }

    private val http = HttpClient.newHttpClient()

    private class Running(
        val context: ConfigurableApplicationContext,
        val harness: Harness,
        val port: Int,
    )

    private fun run(
        dir: Path,
        callers: Map<String, String> = CALLERS,
        block: (Running) -> Unit,
    ) {
        TransportContext.secrets(dir)
        val primary = AdapterId("p-${UUID.randomUUID().toString().take(UNIQUE)}")
        val secondary = AdapterId("s-${UUID.randomUUID().toString().take(UNIQUE)}")
        val properties =
            TransportContext.propertyValues(dir, com.dynokore.gst.transport.adapters.TransportDb.jdbcUrl) +
                listOf(
                    "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}",
                    "transport.ap.adapters[0].id=${primary.value}",
                    "transport.ap.adapters[0].path=primary",
                    "transport.ap.adapters[1].id=${secondary.value}",
                    "transport.ap.adapters[1].path=secondary",
                    "management.server.port=0",
                ) +
                callers.entries.flatMapIndexed { i, (service, subject) ->
                    listOf(
                        "service-runtime.workload-identity.callers[$i].service=$service",
                        "service-runtime.workload-identity.callers[$i].subject=$subject",
                    )
                }
        val context =
            SpringApplicationBuilder(TransportApplication::class.java, CallerTestConfig::class.java)
                .web(WebApplicationType.SERVLET)
                .properties(*properties.toTypedArray())
                .run("--server.port=0")
        try {
            val port = checkNotNull((context as WebServerApplicationContext).webServer).port
            block(Running(context, Harness(context, primary, secondary), port))
        } finally {
            context.close()
        }
    }

    private class Answer(
        val status: Int,
        val body: String,
        val retryAfter: String?,
    )

    private fun Running.post(
        path: String,
        body: String,
        workload: String?,
        authorization: String?,
        vararg headers: Pair<String, String>,
    ): Answer {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
        workload?.let { request.header(WorkloadToken.HEADER_NAME, it) }
        authorization?.let { request.header("Authorization", it) }
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body(), response.headers().firstValue("Retry-After").orElse(null))
    }

    private fun payloadBody(
        doc: UUID,
        entity: String,
    ): String {
        val payload = Base64.getEncoder().encodeToString("<Invoice/>".toByteArray())
        return """{"docUuid":"$doc","version":1,"payload":"$payload","entityRef":"$entity"}"""
    }

    private fun packageBody(): String {
        val member = """{"docUuid":"${UUID.randomUUID()}","version":1,"payloadRef":"r","sizeBytes":10}"""
        return """{"packageRef":"P-1","adapterRef":"a","documentTypeCode":"380","members":[$member]}"""
    }

    private fun networkBody() =
        """{"docUuid":"${UUID.randomUUID()}","version":1,"adapterRef":"a","peppolRecipientId":"0195:x"}"""

    private fun Running.submit(
        doc: UUID,
        workload: String?,
        authorization: String?,
        vararg headers: Pair<String, String>,
    ) = post(PAYLOADS, payloadBody(doc, "E-001"), workload, authorization, *headers)

    private fun Running.counts(tenant: UUID): Pair<Int, Int> =
        harness.asTenant(tenant) {
            harness.dsl.fetchCount(SUBMISSION_ATTEMPT) to harness.dsl.fetchCount(ATTEMPT_EGRESS)
        }

    private fun Running.refusedWithNothing(
        tenant: UUID,
        doc: UUID,
        answer: Answer,
    ) {
        answer.status shouldBe 403
        answer.body shouldContain "SRT-004"
        answer.retryAfter shouldBe null
        counts(tenant) shouldBe (0 to 0)
        ApSimulator.submissions().count { doc.toString() in it.toString() } shouldBe 0
        Decisions.decide.get() shouldBe 0
    }

    private fun Running.setUp(): Pair<UUID, String> {
        val tenant = UUID.randomUUID()
        harness.assign(tenant, "E-001", harness.primary)
        return tenant to TestPrincipals.register(tenant)
    }

    @Test
    fun `INV-Q-8387 submission-svc with a valid token of a tenant is accepted by the adapter and asks no decision`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, user) = r.setUp()
        val doc = UUID.randomUUID()
        val answer = r.submit(doc, "W-submission", user)
        answer.status shouldBe 202
        answer.body shouldContain "ACCEPTED"
        r.counts(tenant) shouldBe (1 to 1)
        Decisions.decide.get() shouldBe 0
    }

    @Test
    fun `INV-Q-8387 console-bff with a valid end-user token is 403 SRT-004, no attempt, no egress`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, user) = r.setUp()
        val doc = UUID.randomUUID()
        r.refusedWithNothing(tenant, doc, r.submit(doc, "W-bff", user))
    }

    @Test
    fun `INV-Q-8387 another calling service is 403 SRT-004, no attempt, no egress, whatever the name looks like`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, user) = r.setUp()
        // tenancy-svc; a verified subject in no entry; services named like submission-svc
        for (workload in listOf("W-tenancy", "W-unmapped", "W-submission-svcs", "W-submission-short")) {
            val doc = UUID.randomUUID()
            r.refusedWithNothing(tenant, doc, r.submit(doc, workload, user))
        }
    }

    @Test
    fun `INV-Q-8387 an empty caller map refuses every service, submission-svc included`(
        @TempDir dir: Path,
    ) = run(dir, callers = emptyMap()) { r ->
        val (tenant, user) = r.setUp()
        val doc = UUID.randomUUID()
        r.refusedWithNothing(tenant, doc, r.submit(doc, "W-submission", user))
    }

    @Test
    fun `INV-Q-8387 console-bff that sends X-Caller-Service submission-svc is still 403 SRT-004`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, user) = r.setUp()
        val doc = UUID.randomUUID()
        r.refusedWithNothing(tenant, doc, r.submit(doc, "W-bff", user, "X-Caller-Service" to "submission-svc"))
    }

    @Test
    fun `INV-Q-8387 submitPackage and sendNetworkDocument refuse console-bff and other services with 403, not 501`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (_, user) = r.setUp()
        for ((path, body) in listOf(PACKAGES to packageBody(), NETWORK to networkBody())) {
            for (workload in listOf("W-bff", "W-tenancy", "W-unmapped")) {
                val answer = r.post(path, body, workload, user)
                answer.status shouldBe 403
                answer.body shouldContain "SRT-004"
            }
            // submission-svc is not refused: the operation answers what it answers (501 while INV-Q-8047 is open)
            val allowed = r.post(path, body, "W-submission", user)
            allowed.status shouldBe 501
            allowed.body shouldNotContain "SRT-004"
        }
    }

    @Test
    fun `INV-Q-8387 a call without a workload token is 401`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, user) = r.setUp()
        r.submit(UUID.randomUUID(), null, user).status shouldBe 401
        r.submit(UUID.randomUUID(), "W-invalid", user).status shouldBe 401
        r.counts(tenant) shouldBe (0 to 0)
    }

    @Test
    fun `INV-Q-8387 submission-svc with an end-user token without tenant_id is 403 SRT-004, no attempt, no egress`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val (tenant, _) = r.setUp()
        val doc = UUID.randomUUID()
        r.refusedWithNothing(tenant, doc, r.submit(doc, "W-submission", TestPrincipals.register(null)))
    }
}

private const val PAYLOADS = "/internal/v1/transport/payloads"
private const val PACKAGES = "/internal/v1/transport/packages"
private const val NETWORK = "/internal/v1/transport/network-documents"
private const val UNIQUE = 12
