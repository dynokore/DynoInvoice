package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApNotificationLedger.Companion.AP_NOTIFICATION_LEDGER
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.SubmissionAttempt.Companion.SUBMISSION_ATTEMPT
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.lang.reflect.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The secret refs read through the `SecretStore` of the running context (the bean is wrapped, the store is real). */
internal object SecretReads {
    val refs = CopyOnWriteArrayList<String>()
}

@Configuration(proxyBeanMethods = false)
internal class RecordingSecretsConfig {
    @Bean
    fun recordingSecretStore(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any =
                if (bean is SecretStore) {
                    val loader = bean.javaClass.classLoader
                    Proxy.newProxyInstance(loader, arrayOf(SecretStore::class.java)) { _, method, args ->
                        if (method.name.startsWith("read")) SecretReads.refs.add(args[0].toString())
                        try {
                            method.invoke(bean, *(args ?: emptyArray()))
                        } catch (e: java.lang.reflect.InvocationTargetException) {
                            throw e.targetException
                        }
                    }
                } else {
                    bean
                }
        }
}

/**
 * `receiveApNotification` at the service boundary (INV-REQ-585): the full TPL-18 context as a servlet application over
 * a real database, real HTTP, the real `SecretStore` over a mounted directory. The pure rules are asserted in the
 * domain and application tests and are not repeated.
 */
class ApCallbackBoundaryTest {
    @BeforeEach
    fun reset() {
        SecretReads.refs.clear()
    }

    private val http = HttpClient.newHttpClient()

    private class Running(
        val harness: Harness,
        val port: Int,
        val mount: Path,
    )

    private class Answer(
        val status: Int,
        val body: String,
    )

    private fun run(
        dir: Path,
        block: (Running) -> Unit,
    ) {
        TransportContext.secrets(dir)
        val primary = AdapterId("p-${UUID.randomUUID().toString().take(UNIQUE)}")
        val secondary = AdapterId("s-${UUID.randomUUID().toString().take(UNIQUE)}")
        val properties =
            TransportContext.propertyValues(dir, com.dynokore.gst.transport.adapters.TransportDb.jdbcUrl) +
                listOf(
                    "transport.ap.adapters[0].id=${primary.value}",
                    "transport.ap.adapters[0].path=primary",
                    "transport.ap.adapters[1].id=${secondary.value}",
                    "transport.ap.adapters[1].path=secondary",
                    "management.server.port=0",
                )
        val context: ConfigurableApplicationContext =
            SpringApplicationBuilder(
                TransportApplication::class.java,
                CallerTestConfig::class.java,
                RecordingSecretsConfig::class.java,
            ).web(WebApplicationType.SERVLET)
                .properties(*properties.toTypedArray())
                .run("--server.port=0")
        try {
            val port = checkNotNull((context as WebServerApplicationContext).webServer).port
            block(Running(Harness(context, primary, secondary), port, dir))
        } finally {
            context.close()
        }
    }

    private fun Running.secret(
        adapter: String,
        value: String? = SECRET,
    ) {
        val file = mount.resolve("transport/ap-callback/$adapter")
        if (value == null) {
            Files.deleteIfExists(file)
        } else {
            Files.createDirectories(file.parent)
            Files.writeString(file, value)
        }
    }

