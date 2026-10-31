package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionView
import com.dynokore.platform.serviceruntime.resilience.ResilienceAutoConfiguration
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.Filter
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
import org.springframework.core.io.DefaultResourceLoader
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private val SETTINGS = mapOf<String, Any>("service-runtime.workload-identity.audience" to "probe-svc")
private const val KEYS = "GET\t/internal/v1/probe/keys"
private const val SIGNED = "POST\t/internal/v1/probe/{id}/verify\tsigned-link"
private const val JOB = "POST\t/internal/v1/probe/close\tservice-job"

/** What the probe handler and the outermost filter saw. */
internal object Probe {
    val handled = AtomicInteger()
    val resolved = AtomicInteger()
    val after = CopyOnWriteArrayList<String>()
    val threads = CopyOnWriteArrayList<String>()
    var throwInHandler = false
}

private val TENANT_A = TenantId.of("0a000000-0000-0000-0000-00000000000a")
private val TENANT_B = TenantId.of("0b000000-0000-0000-0000-00000000000b")

@Configuration(proxyBeanMethods = false)
@Import(
    TomcatServletWebServerAutoConfiguration::class,
    EndUserIdentityAutoConfiguration::class,
)
internal class ProbeConfig {
    @Bean fun breakGlassSessionView(): BreakGlassSessionView = BreakGlassSessionView { _, _, _ -> null }

    @Bean fun tenantContext(): TenantContextImpl = TenantContextImpl()
}

/** For the order test: the four filters of the identity and resilience configurations, listed before them. */
@Configuration(proxyBeanMethods = false)
internal class OrderStubs {
    @Bean fun breakGlassSessionView(): BreakGlassSessionView = BreakGlassSessionView { _, _, _ -> null }

    @Bean fun clock(): java.time.Clock = java.time.Clock.systemUTC()

    @Bean fun workloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier {
            WorkloadVerification.Verified("svc:caller")
        }

    @Bean fun tenantContext(): TenantContextImpl = TenantContextImpl()
}

@Configuration(proxyBeanMethods = false)
internal class ResolverConfig {
    @Bean
    fun resolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { header ->
            Probe.resolved.incrementAndGet()
            when (header) {
                "Bearer a" -> {
                    EndUserPrincipal("user-a", TENANT_A, emptySet())
                }

                "Bearer b" -> {
                    EndUserPrincipal("user-b", TENANT_B, emptySet())
                }

                "Bearer op" -> {
                    EndUserPrincipal("operator-1", null, emptySet())
                }

                "Bearer session" -> {
                    EndUserPrincipal(
                        "operator-2",
                        TENANT_A,
                        emptySet(),
                        operatorSession = OperatorSession("support", "case-1"),
                    )
                }

                "Bearer down", "Basic down" -> {
                    throw TokenIssuerUnavailableException()
                }

                else -> {
                    null
                }
            }
        }
}

@Configuration(proxyBeanMethods = false)
internal class ServletConfig {
    @Bean
    fun probe(tenants: TenantContextImpl): ServletRegistrationBean<HttpServlet> =
        ServletRegistrationBean(ProbeServlet(tenants), "/*")

    /** Outermost (before every filter of the library): what is bound once the response is done. */
    @Bean
    fun outermost(tenants: TenantContextImpl): FilterRegistrationBean<Filter> =
        FilterRegistrationBean<Filter>(
            Filter { request, response, chain ->
                try {
                    chain.doFilter(request, response)
                } finally {
                    Probe.threads += Thread.currentThread().name
                    Probe.after += unbound(tenants)
                }
            },
        ).apply { order = -1000 }
}

private fun unbound(tenants: TenantContext): String =
    try {
        tenants.current().toString()
    } catch (_: IllegalStateException) {
        "none"
    }

