package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.S3ObjectStore
import com.dynokore.platform.serviceruntime.binding.FakeSecretStore
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.s3.S3Client
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@EnableConfigurationProperties(ModuleProperties::class)
private class ModulePropertiesConfiguration

/** INV-T-12057 (INV-Q-12045 A): the S3Client of the ObjectStore binding is built from configuration. */
class ObjectStoreClientWiringTest {
    private class Seen(
        val method: String,
        val path: String,
        val authorization: String?,
    )

    private val seen = CopyOnWriteArrayList<Seen>()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { s ->
            s.createContext("/") { exchange ->
                exchange.requestBody.readAllBytes()
                seen +=
                    Seen(
                        exchange.requestMethod,
                        exchange.requestURI.rawPath,
                        exchange.requestHeaders.getFirst("Authorization"),
                    )
                val body = if (exchange.requestMethod == "GET") "payload".toByteArray() else ByteArray(0)
                exchange.sendResponseHeaders(200, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            s.start()
        }
    private val endpoint = "http://localhost:${server.address.port}"

    private val idRef = SecretRef("object-store/access-key-id")
    private val secretRef = SecretRef("object-store/secret-access-key")
    private val secrets =
        FakeSecretStore().also {
            it.put(idRef, "AKIAFIRST")
            it.put(secretRef, "first-secret-value")
        }

    @AfterEach
    fun stop() = server.stop(0)

    private fun runner(
        vararg properties: String,
        store: SecretStore = secrets,
    ) = ApplicationContextRunner()
        .withUserConfiguration(EvidenceStoreWiring::class.java, ModulePropertiesConfiguration::class.java)
        .withBean(SecretStore::class.java, { store })
        .withPropertyValues(*properties)

    private fun all(vararg overrides: Pair<String, String?>): Array<String> {
        val base =
            linkedMapOf<String, String?>(
                Props.BUCKET to "evidence",
                Props.OBJECT_STORE_REGION to "ap-southeast-1",
                Props.OBJECT_STORE_ENDPOINT to endpoint,
                Props.OBJECT_STORE_PATH_STYLE to "true",
                Props.OBJECT_STORE_ACCESS_KEY_ID to idRef.value,
                Props.OBJECT_STORE_SECRET_ACCESS_KEY to secretRef.value,
            )
        overrides.forEach { (k, v) -> base[k] = v }
        return base.filterValues { it != null }.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    private fun failure(vararg overrides: Pair<String, String?>): String {
        var message: String? = null
        runner(*all(*overrides)).run { message = rootMessage(it) }
        return checkNotNull(message) { "the context started" }
    }

    private fun rootMessage(context: AssertableApplicationContext): String? {
        var t: Throwable? = context.startupFailure ?: return null
        val messages = mutableListOf<String>()
        while (t != null) {
            t.message?.let { messages += it }
            t = t.cause
        }
        return messages.joinToString(" | ")
    }

    @Test
    fun `all inputs set builds one S3Client and the S3ObjectStore, and a rotated secret is used with no restart`() {
        runner(*all()).run { ctx ->
            ctx.startupFailure shouldBe null
            ctx.getBeansOfType(S3Client::class.java).keys shouldHaveSize 1
            val store = ctx.getBean(ObjectStore::class.java)
            (store.javaClass == S3ObjectStore::class.java) shouldBe true

            store.put(ObjectKey("k/1"), "x".toByteArray(), Instant.now().plusSeconds(60))
            seen.last().method shouldBe "PUT"
            seen.last().path shouldBe "/evidence/k/1"
            seen.last().authorization!! shouldContain "Credential=AKIAFIRST/"

            secrets.put(idRef, "AKIASECOND")
            secrets.put(secretRef, "second-secret-value")
            String(store.get(ObjectKey("k/1"))) shouldBe "payload"
            seen.last().method shouldBe "GET"
            seen.last().authorization!! shouldContain "Credential=AKIASECOND/"
        }
    }

    @Test
    fun `region absent builds neither bean`() {
        runner(*all(Props.OBJECT_STORE_REGION to null)).run { ctx ->
            ctx.startupFailure shouldBe null
            ctx.getBeansOfType(S3Client::class.java).size shouldBe 0
            ctx.getBeansOfType(ObjectStore::class.java).size shouldBe 0
        }
    }

    @Test
    fun `a user-supplied S3Client is used and the built one is not created`() {
        val own =
            S3Client
                .builder()
                .region(software.amazon.awssdk.regions.Region.AP_SOUTHEAST_1)
                .credentialsProvider(
                    software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials
                            .create("OWN", "own"),
                    ),
                ).build()
        runner(*all()).withBean(S3Client::class.java, { own }).run { ctx ->
            ctx.startupFailure shouldBe null
            ctx.getBeansOfType(S3Client::class.java).values.single() shouldBe own
            ctx.getBean(ObjectStore::class.java).javaClass shouldBe S3ObjectStore::class.java
        }
    }

    @Test
    fun `a blank region refuses the start`() {
        val m = failure(Props.OBJECT_STORE_REGION to " ")
        m shouldContain Props.OBJECT_STORE_REGION
    }

    private fun refusesEndpoint(value: String) {
        val m = failure(Props.OBJECT_STORE_ENDPOINT to value)
        m shouldContain Props.OBJECT_STORE_ENDPOINT
        m shouldNotContain "u:p"
        m shouldNotContain "first-secret-value"
    }

    @Test
    fun `endpoint ftp is refused`() = refusesEndpoint("ftp://h")

    @Test
    fun `endpoint without scheme is refused`() = refusesEndpoint("h:9000")

    @Test
    fun `endpoint without host is refused`() = refusesEndpoint("http:///x")

    @Test
    fun `endpoint with user information is refused and not echoed`() = refusesEndpoint("http://u:p@h")

    @Test
    fun `endpoint with a query is refused`() = refusesEndpoint("http://h?x=1")

    @Test
    fun `endpoint with a fragment is refused`() = refusesEndpoint("http://h#f")

    @Test
    fun `endpoint with a trailing slash is refused`() = refusesEndpoint("http://h/")

    @Test
    fun `only the access key id credential set is refused`() {
        failure(Props.OBJECT_STORE_SECRET_ACCESS_KEY to null) shouldContain Props.OBJECT_STORE_SECRET_ACCESS_KEY
    }

    @Test
    fun `only the secret access key credential set is refused`() {
        failure(Props.OBJECT_STORE_ACCESS_KEY_ID to null) shouldContain Props.OBJECT_STORE_ACCESS_KEY_ID
    }

    @Test
    fun `a credential reference naming no secret refuses the start without a value`() {
        val m = failure(Props.OBJECT_STORE_SECRET_ACCESS_KEY to "object-store/missing")
        m shouldContain Props.OBJECT_STORE_SECRET_ACCESS_KEY
        m shouldNotContain "AKIAFIRST"
        m shouldNotContain "first-secret-value"
    }

    @Test
    fun `an invalid path-style value is refused`() {
        failure(Props.OBJECT_STORE_PATH_STYLE to "maybe") shouldContain Props.OBJECT_STORE_PATH_STYLE
    }
}