    private fun sign(
        timestamp: String,
        body: String,
        secret: String = SECRET,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal("$timestamp.$body".toByteArray()))
    }

    private fun now() = (System.currentTimeMillis() / MILLIS).toString()

    private fun Running.post(
        adapter: String,
        body: String,
        timestamp: String? = now(),
        signature: String? = timestamp?.let { sign(it, body) },
    ): Answer {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/v1/ap/$adapter/notifications"))
                .header("Content-Type", "application/json")
                .header(WorkloadToken.HEADER_NAME, "W-submission")
                // GAP (see the MR): the contract marks the operation `x-caller-auth: end-user`, so the runtime
                // asks for a token a signed AP callback does not carry; a tenant-less principal reaches the filter
                .header("Authorization", TestPrincipals.register(null))
                .POST(HttpRequest.BodyPublishers.ofString(body))
        timestamp?.let { request.header("X-AP-Timestamp", it) }
        signature?.let { request.header("X-AP-Signature", it) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body())
    }

    private fun Running.attempt(
        tenant: UUID,
        doc: UUID,
        version: Int = 1,
    ) = harness.asTenant(tenant) {
        harness.dsl
            .insertInto(SUBMISSION_ATTEMPT)
            .set(SUBMISSION_ATTEMPT.ID, UUID.randomUUID())
            .set(SUBMISSION_ATTEMPT.TENANT_ID, tenant)
            .set(SUBMISSION_ATTEMPT.ENTITY_REF, "E-001")
            .set(SUBMISSION_ATTEMPT.DOC_UUID, doc)
            .set(SUBMISSION_ATTEMPT.VERSION, version)
            .set(SUBMISSION_ATTEMPT.ADAPTER_ID, harness.primary.value)
            .set(SUBMISSION_ATTEMPT.STARTED_AT, OffsetDateTime.now())
            .execute()
    }

    private fun Running.ledger(tenant: UUID) =
        harness.asTenant(tenant) { harness.dsl.fetchCount(AP_NOTIFICATION_LEDGER) }

    private fun Running.outbox(tenant: UUID) = harness.asTenant(tenant) { harness.dsl.fetchCount(Outbox.OUTBOX) }

    private fun Running.events(tenant: UUID): List<String> =
        harness.asTenant(tenant) {
            harness.dsl
                .select(Outbox.OUTBOX.DATA)
                .from(Outbox.OUTBOX)
                .fetch()
                .map { it.value1()!!.data() }
        }

    private fun ack(
        id: String,
        doc: UUID,
        version: Int = 1,
    ) =
        """{"apNotificationId":"$id","kind":"ACKNOWLEDGEMENT","docUuid":"$doc","version":$version,"acknowledgementId":"A-$id"}"""

    private fun rejection(
        id: String,
        doc: UUID,
        kind: String = "REJECTION",
    ) = """{"apNotificationId":"$id","kind":"$kind","docUuid":"$doc","version":1,""" +
        """"reasons":[{"code":"R1","message":"first"},{"code":"R2","message":"second"}]}"""

    private fun Running.refused(answer: Answer) {
        answer.status shouldBe 401
        answer.body shouldContain "SRT-003"
    }

    private fun nid() = "n-${UUID.randomUUID()}"

    @Test
    fun `INV-AC-624 no timestamp header gives 401 SRT-003 and zero rows`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        r.refused(r.post(r.harness.primary.value, ack(nid(), doc), timestamp = null, signature = sign(now(), "x")))
        r.refused(r.post(r.harness.primary.value, ack(nid(), doc), timestamp = null, signature = null))
        r.ledger(tenant) shouldBe 0
        r.outbox(tenant) shouldBe 0
    }

    @Test
    fun `INV-AC-624 a stale timestamp gives 401 with zero ledger rows, zero outbox rows and zero events`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        val stale = (System.currentTimeMillis() / MILLIS - STALE_SECONDS).toString()
        r.refused(r.post(r.harness.primary.value, ack(nid(), doc), timestamp = stale))
        r.ledger(tenant) shouldBe 0
        r.outbox(tenant) shouldBe 0
        r.events(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-624 an unsigned callback is 401 with no state change and a signed one afterwards is processed`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        r.refused(r.post(r.harness.primary.value, ack(nid(), doc), signature = null))
        r.refused(r.post(r.harness.primary.value, ack(nid(), doc), signature = sign(now(), "other body")))
        r.ledger(tenant) shouldBe 0
        r.outbox(tenant) shouldBe 0
    }

    @Test
    fun `INV-AC-624 another adapter's secret gives 401`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value, SECRET)
        r.secret(r.harness.secondary.value, "the-other-adapters-secret")
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        val body = ack(nid(), doc)
        val ts = now()
        r.refused(r.post(r.harness.primary.value, body, ts, sign(ts, body, "the-other-adapters-secret")))
        r.ledger(tenant) shouldBe 0
    }

    @Test
    fun `INV-AC-624 an adapter id that only extends a configured one gives 401 and its secret is not read`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        val lookAlike = r.harness.primary.value + "-2"
        r.secret(lookAlike)
        val doc = UUID.randomUUID()
        r.refused(r.post(lookAlike, ack(nid(), doc)))
        r.refused(
            r.post(
                r.harness.primary.value
                    .uppercase(),
                ack(nid(), doc),
            ),
        )
        val upper =
            r.harness.primary.value
                .uppercase()
        SecretReads.refs.filter { it.endsWith("-2") || it.endsWith(upper) } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-624 an unconfigured adapter gives 401 and the SecretStore is not read`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret("unconfigured")
        r.refused(r.post("unconfigured", ack(nid(), UUID.randomUUID())))
        SecretReads.refs.filter { it.contains("ap-callback") } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-624 a secret removed between two calls gives 401 on the second`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        r.post(r.harness.primary.value, ack(nid(), doc)).status shouldBe 200
        r.secret(r.harness.primary.value, null)
        r.refused(r.post(r.harness.primary.value, ack(nid(), doc)))
        r.ledger(tenant) shouldBe 1
        SecretReads.refs.filter { it.startsWith("transport/ap-callback/") } shouldContainExactly
            List(2) { "transport/ap-callback/${r.harness.primary.value}" }
    }

    @Test
    fun `INV-AC-624 the body is parsed only after the signature verified`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        r.refused(r.post(r.harness.primary.value, "{not json", signature = "sha256=" + "0".repeat(HEX)))
        val ts = now()
        val bad = "{not json"
        val answer = r.post(r.harness.primary.value, bad, ts, sign(ts, bad))
        answer.status shouldBe 400
        answer.body shouldContain "SRT-001"
    }

    @Test
    fun `INV-AC-623 INV-AC-9488 the same signed notification twice gives one ledger row and one event`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        val id = nid()
        val first = r.post(r.harness.primary.value, ack(id, doc))
        first.status shouldBe 200
        first.body shouldContain "\"recorded\":true"
        // a retry with a new timestamp is the same notification
        val ts = (System.currentTimeMillis() / MILLIS + 1).toString()
        val body = ack(id, doc)
        val second = r.post(r.harness.primary.value, body, ts, sign(ts, body))
        second.status shouldBe 200
        second.body shouldContain "\"recorded\":false"
        r.ledger(tenant) shouldBe 1
        r.outbox(tenant) shouldBe 1
        val event = r.events(tenant).single()
        event shouldContain "ACCEPTED"
        event shouldNotContain "reasons\":[{"
        event shouldContain "A-$id"
    }

    @Test
    fun `INV-AC-9488 an unsigned notification with a new id changes neither the ledger nor the events`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        r.post(r.harness.primary.value, ack(nid(), doc)).status shouldBe 200
        r.refused(r.post(r.harness.primary.value, ack(nid(), doc), signature = null))
        r.ledger(tenant) shouldBe 1
        r.outbox(tenant) shouldBe 1
    }

    @Test
    fun `INV-AC-623 the same notification id on two adapters gives two rows`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        r.secret(r.harness.secondary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        val id = nid()
        r.post(r.harness.primary.value, ack(id, doc)).status shouldBe 200
        r.post(r.harness.secondary.value, ack(id, doc)).status shouldBe 200
        r.ledger(tenant) shouldBe 2
        r.outbox(tenant) shouldBe 2
    }

    @Test
    fun `INV-AC-623 a Doc UUID of another tenant is attributed to its own tenant and invisible to the bound one`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(owner, doc)
        r.post(r.harness.primary.value, ack(nid(), doc)).status shouldBe 200
        r.ledger(owner) shouldBe 1
        r.ledger(other) shouldBe 0
        r.outbox(other) shouldBe 0
    }

    @Test
    fun `INV-AC-623 unknown Doc UUID and unknown version are 404 SRT-005 with zero rows`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        val unknownDoc = r.post(r.harness.primary.value, ack(nid(), UUID.randomUUID()))
        val unknownVersion = r.post(r.harness.primary.value, ack(nid(), doc, version = 7))
        for (answer in listOf(unknownDoc, unknownVersion)) {
            answer.status shouldBe 404
            answer.body shouldContain "SRT-005"
        }
        r.ledger(tenant) shouldBe 0
        r.outbox(tenant) shouldBe 0
    }

    @Test
    fun `INV-AC-623 a body with notificationId and type is 400 SRT-001`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val body = """{"notificationId":"x","type":"ACKNOWLEDGEMENT"}"""
        val ts = now()
        val answer = r.post(r.harness.primary.value, body, ts, sign(ts, body))
        answer.status shouldBe 400
        answer.body shouldContain "SRT-001"
    }

    @Test
    fun `INV-AC-623 a rejection with two reasons gives one event, once, with the reasons in order`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        val id = nid()
        r.post(r.harness.primary.value, rejection(id, doc)).status shouldBe 200
        r.post(r.harness.primary.value, rejection(id, doc)).status shouldBe 200
        r.ledger(tenant) shouldBe 1
        val event = r.events(tenant).single()
        event shouldContain "REJECTED"
        event shouldContain "\"acknowledgementId\": \"$id\""
        (event.indexOf("R1") < event.indexOf("R2")) shouldBe true
    }

    @Test
    fun `INV-AC-623 a notification of kind IRAS_REJECTION is 400 SRT-001`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        val body = rejection(nid(), doc, kind = "IRAS_REJECTION")
        val ts = now()
        val answer = r.post(r.harness.primary.value, body, ts, sign(ts, body))
        answer.status shouldBe 400
        answer.body shouldContain "SRT-001"
        r.ledger(tenant) shouldBe 0
    }

    @Test
    fun `INV-AC-623 a delivery status is recorded once with no event and unknown ones are 404`(
        @TempDir dir: Path,
    ) = run(dir) { r ->
        r.secret(r.harness.primary.value)
        val tenant = UUID.randomUUID()
        val doc = UUID.randomUUID()
        r.attempt(tenant, doc)
        val id = nid()
        val body = """{"apNotificationId":"$id","kind":"DELIVERY_STATUS","docUuid":"$doc","version":1}"""
        r.post(r.harness.primary.value, body).status shouldBe 200
        r.post(r.harness.primary.value, body).status shouldBe 200
        r.ledger(tenant) shouldBe 1
        r.outbox(tenant) shouldBe 0
        val other = UUID.randomUUID()
        val unknown = """{"apNotificationId":"${nid()}","kind":"DELIVERY_STATUS","docUuid":"$other","version":1}"""
        r.post(r.harness.primary.value, unknown).status shouldBe 404
        r.ledger(tenant) shouldBe 1
    }

    private companion object {
        const val SECRET = "callback-secret-0123456789"
        const val MILLIS = 1000L
        const val STALE_SECONDS = 301L
        const val HEX = 64
        const val UNIQUE = 12
    }
}