internal class ProbeServlet(
    private val tenants: TenantContextImpl,
) : HttpServlet() {
    override fun service(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        Probe.handled.incrementAndGet()
        if (Probe.throwInHandler) error("handler failed")
        val bound = unbound(tenants)
        val actor = if (bound == "none") "none" else tenants.currentActor()
        val platform =
            if (request.getParameter("platform") != null) {
                (tenants as PlatformTenantBinding).bindPlatform("operator-1") { tenants.current().toString() }
            } else {
                ""
            }
        val nested =
            if (request.getParameter("nested") != null && bound != "none") {
                tenants.bind(tenants.current(), "inner") { tenants.currentActor() } + "/" + tenants.currentActor()
            } else {
                ""
            }
        response.writer.write("$bound|$actor|$platform|$nested")
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndUserPrincipalBindingFilterTest {
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

    private fun resourceRoot(files: Map<String, String>): Path {
        val root = Files.createTempDirectory(dir, "cp")
        files.forEach { (name, text) ->
            Files.createDirectories(root.resolve(name).parent)
            Files.writeString(root.resolve(name), text)
        }
        return root
    }

    /** One worker thread, so a binding left on it would show in the next request. */
    private fun start(
        files: Map<String, String>,
        vararg extra: Path,
    ): Server {
        val roots = listOf(resourceRoot(files)) + extra
        val loader = URLClassLoader(roots.map { it.toUri().toURL() }.toTypedArray(), javaClass.classLoader)
        loaders += loader
        val context =
            SpringApplicationBuilder(ProbeConfig::class.java, ServletConfig::class.java, ResolverConfig::class.java)
                .web(WebApplicationType.SERVLET)
                .resourceLoader(DefaultResourceLoader(loader))
                .properties(SETTINGS)
                .run("--server.port=0", "--server.tomcat.threads.max=1", "--server.tomcat.threads.min-spare=1")
        running += context
        return Server(context, checkNotNull((context as WebServerApplicationContext).webServer).port)
    }

    private val workload = mapOf("META-INF/gst/workload-only-operations.tsv" to "$KEYS\n")
    private val callerAuth = mapOf("META-INF/gst/caller-auth-operations.tsv" to "$SIGNED\n$JOB\n")
    private val both = workload + callerAuth

    private val server by lazy { start(both) }

    private fun Server.send(
        method: String,
        path: String,
        token: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(
                    URI.create("http://localhost:$port$path"),
                ).method(method, HttpRequest.BodyPublishers.noBody())
        token?.let { request.header("Authorization", it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun HttpResponse<String>.code(): String = mapper.readTree(body())["code"].asString()

    @AfterAll
    fun stop() {
        running.forEach(ConfigurableApplicationContext::close)
        loaders.forEach(URLClassLoader::close)
    }

    private fun reset() {
        Probe.handled.set(0)
        Probe.resolved.set(0)
        Probe.after.clear()
        Probe.throwInHandler = false
    }

    @Test
    fun `INV-AC-8196 tenant principals are bound for the request and nothing stays on the thread`() {
        reset()
        server.send("GET", "/internal/v1/probe/a", "Bearer a").body() shouldBe "$TENANT_A|user-a||"
        server.send("GET", "/internal/v1/probe/a", "Bearer b").body() shouldBe "$TENANT_B|user-b||"
        Probe.after.toList() shouldContainExactly listOf("none", "none")
    }

    @Test
    fun `INV-AC-8196 40 alternating requests on one worker thread never see the request before`() {
        reset()
        Probe.threads.clear()
        repeat(40) {
            val (token, tenant, actor) =
                if (it % 2 ==
                    0
                ) {
                    Triple("Bearer a", TENANT_A, "user-a")
                } else {
                    Triple("Bearer b", TENANT_B, "user-b")
                }
            server.send("GET", "/internal/v1/probe/a", token).body() shouldBe "$tenant|$actor||"
        }
        Probe.after.toList() shouldContainExactly List(40) { "none" }
        Probe.threads.toSet().size shouldBe 1
    }

    @Test
    fun `INV-AC-8196 a tenant-scoped operator session is bound as a tenant principal with the operator as actor`() {
        server.send("GET", "/internal/v1/probe/a", "Bearer session").body() shouldBe "$TENANT_A|operator-2||"
    }

    @Test
    fun `INV-AC-8196 a handler exception still clears the binding`() {
        reset()
        Probe.throwInHandler = true
        try {
            server.send("GET", "/internal/v1/probe/a", "Bearer a").statusCode() shouldBe 500
            Probe.after.toList() shouldContainExactly listOf("none")
        } finally {
            reset()
        }
        server.send("GET", "/internal/v1/probe/a", "Bearer b").body() shouldBe "$TENANT_B|user-b||"
    }

    @Test
    fun `INV-AC-8196 a nested binding under the filter succeeds`() {
        server.send("GET", "/internal/v1/probe/a?nested=1", "Bearer a").body() shouldBe "$TENANT_A|user-a||inner/user-a"
    }

    @Test
    fun `INV-AC-8196 a platform operator reaches the handler with nothing bound and the platform adapter binds`() {
        reset()
        server.send("GET", "/internal/v1/probe/a", "Bearer op").let {
            it.statusCode() shouldBe 200
            it.body() shouldBe "none|none||"
        }
        server.send("GET", "/internal/v1/probe/a?platform=1", "Bearer op").body() shouldBe
            "none|none|${TenantId.PLATFORM}|"
    }

    @Test
    fun `INV-AC-8196 refusals answer 401 SRT-003 or 503 SRT-011 and the handler is not called`() {
        for (
        (token, status, code) in
        listOf(
            Triple(null, 401, "SRT-003"),
            Triple("Bearer unknown", 401, "SRT-003"),
            Triple("Basic unknown", 401, "SRT-003"),
            Triple("Bearer down", 503, "SRT-011"),
        )
        ) {
            reset()
            server.send("GET", "/internal/v1/probe/a", token).let {
                it.statusCode() shouldBe status
                it.code() shouldBe code
            }
            Probe.handled.get() shouldBe 0
            Probe.after.toList() shouldContainExactly listOf("none")
        }
    }

    @Test
    fun `INV-AC-8196 no resolver bean gives 401 on end-user operations and workload-only ones still pass`() {
        val context =
            SpringApplicationBuilder(ProbeConfig::class.java, ServletConfig::class.java)
                .web(WebApplicationType.SERVLET)
                .resourceLoader(
                    DefaultResourceLoader(
                        URLClassLoader(arrayOf(resourceRoot(both).toUri().toURL()), javaClass.classLoader),
                    ),
                ).properties(SETTINGS)
                .run("--server.port=0")
        running += context
        val bare = Server(context, checkNotNull((context as WebServerApplicationContext).webServer).port)
        reset()
        bare.send("GET", "/internal/v1/probe/a", "Bearer a").let {
            it.statusCode() shouldBe 401
            it.code() shouldBe "SRT-003"
        }
        Probe.handled.get() shouldBe 0
        bare.send("GET", "/internal/v1/probe/keys").statusCode() shouldBe 200
        Probe.handled.get() shouldBe 1
    }

    @Test
    fun `INV-AC-8196 the filter is registered at the new order after the three earlier ones`() {
        val context =
            SpringApplicationBuilder(
                OrderStubs::class.java,
                IdentityAutoConfiguration::class.java,
                ResilienceAutoConfiguration::class.java,
                EndUserIdentityAutoConfiguration::class.java,
            ).web(WebApplicationType.NONE).properties(SETTINGS).run()
        running += context
        val beans = context.getBeansOfType(FilterRegistrationBean::class.java)
        val orders = beans.mapValues { it.value.order }
        orders["endUserPrincipalBindingFilter"] shouldBe -50
        orders["operatorSessionBindingFilter"] shouldBe -60
        (orders["endUserPrincipalBindingFilter"]!! > orders["operatorSessionBindingFilter"]!!) shouldBe true
        beans["endUserPrincipalBindingFilter"]!!.urlPatterns.toList() shouldBe listOf("/*")
        orders["endUserTokenCaptureFilter"] shouldBe -70
        orders["workloadTokenAuthenticationFilter"] shouldBe -90
        (orders["endUserPrincipalBindingFilter"]!! > orders["endUserTokenCaptureFilter"]!!) shouldBe true
        (orders["endUserPrincipalBindingFilter"]!! > orders["workloadTokenAuthenticationFilter"]!!) shouldBe true
    }

    @Test
    fun `INV-AC-8196 untouched operations reach the handler with no resolver call and nothing bound`() {
        for (token in listOf(null, "Bearer a")) {
            for ((method, path) in listOf(
                "GET" to "/internal/v1/probe/keys",
                "POST" to "/internal/v1/probe/x1/verify",
                "POST" to "/internal/v1/probe/close",
            )) {
                reset()
                server.send(method, path, token).let {
                    it.statusCode() shouldBe 200
                    it.body() shouldBe "none|none||"
                }
                Probe.handled.get() shouldBe 1
                Probe.resolved.get() shouldBe 0
            }
        }
    }

    @Test
    fun `INV-AC-8196 an unlisted operation is an end-user operation`() {
        val listed =
            start(
                mapOf(
                    "META-INF/gst/workload-only-operations.tsv" to
                        "GET\t/internal/v1/probe/keys\nPOST\t/internal/v1/probe/{id}/x\n",
                ),
            )
        for (
        (method, path) in
        listOf(
            "POST" to "/internal/v1/probe/keys",
            "GET" to "/internal/v1/probe/keys/",
            "GET" to "/internal/v1/probe/keys/extra",
            "GET" to "/internal/v1/probe/other",
            "POST" to "/internal/v1/probe/a/b/x",
            "POST" to "/internal/v1/probe//x",
            "get" to "/internal/v1/probe/keys",
        )
        ) {
            reset()
            listed.send(method, path).let {
                it.statusCode() shouldBe 401
                it.code() shouldBe "SRT-003"
            }
            Probe.handled.get() shouldBe 0
        }
        listed.send("POST", "/internal/v1/probe/q/x").statusCode() shouldBe 200
        listed.send("GET", "/internal/v1/probe/keys").statusCode() shouldBe 200
    }

    @Test
    fun `INV-AC-8196 look-alike resource names are not read`() {
        for (
        name in
        listOf(
            "META-INF/gst/workload-only-operations.txt",
            "META-INF/workload-only-operations.tsv",
            "META-INF/gst/caller-auth-operations.txt",
            "META-INF/caller-auth-operations.tsv",
        )
        ) {
            val other = start(mapOf(name to "$KEYS\t\nPOST\t/internal/v1/probe/close\n$JOB\n"))
            other.send("GET", "/internal/v1/probe/keys").statusCode() shouldBe 401
            other.send("POST", "/internal/v1/probe/close").statusCode() shouldBe 401
        }
        // no resource at all: every operation is an end-user operation
        start(emptyMap()).send("GET", "/internal/v1/probe/keys").statusCode() shouldBe 401
    }

    @Test
    fun `INV-AC-8196 a caller-auth line does not make another method untouched`() {
        val only =
            start(
                mapOf("META-INF/gst/caller-auth-operations.tsv" to "GET\t/internal/v1/probe/keys\tsigned-link\n"),
            )
        only.send("GET", "/internal/v1/probe/keys").statusCode() shouldBe 200
        only.send("POST", "/internal/v1/probe/keys").let {
            it.statusCode() shouldBe 401
            it.code() shouldBe "SRT-003"
        }
    }

    @Test
    fun `INV-AC-8196 two resources of the same name are merged`() {
        val second =
            resourceRoot(
                mapOf("META-INF/gst/workload-only-operations.tsv" to "GET\t/internal/v1/probe/second\n"),
            )
        val merged = start(workload, second)
        merged.send("GET", "/internal/v1/probe/keys").statusCode() shouldBe 200
        merged.send("GET", "/internal/v1/probe/second").statusCode() shouldBe 200
    }

    @Test
    fun `INV-AC-8196 a malformed line stops the start-up`() {
        val workloadFile = "META-INF/gst/workload-only-operations.tsv"
        val callerFile = "META-INF/gst/caller-auth-operations.tsv"
        val malformed =
            listOf(
                workloadFile to "GET\n",
                workloadFile to "GET\t/a\textra\n",
                workloadFile to "\t/a\n",
                workloadFile to "GET\ta\n",
                workloadFile to "GET\t/a\n\nGET\t/b\n",
                callerFile to "GET\t/a\tend-user\n",
                callerFile to "GET\t/a\tSigned-Link\n",
                callerFile to "GET\t/a\t\n",
                callerFile to "GET\t/a\n",
                callerFile to "GET\t/a\tsigned-link\tx\n",
                callerFile to "GET\t/a\tsigned-link\nPOST\t/b\tnope\n",
            )
        for ((file, text) in malformed) {
            val failure = shouldThrow<Exception> { start(mapOf(file to text)) }
            generateSequence<Throwable>(failure) { it.cause }.last().message.orEmpty() shouldContain "$file"
        }
    }

    @Test
    fun `INV-AC-8196 the workload-only and caller-auth formats parse as written`() {
        val ops = UntouchedOperations.from(URLClassLoader(arrayOf(resourceRoot(both).toUri().toURL())))
        ops.matches("GET", "/internal/v1/probe/keys") shouldBe true
        ops.matches("POST", "/internal/v1/probe/7/verify") shouldBe true
        ops.matches("POST", "/internal/v1/probe/verify") shouldBe false
    }
}
