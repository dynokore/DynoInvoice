package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.resilience.ResilienceAutoConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.filter.RequestContextFilter
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val NS = "system:serviceaccount:platform:"
private const val CP_SUBJECT = "${NS}rel-control-plane-svc"

/** Token → verified subject; a token not listed is refused. `T-nosub` is verified with the subject the JWT verifier gives it. */
private val SUBJECTS =
    mapOf(
        "T-cp" to CP_SUBJECT,
        "T-canonical" to "${NS}rel-canonical-svc",
        "T-payload" to "${NS}rel-payload-svc",
        "T-ingestion" to "${NS}rel-ingestion-svc",
        "T-cpx" to "${NS}rel-control-plane-svc-x",
        "T-other" to "${CP_SUBJECT}-2",
        "T-ns" to "system:serviceaccount:other:rel-control-plane-svc",
        "T-case" to "${NS}Rel-control-plane-svc",
        "T-space" to "$CP_SUBJECT ",
        "T-nosub" to WorkloadIdentityProperties.UNKNOWN_SUBJECT,
        "T-unmapped" to "${NS}rel-nobody",
    )

private val MAPPED =
    mapOf(
        "control-plane-svc" to CP_SUBJECT,
        "canonical-svc" to "${NS}rel-canonical-svc",
        "payload-svc" to "${NS}rel-payload-svc",
        "ingestion-svc" to "${NS}rel-ingestion-svc",
        // a name that starts with another caller's name: a prefix comparison would confuse the two
        "control-plane-svc-x" to "${NS}rel-control-plane-svc-x",
    )

private val FIXTURE =
    "GET\t/internal/v1/a\tcontrol-plane-svc\n" +
        "POST\t/internal/v1/b/{id}\tcanonical-svc,payload-svc\n" +
        "GET\t/internal/v1/c\tany\n" +
        "GET\t/internal/v1/f\tany-except:control-plane-svc\n"

/** What the probe handlers saw: calls per path, and the caller each reported. */
internal object EdgeProbe {
    val calls = ConcurrentHashMap<String, AtomicInteger>()

    fun reset() = calls.clear()

    fun count(path: String) = calls[path]?.get() ?: 0
}

