package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.ap.HttpAccessPointAdapter
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.AttemptEgress.Companion.ATTEMPT_EGRESS
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.SubmissionAttempt.Companion.SUBMISSION_ATTEMPT
import com.dynokore.gst.transport.application.AccessPointAdapters
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.TransportOutcome
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Access Point port at the service boundary (P109): the full TPL-18 context over a real database, with the
 * certification-kit AP simulator as the adapter under test. Each test runs with adapters of ids of its own.
 */
class SubmitPayloadBoundaryTest {
    @BeforeEach
    fun resetSimulator() {
        ApSimulator.reset()
    }

    private fun sandbox(vararg more: String) = arrayOf("transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}", *more)

    private fun doc() = DocRef(UUID.randomUUID(), 1)

    /** Every attempt of the tenant has exactly one egress record and names the entity's adapter. */
    private fun Harness.assertOneEgressThrough(
        tenant: UUID,
        adapter: String,
    ) {
        val log = attemptLog(tenant)
        log shouldHaveSize 1
        log.single().second shouldBe adapter
        log.single().third shouldBe 1
    }

    /** Every request the simulator received is an egress record of the attempt log (nothing past the port). */
    private fun Harness.assertEveryRequestLogged(tenant: UUID) {
        val logged = asTenant(tenant) { dsl.fetchCount(ATTEMPT_EGRESS) }
        ApSimulator.callCount() shouldBe logged
    }

