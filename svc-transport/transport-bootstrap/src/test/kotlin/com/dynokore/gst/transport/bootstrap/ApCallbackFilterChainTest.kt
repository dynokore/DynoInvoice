package com.dynokore.gst.transport.bootstrap

import com.dynokore.platform.serviceruntime.identity.UntouchedOperations
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.UUID

/** The 503 of the inbound kind and the real filter chain (INV-Q-15630 B): nothing is built to get past the filter. */
class ApCallbackFilterChainTest : ApCallbackSupport() {
    @Test
    fun `INV-AC-9488 an inbound network document is 503 SUB-024 with nothing recorded, twice`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        val body =
            """{"apNotificationId":"${nid()}","kind":"INBOUND_NETWORK_DOCUMENT","docUuid":"$doc","version":1}"""
        repeat(2) {
            val ts = now()
            val answer = r.post(r.harness.primary.value, body, ts, sign(ts, body))
            answer.status shouldBe 503
            answer.body shouldContain "\"code\":\"SUB-024\""
            answer.body shouldNotContain "SRT-011"
            answer.body shouldNotContain "SRT-010"
        }
        com.dynokore.gst.catalogue.ErrorCode.SUB_024.code shouldBe "SUB-024"
        r.ledger(tenant) shouldBe 0
        r.outbox(tenant) shouldBe 0
        // unsigned: the 503 is reached only after the signature verified
        r.refused(r.post(r.harness.primary.value, body, signature = null))
    }

    @Test
    fun `INV-AC-9488 the operations list matches the callback by method and template and nothing near it`() {
        val untouched = UntouchedOperations.from(javaClass.classLoader)
        untouched.matches("POST", "/v1/ap/primary/notifications") shouldBe true
        untouched.matches("GET", "/v1/ap/primary/notifications") shouldBe false
        untouched.matches("POST", "/v1/ap/primary/notifications/x") shouldBe false
        untouched.matches("POST", "/v1/ap/a/b/notifications") shouldBe false
        untouched.matches("POST", "/v1/ap/primary/notification") shouldBe false
    }

    @Test
    fun `INV-AC-9488 a signed notification with no Authorization header reaches the controller through the real filter`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        val answer = r.post(r.harness.primary.value, ack(nid(), doc))
        answer.status shouldBe 200
        answer.body shouldContain "\"recorded\":true"
    }

    @Test
    fun `INV-AC-624 a signature that does not verify is 401 from the signature check and the resolver is not called`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        val body = ack(nid(), doc)
        val ts = now()
        r.refused(r.post(r.harness.primary.value, body, ts, sign(ts, body, "not-the-secret")))
        r.ledger(tenant) shouldBe 0
        r.outbox(tenant) shouldBe 0
        r.events(tenant) shouldBe emptyList()
        ResolverCalls.count.get() shouldBe 0
    }

    @Test
    fun `INV-AC-9488 another operation of the contract with no Authorization header is still 401 SRT-003`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${r.port}/internal/v1/transport/payloads"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 401
        response.body() shouldContain "SRT-003"
    }
}