@Configuration(proxyBeanMethods = false)
@Import(TomcatServletWebServerAutoConfiguration::class, IdentityAutoConfiguration::class)
internal class EdgeConfig {
    @Bean @Primary
    fun edgeTestVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token ->
            SUBJECTS[token?.value]?.let { WorkloadVerification.Verified(it) } ?: WorkloadVerification.Refused("invalid")
        }

    @Bean fun requestContext(): FilterRegistrationBean<RequestContextFilter> =
        FilterRegistrationBean(RequestContextFilter()).apply { order = -1000 }

    @Bean fun probe(caller: ServiceCaller): ServletRegistrationBean<HttpServlet> =
        ServletRegistrationBean(
            object : HttpServlet() {
                override fun service(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                ) {
                    EdgeProbe.calls.computeIfAbsent(request.requestURI) { AtomicInteger() }.incrementAndGet()
                    response.writer.write(caller.current() ?: "null")
                }
            },
            "/*",
        )
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncEdgeCallerFilterTest {
    @TempDir
    lateinit var dir: Path

    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private val running = mutableListOf<ConfigurableApplicationContext>()
    private val loaders = mutableListOf<URLClassLoader>()

    private class Server(
        val context: ConfigurableApplicationContext,
        val port: Int,
    )

    private fun settings(callers: Map<String, String>): Map<String, Any> =
        mutableMapOf<String, Any>("service-runtime.workload-identity.audience" to "probe-svc").also { map ->
            callers.entries.forEachIndexed { i, (service, subject) ->
                map["service-runtime.workload-identity.callers[$i].service"] = service
                map["service-runtime.workload-identity.callers[$i].subject"] = subject
            }
        }

    /** One worker thread, so an attribute left on it would show in the next request. */
    private fun start(
        resource: String?,
        callers: Map<String, String> = MAPPED,
    ): Server {
        val root = Files.createTempDirectory(dir, "cp")
        if (resource != null) {
            val file = root.resolve(SyncEdgeCallerFilter.RESOURCE)
            Files.createDirectories(file.parent)
            Files.writeString(file, resource)
        }
        val loader = URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader)
        loaders += loader
        val context =
            SpringApplicationBuilder(EdgeConfig::class.java)
                .web(WebApplicationType.SERVLET)
                .resourceLoader(DefaultResourceLoader(loader))
                .properties(settings(callers))
                .run("--server.port=0", "--server.tomcat.threads.max=1", "--server.tomcat.threads.min-spare=1")
        running += context
        return Server(context, checkNotNull((context as WebServerApplicationContext).webServer).port)
    }

    private val server by lazy { start(FIXTURE) }

    private fun Server.send(
        method: String,
        path: String,
        token: String?,
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
        token?.let { request.header(WorkloadToken.HEADER_NAME, it) }
        headers.forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** 200 when the handler ran once (its body the caller seen), otherwise the refusal code with 0 handler calls. */
    private fun Server.outcome(
        method: String,
        path: String,
        token: String?,
        vararg headers: Pair<String, String>,
    ): String {
        EdgeProbe.reset()
        val response = send(method, path, token, *headers)
        return when (response.statusCode()) {
            200 -> {
                EdgeProbe.count(path) shouldBe 1
                "200 ${response.body()}"
            }

            else -> {
                EdgeProbe.count(path) shouldBe 0
                "${response.statusCode()} ${runCatching {
                    mapper
                        .readTree(
                            response.body(),
                        )["code"]
                        .asString()
                }.getOrDefault("-")}"
            }
        }
    }

    @AfterAll
    fun stop() {
        running.forEach(ConfigurableApplicationContext::close)
        loaders.forEach(URLClassLoader::close)
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 a named caller reaches its operation and no other service does`() {
        server.outcome("GET", "/internal/v1/a", "T-cp") shouldBe "200 control-plane-svc"
        server.outcome("POST", "/internal/v1/b/7", "T-canonical") shouldBe "200 canonical-svc"
        server.outcome("POST", "/internal/v1/b/7", "T-payload") shouldBe "200 payload-svc"
        server.outcome("POST", "/internal/v1/b/7", "T-cp") shouldBe "403 SRT-004"
        server.outcome("GET", "/internal/v1/a", "T-canonical") shouldBe "403 SRT-004"
        server.outcome("GET", "/internal/v1/a", "T-ingestion") shouldBe "403 SRT-004"
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 an any edge lets every verified token through, mapped or not`() {
        server.outcome("GET", "/internal/v1/c", "T-ingestion") shouldBe "200 ingestion-svc"
        server.outcome("GET", "/internal/v1/c", "T-unmapped") shouldBe "200 null"
        server.outcome("GET", "/internal/v1/c", "T-cp") shouldBe "200 control-plane-svc"
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 look-alike subjects are refused on the single-caller operation`() {
        for (token in listOf("T-other", "T-ns", "T-case", "T-space", "T-nosub", "T-unmapped")) {
            server.outcome("GET", "/internal/v1/a", token) shouldBe "403 SRT-004"
        }
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 an identity header or an end-user token never names the caller`() {
        server.outcome("GET", "/internal/v1/a", "T-other", "X-Caller-Service" to "control-plane-svc") shouldBe
            "403 SRT-004"
        server.outcome(
            "GET",
            "/internal/v1/a",
            "T-other",
            "X-Forwarded-Client-Cert" to "Subject=\"$CP_SUBJECT\"",
        ) shouldBe
            "403 SRT-004"
        server.outcome("GET", "/internal/v1/a", "T-other", "Authorization" to "Bearer sub-$CP_SUBJECT") shouldBe
            "403 SRT-004"
        server.outcome("GET", "/internal/v1/c", "T-other", "X-Caller-Service" to "control-plane-svc") shouldBe
            "200 null"
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 an any-except edge refuses the excluded service, an unmapped and a null caller`() {
        server.outcome("GET", "/internal/v1/f", "T-canonical") shouldBe "200 canonical-svc"
        server.outcome("GET", "/internal/v1/f", "T-cp") shouldBe "403 SRT-004"
        server.outcome("GET", "/internal/v1/f", "T-cpx") shouldBe "200 control-plane-svc-x"
        server.outcome("GET", "/internal/v1/a", "T-cpx") shouldBe "403 SRT-004"
        server.outcome("GET", "/internal/v1/f", "T-unmapped") shouldBe "403 SRT-004"
        server.outcome("GET", "/internal/v1/f", "T-other") shouldBe "403 SRT-004"
        server.outcome("GET", "/internal/v1/f", "T-nosub") shouldBe "403 SRT-004"
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 an unlisted internal path, a trailing separator and another method are refused`() {
        for (token in SUBJECTS.keys) {
            server.outcome("GET", "/internal/v1/z", token) shouldBe "403 SRT-004"
        }
        server.outcome("POST", "/internal/v1/b/7/", "T-canonical") shouldBe "403 SRT-004"
        server.outcome("GET", "/internal/v1/b/7", "T-canonical") shouldBe "403 SRT-004"
        server.outcome("POST", "/internal/v1/b/", "T-canonical") shouldBe "403 SRT-004"
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 a path outside internal v1 is not touched and a missing token is 401`() {
        server.outcome("GET", "/public/x", "T-unmapped") shouldBe "200 null"
        server.outcome("GET", "/internal/v10/x", "T-unmapped") shouldBe "200 null"
        for (path in listOf("/internal/v1/a", "/internal/v1/c", "/public/x")) {
            server.outcome("GET", path, null).substringBefore(' ') shouldBe "401"
            server.outcome("GET", path, "T-bad").substringBefore(' ') shouldBe "401"
        }
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 an empty callers list refuses every named edge and keeps any`() {
        val empty = start(FIXTURE, callers = emptyMap())
        empty.outcome("GET", "/internal/v1/a", "T-cp") shouldBe "403 SRT-004"
        empty.outcome("GET", "/internal/v1/f", "T-canonical") shouldBe "403 SRT-004"
        empty.outcome("GET", "/internal/v1/c", "T-cp") shouldBe "200 null"
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 an absent resource refuses every internal path and touches no other`() {
        val bare = start(null)
        bare.outcome("GET", "/internal/v1/a", "T-cp") shouldBe "403 SRT-004"
        bare.outcome("GET", "/internal/v1/c", "T-cp") shouldBe "403 SRT-004"
        bare.outcome("GET", "/other", "T-cp") shouldBe "200 control-plane-svc"
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 40 alternating requests on one worker thread never report the request before`() {
        val seen =
            List(40) {
                val token = if (it % 2 == 0) "T-cp" else "T-ingestion"
                server.send("GET", "/internal/v1/c", token).body()
            }
        seen shouldContainExactly List(40) { if (it % 2 == 0) "control-plane-svc" else "ingestion-svc" }
        server.send("GET", "/internal/v1/c", "T-unmapped").body() shouldBe "null"
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 the refusal body names no subject or token`() {
        val body = server.send("GET", "/internal/v1/a", "T-other").body()
        body shouldNotContain "T-other"
        body shouldNotContain CP_SUBJECT
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 the sync-edge filter is registered between the workload and the deadline filters`() {
        val context =
            SpringApplicationBuilder(
                OrderStubs::class.java,
                IdentityAutoConfiguration::class.java,
                ResilienceAutoConfiguration::class.java,
            ).web(WebApplicationType.NONE).properties(settings(emptyMap())).run()
        running += context
        val orders = context.getBeansOfType(FilterRegistrationBean::class.java).mapValues { it.value.order }
        IdentityAutoConfiguration.ORDER_SYNC_EDGE_CALLER shouldBe -85
        orders["syncEdgeCallerFilter"] shouldBe -85
        orders["workloadTokenAuthenticationFilter"] shouldBe -90
        orders["deadlineServerFilter"] shouldBe -80
    }

    @Test
    fun `INV-AC-8251 a pre-set attribute is removed and an unmapped token reports null`() {
        val filter = WorkloadTokenAuthenticationFilter({ WorkloadVerification.Verified("unmapped") }, listOf())
        val request =
            MockHttpServletRequest().apply {
                addHeader(WorkloadToken.HEADER_NAME, "x")
                setAttribute(ServiceCaller.REQUEST_ATTRIBUTE, "control-plane-svc")
            }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        try {
            filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> })
            RequestAttributeServiceCaller().current() shouldBe null
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }

    @Test
    fun `INV-AC-8251 the caller is null outside a request and the refused path keeps 401`() {
        RequestAttributeServiceCaller().current() shouldBe null
        val request =
            MockHttpServletRequest().apply {
                setAttribute(
                    ServiceCaller.REQUEST_ATTRIBUTE,
                    "control-plane-svc",
                )
            }
        val response = MockHttpServletResponse()
        var reached = false
        WorkloadTokenAuthenticationFilter({ WorkloadVerification.Refused("no") }, listOf(WorkloadCaller("a-svc", "s")))
            .doFilter(request, response, FilterChain { _, _ -> reached = true })
        reached shouldBe false
        response.status shouldBe 401
        request.getAttribute(ServiceCaller.REQUEST_ATTRIBUTE) shouldBe null
    }

    @Test
    fun `INV-AC-8251 the properties stop the start-up on a malformed caller entry`() {
        fun props(vararg callers: WorkloadCaller) =
            WorkloadIdentityProperties(audience = "a", callers = callers.toList())
        WorkloadIdentityProperties(audience = "a").also { it.afterPropertiesSet() }
        props(WorkloadCaller("control-plane-svc", "s1")).afterPropertiesSet()
        listOf(
            props(WorkloadCaller("Control-Plane-Svc", "s")),
            props(WorkloadCaller("control_plane_svc", "s")),
            props(WorkloadCaller("a".repeat(64), "s")),
            props(WorkloadCaller("", "s")),
            props(WorkloadCaller("a-svc\n", "s")),
            props(WorkloadCaller("a-svc", "")),
            props(WorkloadCaller("a-svc", "  ")),
            props(WorkloadCaller("a-svc", "unknown")),
            props(WorkloadCaller("a-svc", "s1"), WorkloadCaller("a-svc", "s2")),
            props(WorkloadCaller("a-svc", "s1"), WorkloadCaller("b-svc", "s1")),
        ).forEach { shouldThrow<IllegalStateException> { it.afterPropertiesSet() } }
        props(WorkloadCaller("a".repeat(63), "s")).afterPropertiesSet()
    }

    @Test
    fun `INV-AC-8251 the callers bind from the environment variables`() {
        val env =
            SystemEnvironmentPropertySource(
                "systemEnvironment",
                mapOf(
                    "SERVICE_RUNTIME_WORKLOAD_IDENTITY_AUDIENCE" to "probe-svc",
                    "SERVICE_RUNTIME_WORKLOAD_IDENTITY_CALLERS_0_SERVICE" to "control-plane-svc",
                    "SERVICE_RUNTIME_WORKLOAD_IDENTITY_CALLERS_0_SUBJECT" to CP_SUBJECT,
                    "SERVICE_RUNTIME_WORKLOAD_IDENTITY_CALLERS_1_SERVICE" to "payload-svc",
                    "SERVICE_RUNTIME_WORKLOAD_IDENTITY_CALLERS_1_SUBJECT" to "s2",
                ),
            )
        val bound =
            Binder(ConfigurationPropertySources.from(env).filterNotNull())
                .bind("service-runtime.workload-identity", WorkloadIdentityProperties::class.java)
                .get()
        bound.callers shouldBe
            listOf(WorkloadCaller("control-plane-svc", CP_SUBJECT), WorkloadCaller("payload-svc", "s2"))
    }

    private val overlapping =
        "GET\t/internal/v1/g/{id}\tpayload-svc\n" + "GET\t/internal/v1/g/special\tcanonical-svc\n" +
            "GET\t/internal/v1/g/{id}/x\tany\n" + "GET\t/internal/v1/h/{a}\tpayload-svc\n" +
            "GET\t/internal/v1/h/{b}\tpayload-svc\n"

    @Test
    fun `INV-AC-8251 INV-AC-8455 the serving operation decides by its own line, whatever the order of the lines`() {
        val reversed =
            overlapping
                .lines()
                .filter { it.isNotEmpty() }
                .reversed()
                .joinToString("\n") + "\n"
        for (text in listOf(overlapping, reversed)) {
            val s = start(text)
            s.outcome("GET", "/internal/v1/g/special", "T-canonical") shouldBe "200 canonical-svc"
            s.outcome("GET", "/internal/v1/g/special", "T-payload") shouldBe "403 SRT-004"
            s.outcome("GET", "/internal/v1/g/7", "T-payload") shouldBe "200 payload-svc"
            s.outcome("GET", "/internal/v1/g/7", "T-canonical") shouldBe "403 SRT-004"
            s.outcome("GET", "/internal/v1/g/special/x", "T-cp") shouldBe "200 control-plane-svc"
            s.outcome("GET", "/internal/v1/h/1", "T-payload") shouldBe "200 payload-svc"
            s.outcome("GET", "/internal/v1/h/1", "T-canonical") shouldBe "403 SRT-004"
        }
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 conflicting or equally specific lines stop the start-up`() {
        val conflicting =
            listOf(
                listOf(
                    "GET\t/internal/v1/h/{a}\tpayload-svc",
                    "GET\t/internal/v1/h/{b}\tcanonical-svc",
                ),
                listOf("GET\t/internal/v1/h/{a}\tpayload-svc", "GET\t/internal/v1/h/{a}\tany"),
                listOf("GET\t/internal/v1/m/{a}/x\tany", "GET\t/internal/v1/m/y/{b}\tany"),
                listOf("GET\t/internal/v1/m/y/{b}\tany", "GET\t/internal/v1/m/{a}/x\tany"),
            )
        for (lines in conflicting) shouldThrow<IllegalStateException> { SyncEdgeCallerFilter.of(lines) }
        SyncEdgeCallerFilter.of(
            listOf(
                "GET\t/internal/v1/n/{a}/{b}\tany",
                "GET\t/internal/v1/n/x/{b}\tany",
                "GET\t/internal/v1/n/x/y\tany",
                "POST\t/internal/v1/n/{a}/{b}\tany",
                "GET\t/internal/v1/h/{a}\tpayload-svc,canonical-svc",
                "GET\t/internal/v1/h/{b}\tcanonical-svc,payload-svc",
            ),
        )
    }

    @Test
    fun `INV-AC-8251 INV-AC-8455 identical lines of two resources are one, different ones stop the start-up`() {
        val two = Files.createTempDirectory(dir, "two")
        Files.createDirectories(two.resolve("META-INF/gst"))
        Files.writeString(two.resolve(SyncEdgeCallerFilter.RESOURCE), "GET\t/internal/v1/a\tcontrol-plane-svc\n")
        val loader = URLClassLoader(arrayOf(two.toUri().toURL(), two.toUri().toURL()), null)
        loader.use { SyncEdgeCallerFilter.from(it) }
        val other = Files.createTempDirectory(dir, "other")
        Files.createDirectories(other.resolve("META-INF/gst"))
        Files.writeString(other.resolve(SyncEdgeCallerFilter.RESOURCE), "GET\t/internal/v1/a\tpayload-svc\n")
        URLClassLoader(arrayOf(two.toUri().toURL(), other.toUri().toURL()), null).use {
            shouldThrow<IllegalStateException> { SyncEdgeCallerFilter.from(it) }
        }
    }

    @Test
    fun `INV-AC-8455 a malformed line of the resource stops the start-up`() {
        val malformed =
            listOf(
                "GET\t/internal/v1/a",
                "GET\t/internal/v1/a\tx-svc\textra",
                "\t/internal/v1/a\tx-svc",
                "GET\tinternal/v1/a\tx-svc",
                "GET\t/internal/v1/a\t",
                "GET\t/internal/v1/a\tx-svc,",
                "GET\t/internal/v1/a\tx-svc,x-svc",
                "GET\t/internal/v1/a\tany,x-svc",
                "GET\t/internal/v1/a\tx-svc,any",
                "GET\t/internal/v1/a\tany,any",
                "GET\t/internal/v1/a\tany-except:",
                "GET\t/internal/v1/a\tany-except:X",
                "GET\t/internal/v1/a\tany-except:x-svc,y-svc",
                "GET\t/internal/v1/a\tx-svc,any-except:y-svc",
                "GET\t/internal/v1/a\tX-svc",
                "GET\t/internal/v1/a\tx_svc",
                "GET\t/internal/v1/a\t${"a".repeat(64)}",
                "GET\t/internal/v1/a\tx-svc\n\nGET\t/internal/v1/b\tx-svc",
            )
        for (text in malformed) {
            shouldThrow<IllegalStateException> { SyncEdgeCallerFilter.of(text.split('\n')) }
        }
        shouldThrow<Exception> { start(malformed[1] + "\n") }
        SyncEdgeCallerFilter.of(FIXTURE.removeSuffix("\n").split('\n'))
    }
}
