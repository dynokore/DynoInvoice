package com.dynokore.platform.serviceruntime.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.web.filter.RequestContextFilter
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private const val MOUNT_NS = "system:serviceaccount:platform:"
private const val SET_POLICY_TEMPLATE = "/internal/v1/policies/{variationPoint}"
private const val SET_POLICY_LINE = "POST\t$SET_POLICY_TEMPLATE\tconsole-bff"

private val MOUNT_SUBJECTS =
    mapOf(
        "T-console-bff" to "${MOUNT_NS}rel-console-bff",
        "T-control-plane" to "${MOUNT_NS}rel-control-plane-svc",
        "T-console-bff-two" to "${MOUNT_NS}rel-console-bff-two",
        "T-Console-Bff" to "${MOUNT_NS}Rel-console-bff",
    )

private val MOUNT_CALLERS =
    mapOf(
        "console-bff" to "${MOUNT_NS}rel-console-bff",
        "control-plane-svc" to "${MOUNT_NS}rel-control-plane-svc",
        "console-bff-two" to "${MOUNT_NS}rel-console-bff-two",
    )

internal object MountedHandler {
    val calls = AtomicInteger()
}

@Configuration(proxyBeanMethods = false)
@Import(TomcatServletWebServerAutoConfiguration::class, IdentityAutoConfiguration::class)
internal class MountedConfig {
    @Bean @Primary
    fun mountedVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token ->
            MOUNT_SUBJECTS[token?.value]?.let { WorkloadVerification.Verified(it) }
                ?: WorkloadVerification.Refused("invalid")
        }

    @Bean fun mountedRequestContext(): FilterRegistrationBean<RequestContextFilter> =
        FilterRegistrationBean(RequestContextFilter()).apply { order = -1000 }

    @Bean fun mountedHandler(): ServletRegistrationBean<HttpServlet> =
        ServletRegistrationBean(
            object : HttpServlet() {
                override fun service(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                ) {
                    MountedHandler.calls.incrementAndGet()
                    response.status = 204
                }
            },
            "/*",
        )
}

/**
 * INV-T-12602 (INV-Q-12586 A, INV-AC-12598): the callers of the operations service-runtime itself serves come from its own
 * contract, shipped as `META-INF/gst/sync-edge-callers.tsv` in the impl artefact; a service declares no line for them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MountedOperationCallersTest {
    @TempDir
    lateinit var dir: Path

    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private val running = mutableListOf<ConfigurableApplicationContext>()
    private val loaders = mutableListOf<URLClassLoader>()

    /** The lines the contract implies: every `/internal/v1/` operation with an `x-sync-edge`, read from the pinned document. */
    private fun expectedLines(): Set<String> {
        val contract =
            Path.of(
                checkNotNull(System.getProperty("gst.test.service-runtime-contract")) {
                    "contract path not set"
                },
            )
        val document =
            Files
                .newBufferedReader(
                    contract,
                    StandardCharsets.UTF_8,
                ).use { Yaml().load<Map<String, Any?>>(it) }
        val lines = mutableSetOf<String>()
        for ((path, item) in document["paths"] as Map<*, *>) {
            if (!(path as String).startsWith("/internal/v1/")) continue
            for ((method, operation) in item as Map<*, *>) {
                val edge = (operation as? Map<*, *>)?.get("x-sync-edge") ?: continue
                val callers =
                    when (edge) {
                        is List<*> -> edge.map { (it as String).substringBefore("->") }.sorted().joinToString(",")
                        else -> (edge as String).substringBefore("->")
                    }
                lines += "${(method as String).uppercase()}\t$path\t$callers"
            }
        }
        return lines
    }

    private fun shippedLines(): Set<String> {
        val urls = javaClass.classLoader.getResources(SyncEdgeCallerFilter.RESOURCE).toList()
        urls.size shouldBe 1
        return urls
            .single()
            .openStream()
            .use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            .lines()
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun start(extraResource: String? = null): Int {
        val root = Files.createTempDirectory(dir, "cp")
        if (extraResource != null) {
            val file = root.resolve(SyncEdgeCallerFilter.RESOURCE)
            Files.createDirectories(file.parent)
            Files.writeString(file, extraResource)
        }
        val loader = URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader)
        loaders += loader
        val properties =
            mutableMapOf<String, Any>("service-runtime.workload-identity.audience" to "probe-svc")
        MOUNT_CALLERS.entries.forEachIndexed { i, (service, subject) ->
            properties["service-runtime.workload-identity.callers[$i].service"] = service
            properties["service-runtime.workload-identity.callers[$i].subject"] = subject
        }
        val context =
            SpringApplicationBuilder(MountedConfig::class.java)
                .web(WebApplicationType.SERVLET)
                .resourceLoader(DefaultResourceLoader(loader))
                .properties(properties)
                .run("--server.port=0")
        running += context
        return checkNotNull((context as WebServerApplicationContext).webServer).port
    }

    /** `204` when the handler ran once, otherwise the refusal status and code with 0 handler calls. */
    private fun outcome(
        port: Int,
        token: String?,
    ): String {
        MountedHandler.calls.set(0)
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/internal/v1/policies/risk-appetite"))
                .POST(HttpRequest.BodyPublishers.noBody())
        token?.let { request.header(WorkloadToken.HEADER_NAME, it) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 204) {
            MountedHandler.calls.get() shouldBe 1
            return "204"
        }
        MountedHandler.calls.get() shouldBe 0
        val code = runCatching { mapper.readTree(response.body())["code"].asString() }.getOrNull()
        return listOfNotNull(response.statusCode().toString(), code).joinToString(" ")
    }

    @AfterAll
    fun stop() {
        running.forEach(ConfigurableApplicationContext::close)
        loaders.forEach(URLClassLoader::close)
    }

    @Test
    fun `INV-AC-12598 the lines of the jar resource equal the lines generated from the contract`() {
        val expected = expectedLines()
        expected.shouldNotBeEmpty()
        expected shouldContain SET_POLICY_LINE
        shippedLines() shouldBe expected
    }

    @Test
    fun `INV-AC-12598 a service with no line of its own lets console-bff reach setPolicy and refuses the others`() {
        val port = start()
        outcome(port, "T-console-bff") shouldBe "204"
        outcome(port, "T-control-plane") shouldBe "403 SRT-004"
        outcome(port, "T-console-bff-two") shouldBe "403 SRT-004"
        outcome(port, "T-Console-Bff") shouldBe "403 SRT-004"
        outcome(port, null) shouldBe "401"
    }

    @Test
    fun `INV-AC-12598 an identical line in another resource counts as one`() {
        val port = start("$SET_POLICY_LINE\n")
        outcome(port, "T-console-bff") shouldBe "204"
        outcome(port, "T-control-plane") shouldBe "403 SRT-004"
    }

    @Test
    fun `INV-AC-12598 a line for the same path with other callers in another resource stops the start-up`() {
        val failure =
            shouldThrow<Exception> { start("POST\t$SET_POLICY_TEMPLATE\tcontrol-plane-svc\n") }
        generateSequence<Throwable>(
            failure,
        ) { it.cause }.any { it.message.orEmpty().contains(SET_POLICY_TEMPLATE) } shouldBe
            true
    }
}