    @Test
    fun `INV-AC-1757 a System-initiated submission leaves only through the entity's adapter with one egress`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        h.submitPayload(tenant, "E-001", doc()).shouldBeInstanceOf<TransportOutcome.Accepted>()
        h.assertOneEgressThrough(tenant, h.primary.value)
        ApSimulator.submissions().map { it.get("adapterId").asString() } shouldBe listOf("primary")
        h.assertEveryRequestLogged(tenant)
    }

    @Test
    fun `INV-AC-7853 the attempt log holds one egress per attempt and the simulator received one request`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        h.submitPayload(tenant, "E-001", doc())
        h.assertOneEgressThrough(tenant, h.primary.value)
        ApSimulator.callCount() shouldBe 1
        ApSimulator.submissions() shouldHaveSize 1
    }

    @Test
    fun `INV-AC-7853 a second egress record for one attempt makes the one-egress check fail`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        h.submitPayload(tenant, "E-001", doc())
        val attempt = h.attemptLog(tenant).single().first
        h.asTenant(tenant) {
            h.dsl
                .insertInto(ATTEMPT_EGRESS)
                .set(ATTEMPT_EGRESS.ID, UUID.randomUUID())
                .set(ATTEMPT_EGRESS.TENANT_ID, tenant)
                .set(ATTEMPT_EGRESS.ATTEMPT_ID, attempt)
                .set(ATTEMPT_EGRESS.ADAPTER_ID, h.primary.value)
                .set(ATTEMPT_EGRESS.SENT_AT, java.time.OffsetDateTime.now())
                .execute()
        }
        shouldThrow<AssertionError> { h.assertOneEgressThrough(tenant, h.primary.value) }
    }

    @Test
    fun `INV-AC-7854 the submission goes through the adapter under test and its id is recorded per attempt`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        val outcome = h.submitPayload(tenant, "E-001", doc())
        ApSimulator.submissions() shouldHaveSize 1
        h.attemptLog(tenant).single().second shouldBe h.primary.value
        // the response carries the three-outcome type only: an acknowledgement, no envelope or code field
        outcome.shouldBeInstanceOf<TransportOutcome.Accepted>()
        outcome::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name } shouldBe listOf("acknowledgementId")
        h.assertEveryRequestLogged(tenant)
    }

    @Test
    fun `INV-AC-7854 a request sent past the port does not appear in the attempt log and fails the check`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        h.submitPayload(tenant, "E-001", doc())
        val bypass =
            HttpRequest
                .newBuilder(URI.create("${ApSimulator.baseUrl}/ap/v1/primary/submissions"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"packageId":"X","documents":[{"docUuid":"${UUID.randomUUID()}","version":1,""" +
                            """"documentType":"INVOICE","sizeBytes":1,"payload":"x"}]}""",
                    ),
                ).build()
        HttpClient.newHttpClient().send(bypass, HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 202
        shouldThrow<AssertionError> { h.assertEveryRequestLogged(tenant) }
    }

    @Test
    fun `INV-AC-1758 switching an entity to a second adapter changes only where its next submission goes`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        val first = doc()
        h.assign(tenant, "E-001", h.primary)
        h.submitPayload(tenant, "E-001", first)
        val before = h.recordedDocs(tenant)
        h.assign(tenant, "E-001", h.secondary)
        val second = doc()
        h.submitPayload(tenant, "E-001", second)
        ApSimulator.submissions().map { it.get("adapterId").asString() } shouldBe listOf("primary", "secondary")
        h.attemptLog(tenant).map { it.second }.toSet() shouldBe setOf(h.primary.value, h.secondary.value)
        // the Doc UUIDs and versions already recorded are unchanged
        h.recordedDocs(tenant).containsAll(before) shouldBe true
    }

    @Test
    fun `INV-AC-846 versions in flight finish on the old adapter and no Doc UUID is sent twice`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox()) { h ->
        val tenant = UUID.randomUUID()
        val inFlight = doc()
        h.assign(tenant, "E-001", h.primary)
        ApSimulator.control("/ap/adapters/primary/outage", """{"enabled":true}""")
        h.submitPayload(tenant, "E-001", inFlight) shouldBe TransportOutcome.TransientFailure
        ApSimulator.control("/ap/adapters/primary/outage", """{"enabled":false}""")
        // the entity is cut over to the second adapter in the middle of the period
        h.assign(tenant, "E-001", h.secondary)
        val fresh = doc()
        h.submitPayload(tenant, "E-001", fresh).shouldBeInstanceOf<TransportOutcome.Accepted>()
        h.submitPayload(tenant, "E-001", inFlight).shouldBeInstanceOf<TransportOutcome.Accepted>()
        val sent =
            ApSimulator.submissions().map {
                it.get("adapterId").asString() to
                    it
                        .get("documents")
                        .get(0)
                        .get("docUuid")
                        .asString()
            }
        sent shouldBe listOf("secondary" to fresh.docUuid.toString(), "primary" to inFlight.docUuid.toString())
        sent.map { it.second }.toSet() shouldHaveSize sent.size
        // a repeat of a settled version makes no second egress
        h.submitPayload(tenant, "E-001", inFlight).shouldBeInstanceOf<TransportOutcome.Accepted>()
        ApSimulator.submissions() shouldHaveSize 2
    }

    @Test
    fun `INV-AC-1850 the interface answers only the three outcomes whatever the AP reports`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox("transport.ap.timeout=700ms")) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        val accepted = h.submitPayload(tenant, "E-001", doc())
        ApSimulator.control(
            "/faults/error",
            """{"status":422,"body":"{\"status\":\"AP_ERROR\",\"code\":\"AP-PKG-INVALID-DOCUMENT\"}"}""",
        )
        val rejected = h.submitPayload(tenant, "E-001", doc())
        ApSimulator.control("/faults/clear", "{}")
        ApSimulator.control("/faults/latency", """{"delayMs":3000}""")
        val timedOut = h.submitPayload(tenant, "E-001", doc())
        accepted.shouldBeInstanceOf<TransportOutcome.Accepted>()
        rejected.shouldBeInstanceOf<TransportOutcome.Rejected>().reasons.forEach {
            it.code.startsWith("AP-") shouldBe false
            it.message.contains("AP-PKG") shouldBe false
        }
        timedOut shouldBe TransportOutcome.TransientFailure
    }

    @Test
    fun `INV-AC-7857 submit answers accepted, rejected and transient failure for what the simulator does`(
        @TempDir dir: Path,
    ) = Harness.run(dir, *sandbox("transport.ap.timeout=700ms")) { h ->
        val tenant = UUID.randomUUID()
        h.assign(tenant, "E-001", h.primary)
        h.submitPayload(tenant, "E-001", doc()).shouldBeInstanceOf<TransportOutcome.Accepted>()
        ApSimulator.control("/faults/error", """{"status":422,"body":"{}"}""")
        h
            .submitPayload(
                tenant,
                "E-001",
                doc(),
            ).shouldBeInstanceOf<TransportOutcome.Rejected>()
            .reasons
            .map { it.code } shouldBe
            listOf("SUB-003")
        ApSimulator.control("/faults/clear", "{}")
        ApSimulator.control("/faults/latency", """{"delayMs":3000}""")
        h.submitPayload(tenant, "E-001", doc()) shouldBe TransportOutcome.TransientFailure
    }

    @Test
    fun `INV-AC-7852 the adapter configuration points at the sandbox endpoint and the simulator receives it there`(
        @TempDir dir: Path,
    ) {
        val production = ProductionTrap()
        try {
            Harness.run(dir, *sandbox("transport.ap.production-endpoint=${production.url}")) { h ->
                val tenant = UUID.randomUUID()
                h.assign(tenant, "E-001", h.primary)
                h.submitPayload(tenant, "E-001", doc())
                val adapter =
                    h.context
                        .getBean(
                            AccessPointAdapters::class.java,
                        ).find(h.primary) as HttpAccessPointAdapter
                val used = checkNotNull(adapter.lastRequestUri)
                // compared by equality with the configured sandbox endpoint
                "${used.scheme}://${used.authority}" shouldBe ApSimulator.baseUrl
                ApSimulator.submissions() shouldHaveSize 1
                production.hits.get() shouldBe 0
            }
        } finally {
            production.stop()
        }
    }

    @Test
    fun `INV-AC-7852 a configuration naming a look-alike host does not equal the sandbox endpoint`(
        @TempDir dir: Path,
    ) {
        val lookAlike = URI(ApSimulator.baseUrl).let { "http://lookalike-${it.host}:${it.port}" }
        lookAlike shouldNotBe ApSimulator.baseUrl
        Harness.run(dir, "transport.ap.sandbox-endpoint=$lookAlike") { h ->
            val tenant = UUID.randomUUID()
            h.assign(tenant, "E-001", h.primary)
            h.submitPayload(tenant, "E-001", doc())
            val used =
                checkNotNull(
                    (
                        h.context
                            .getBean(
                                AccessPointAdapters::class.java,
                            ).find(h.primary) as HttpAccessPointAdapter
                    ).lastRequestUri,
                )
            shouldThrow<AssertionError> { "${used.scheme}://${used.authority}" shouldBe ApSimulator.baseUrl }
        }
    }

    private fun Harness.recordedDocs(tenant: UUID) =
        asTenant(tenant) {
            dsl.select(SUBMISSION_ATTEMPT.DOC_UUID, SUBMISSION_ATTEMPT.VERSION).from(SUBMISSION_ATTEMPT).fetch().map {
                it.value1() to it.value2()
            }
        }

    /** A production endpoint that counts the requests it gets: a sandbox configuration must never reach it. */
    private class ProductionTrap {
        val hits = AtomicInteger()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") {
                    hits.incrementAndGet()
                    it.sendResponseHeaders(503, -1)
                    it.close()
                }
                start()
            }
        val url: String get() = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)
    }
}
