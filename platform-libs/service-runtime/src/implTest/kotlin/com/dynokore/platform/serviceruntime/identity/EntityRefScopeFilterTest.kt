package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionView
import com.dynokore.platform.serviceruntime.resilience.ResilienceAutoConfiguration
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

private val ER_TENANT = TenantId.of("0a000000-0000-0000-0000-00000000000a")
private val ER_SETTINGS = mapOf<String, Any>("service-runtime.workload-identity.audience" to "probe-svc")
private const val ER_LINES = "POST\t/internal/v1/a/{id}\tentityRef\nPOST\t/internal/v1/b\ttarget\n"

/** What the handler of the entity-ref probe saw. */
internal object ErProbe {
    val handled = AtomicInteger()
    val bodies = CopyOnWriteArrayList<ByteArray>()
    val principals = CopyOnWriteArrayList<String>()
}

@Configuration(proxyBeanMethods = false)
@Import(TomcatServletWebServerAutoConfiguration::class, EndUserIdentityAutoConfiguration::class)
internal class ErConfig {
    @Bean fun breakGlassSessionView(): BreakGlassSessionView = BreakGlassSessionView { _, _, _ -> null }

    @Bean fun tenantContext(): TenantContextImpl = TenantContextImpl()

    @Bean
    fun resolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { header ->
            when (header) {
                "Bearer T-E1" -> {
                    EndUserPrincipal("user-1", ER_TENANT, setOf(EntityRef("E-001"), EntityRef("E-100")))
                }

                "Bearer T-E2" -> {
                    EndUserPrincipal("user-2", ER_TENANT, setOf(EntityRef("E-002")))
                }

                "Bearer T-STALE" -> {
                    EndUserPrincipal("user-1", ER_TENANT, setOf(EntityRef("E-001")), entitySetCurrent = false)
                }

                "Bearer T-OP" -> {
                    EndUserPrincipal("operator-1", null, emptySet())
                }

                else -> {
                    null
                }
            }
        }

    /** Outermost: a stub that puts a principal covering E-002 into the attribute before the binding filter runs. */
    @Bean
    fun stubAttribute(): FilterRegistrationBean<jakarta.servlet.Filter> =
        FilterRegistrationBean<jakarta.servlet.Filter>(
            jakarta.servlet.Filter { request, response, chain ->
                request.setAttribute(
                    EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE,
                    EndUserPrincipal("stub", ER_TENANT, setOf(EntityRef("E-002"))),
                )
                chain.doFilter(request, response)
            },
        ).apply { order = -1000 }

    @Bean
    fun probe(): ServletRegistrationBean<HttpServlet> =
        ServletRegistrationBean(
            object : HttpServlet() {
                override fun service(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                ) {
                    ErProbe.handled.incrementAndGet()
                    ErProbe.bodies += request.inputStream.readAllBytes()
                    ErProbe.principals +=
                        (request.getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal)
                            ?.actor ?: "none"
                    response.writer.write("ok")
                }
            },
            "/*",
        )
}

@Configuration(proxyBeanMethods = false)
internal class ErOrderStubs {
    @Bean fun breakGlassSessionView(): BreakGlassSessionView = BreakGlassSessionView { _, _, _ -> null }

    @Bean fun clock(): java.time.Clock = java.time.Clock.systemUTC()

    @Bean fun workloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { WorkloadVerification.Verified("svc:caller") }

    @Bean fun tenantContext(): TenantContextImpl = TenantContextImpl()
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityRefScopeFilterTest {
    @TempDir
    lateinit var dir: Path

    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private val running = mutableListOf<ConfigurableApplicationContext>()
    private val loaders = mutableListOf<URLClassLoader>()

    private class Server(
        val port: Int,
    )

    private fun loader(files: Map<String, String>): URLClassLoader {
        val root = Files.createTempDirectory(dir, "cp")
        files.forEach { (name, text) ->
            Files.createDirectories(root.resolve(name).parent)
            Files.writeString(root.resolve(name), text)
        }
        return URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).also { loaders += it }
    }

    private fun start(files: Map<String, String>): Server {
        val context =
            SpringApplicationBuilder(ErConfig::class.java)
                .web(WebApplicationType.SERVLET)
                .resourceLoader(DefaultResourceLoader(loader(files)))
                .properties(ER_SETTINGS)
                .run("--server.port=0", "--server.tomcat.threads.max=1", "--server.tomcat.threads.min-spare=1")
        running += context
        return Server(checkNotNull((context as WebServerApplicationContext).webServer).port)
    }

    private val server by lazy {
        start(mapOf("META-INF/gst/entity-ref-operations.tsv" to ER_LINES))
    }

    @AfterAll
    fun stop() {
        running.forEach(ConfigurableApplicationContext::close)
        loaders.forEach(URLClassLoader::close)
    }

    private fun post(
        path: String,
        body: String,
        token: String? = "Bearer T-E1",
        on: Server = server,
    ) = postBytes(path, body.toByteArray(Charsets.UTF_8), token, on = on)

    private fun postBytes(
        path: String,
        body: ByteArray,
        token: String? = "Bearer T-E1",
        extra: Map<String, String> = emptyMap(),
        on: Server = server,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${on.port}$path"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        token?.let { request.header("Authorization", it) }
        extra.forEach { (k, v) -> request.header(k, v) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun reset() {
        ErProbe.handled.set(0)
        ErProbe.bodies.clear()
        ErProbe.principals.clear()
    }

    private fun HttpResponse<String>.code(): String = mapper.readTree(body())["code"].asString()

    private fun expect(
        response: HttpResponse<String>,
        status: Int,
        code: String?,
        handled: Int,
    ) {
        response.statusCode() shouldBe status
        if (code != null) response.code() shouldBe code
        ErProbe.handled.get() shouldBe handled
    }

    @Test
    fun `INV-AC-8515 an entity in the set reaches the handler with the identical body bytes`() {
        reset()
        val body = """{ "entityRef" : "E-001", "n": [1, {"x": null}] }"""
        expect(post("/internal/v1/a/7", body), 200, null, 1)
        String(ErProbe.bodies.single(), Charsets.UTF_8) shouldBe body
        reset()
        expect(post("/internal/v1/a/7", """{"entityRef":"E-100"}"""), 200, null, 1)
    }

    @Test
    fun `INV-AC-8515 an entity outside the set is 403 SRT-004 with the same body whether or not it exists`() {
        val outside =
            listOf("E-002", "E-0010", "e-001", "E-001 ", "", "x".repeat(64), "E-00", "E-0011", " ", "E-001\n")
        val bodies = mutableSetOf<String>()
        for (value in outside) {
            reset()
            val answer = post("/internal/v1/a/7", """{"entityRef":${mapper.writeValueAsString(value)}}""")
            expect(answer, 403, "SRT-004", 0)
            bodies += answer.body().replace(Regex("\"(correlationId|instance|traceId)\"\\s*:\\s*\"[^\"]*\""), "")
        }
        bodies.size shouldBe 1
    }

    @Test
    fun `INV-AC-8515 a stale entity set and an operator token are 403 SRT-004`() {
        reset()
        expect(post("/internal/v1/a/7", """{"entityRef":"E-001"}""", "Bearer T-STALE"), 403, "SRT-004", 0)
        expect(post("/internal/v1/a/7", """{"entityRef":"E-001"}""", "Bearer T-OP"), 403, "SRT-004", 0)
    }

    @Test
    fun `INV-AC-8515 the property absent continues to the handler`() {
        reset()
        expect(post("/internal/v1/a/7", "{}"), 200, null, 1)
        expect(post("/internal/v1/a/7", """{"other":"E-002"}"""), 200, null, 2)
    }

    @Test
    fun `INV-AC-8515 a body that is not one object with a single string property is 400 SRT-001`() {
        val bad =
            listOf(
                """{"entityRef":1}""",
                """{"entityRef":true}""",
                """{"entityRef":null}""",
                """{"entityRef":{"a":1}}""",
                """{"entityRef":"E-001","entityRef":"E-002"}""",
                """{"entityRef":"E-002","entityRef":"E-001"}""",
                "[]",
                "\"E-001\"",
                "",
                "{}{}",
                """{"entityRef":"E-001"} x""",
                """{"entityRef":"E-001""",
            )
        for (body in bad) {
            reset()
            expect(post("/internal/v1/a/7", body), 400, "SRT-001", 0)
        }
        reset()
        expect(postBytes("/internal/v1/a/7", byteArrayOf(0x7b, 0x22, 0xff.toByte(), 0x22, 0x7d)), 400, "SRT-001", 0)
        val split = "{\"entityRef\":\"E-001\",\"x\":\"".toByteArray() + byteArrayOf(0xc3.toByte()) + "\"}".toByteArray()
        expect(postBytes("/internal/v1/a/7", split), 400, "SRT-001", 0)
    }

    @Test
    fun `INV-AC-9629 an array on a line naming entityRef is judged element by element`() {
        reset()
        expect(post("/internal/v1/a/7", """{"entityRef":["E-001"]}"""), 200, null, 1)
        expect(post("/internal/v1/a/7", """{"entityRef":["E-002"]}"""), 403, "SRT-004", 1)
        expect(post("/internal/v1/a/7", """{"entityRef":["E-001","E-002"]}"""), 403, "SRT-004", 1)
        expect(post("/internal/v1/a/7", """{"entityRef":["E-001",1]}"""), 400, "SRT-001", 1)
    }

    @Test
    fun `INV-AC-8515 a body over 1048576 bytes is 400 SRT-001 and one at the limit is read`() {
        reset()
        val pad = """{"entityRef":"E-001","p":""""
        val over = pad + "a".repeat(1048577 - pad.length - 2) + "\"}"
        over.length shouldBe 1048577
        expect(post("/internal/v1/a/7", over), 400, "SRT-001", 0)
        val atLimit = pad + "a".repeat(1048576 - pad.length - 2) + "\"}"
        atLimit.length shouldBe 1048576
        expect(post("/internal/v1/a/7", atLimit), 200, null, 1)
    }

    @Test
    fun `INV-AC-8515 a look-alike property in another case is never read`() {
        reset()
        expect(post("/internal/v1/a/7", """{"entityref":"E-002","entityRef":"E-001"}"""), 200, null, 1)
        expect(post("/internal/v1/a/7", """{"entityref":"E-001","entityRef":"E-002"}"""), 403, "SRT-004", 1)
        expect(post("/internal/v1/a/7", """{"entityref":"E-002"}"""), 200, null, 2)
    }

    @Test
    fun `INV-AC-8515 each line judges the property it names`() {
        reset()
        expect(post("/internal/v1/b", """{"target":"E-002","entityRef":"E-001"}"""), 403, "SRT-004", 0)
        expect(post("/internal/v1/b", """{"target":"E-001","entityRef":"E-002"}"""), 200, null, 1)
    }

    @Test
    fun `INV-AC-8515 an unmarked operation is not judged`() {
        val unmarked = start(mapOf("META-INF/gst/entity-ref-operations.tsv" to "$ER_LINES"))
        reset()
        val paths =
            listOf("/internal/v1/c", "/internal/v1/other", "/internal/v1/a", "/internal/v1/a/7/x", "/internal/v1/a/")
        for (path in paths) {
            for (body in listOf("""{"entityRef":"E-002"}""", "not json", "")) {
                post(path, body, on = unmarked).statusCode() shouldBe 200
            }
        }
        ErProbe.handled.get() shouldBe 15
        val get =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:${unmarked.port}/internal/v1/b"))
                    .header("Authorization", "Bearer T-E1")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        get.statusCode() shouldBe 200
    }

    @Test
    fun `INV-AC-8515 no end-user token on a marked path is 401 SRT-003`() {
        reset()
        expect(post("/internal/v1/a/7", """{"entityRef":"E-001"}""", null), 401, "SRT-003", 0)
        expect(post("/internal/v1/a/7", """{"entityRef":"E-001"}""", "Bearer unknown"), 401, "SRT-003", 0)
    }

    @Test
    fun `INV-AC-8515 a principal attribute already on the request is replaced by the principal of the token`() {
        reset()
        // the stub filter holds a principal covering E-002; the token T-E1 covers E-001 and E-100 only
        expect(post("/internal/v1/a/7", """{"entityRef":"E-002"}"""), 403, "SRT-004", 0)
        expect(post("/internal/v1/a/7", """{"entityRef":"E-001"}"""), 200, null, 1)
        ErProbe.principals.single() shouldBe "user-1"
        // an operation the binding filter leaves untouched keeps no principal: the stub's is not used
        val untouched =
            start(
                mapOf(
                    "META-INF/gst/entity-ref-operations.tsv" to "POST\t/internal/v1/w\tentityRef\n",
                    "META-INF/gst/workload-only-operations.tsv" to "POST\t/internal/v1/w\n",
                ),
            )
        expect(post("/internal/v1/w", """{"entityRef":"E-002"}""", on = untouched), 401, "SRT-003", 1)
    }

    @Test
    fun `INV-AC-8515 40 alternating requests of two tokens on one worker thread are not judged by the one before`() {
        reset()
        repeat(40) {
            val (token, mine, theirs) =
                if (it % 2 == 0) Triple("Bearer T-E1", "E-001", "E-002") else Triple("Bearer T-E2", "E-002", "E-001")
            expect(post("/internal/v1/a/7", """{"entityRef":"$theirs"}""", token), 403, "SRT-004", 0)
            post("/internal/v1/a/7", """{"entityRef":"$mine"}""", token).statusCode() shouldBe 200
            reset()
        }
    }

    @Test
    fun `INV-AC-8515 the filter is registered at -45 after the binding filter`() {
        val context =
            SpringApplicationBuilder(
                ErOrderStubs::class.java,
                IdentityAutoConfiguration::class.java,
                ResilienceAutoConfiguration::class.java,
                EndUserIdentityAutoConfiguration::class.java,
            ).web(WebApplicationType.NONE).properties(ER_SETTINGS).run()
        running += context
        val beans = context.getBeansOfType(FilterRegistrationBean::class.java)
        beans["entityRefScopeFilter"]!!.order shouldBe EndUserIdentityAutoConfiguration.ORDER_ENTITY_REF_SCOPE
        beans["entityRefScopeFilter"]!!.order shouldBe -45
        beans["endUserPrincipalBindingFilter"]!!.order shouldBe -50
        beans["entityRefScopeFilter"]!!.urlPatterns.toList() shouldBe listOf("/*")
    }

    private fun resolution(files: Map<String, String>) {
        val on = start(files)
        reset()

        fun judge(
            path: String,
            body: String,
            status: Int,
        ) {
            val before = ErProbe.handled.get()
            post(path, body, on = on).statusCode() shouldBe status
            ErProbe.handled.get() shouldBe before + if (status == 200) 1 else 0
        }
        judge("/internal/v1/g/special", """{"target":"E-001","entityRef":"E-002"}""", 200)
        judge("/internal/v1/g/special", """{"target":"E-002","entityRef":"E-001"}""", 403)
        judge("/internal/v1/g/7", """{"entityRef":"E-001","target":"E-002"}""", 200)
        judge("/internal/v1/g/7", """{"entityRef":"E-002","target":"E-001"}""", 403)
    }

    private val templated = "POST\t/internal/v1/g/{id}\tentityRef"
    private val specific = "POST\t/internal/v1/g/special\ttarget"

    @Test
    fun `INV-AC-8515 the specific line serves its path and the templated line the others`() {
        resolution(mapOf(EntityRefScopeFilter.RESOURCE to "$templated\n$specific\n"))
    }

    @Test
    fun `INV-AC-8515 the line order of the file does not decide which line serves`() {
        resolution(mapOf(EntityRefScopeFilter.RESOURCE to "$specific\n$templated\n"))
    }

    @Test
    fun `INV-AC-8515 lines held by two resources resolve the same`() {
        val root1 = loader(mapOf(EntityRefScopeFilter.RESOURCE to "$templated\n"))
        val root2 = loader(mapOf(EntityRefScopeFilter.RESOURCE to "$specific\n"))
        val both = URLClassLoader(root1.urLs + root2.urLs, javaClass.classLoader).also { loaders += it }
        val context =
            SpringApplicationBuilder(ErConfig::class.java)
                .web(WebApplicationType.SERVLET)
                .resourceLoader(DefaultResourceLoader(both))
                .properties(ER_SETTINGS)
                .run("--server.port=0", "--server.tomcat.threads.max=1", "--server.tomcat.threads.min-spare=1")
        running += context
        val on = Server(checkNotNull((context as WebServerApplicationContext).webServer).port)
        reset()
        post("/internal/v1/g/special", """{"target":"E-002","entityRef":"E-001"}""", on = on).statusCode() shouldBe 403
        post("/internal/v1/g/special", """{"target":"E-001","entityRef":"E-002"}""", on = on).statusCode() shouldBe 200
        post("/internal/v1/g/7", """{"entityRef":"E-002","target":"E-001"}""", on = on).statusCode() shouldBe 403
        post("/internal/v1/g/7", """{"entityRef":"E-001","target":"E-002"}""", on = on).statusCode() shouldBe 200
    }

    private fun twoResources(
        a: String,
        b: String,
    ): URLClassLoader {
        val one = loader(mapOf(EntityRefScopeFilter.RESOURCE to a))
        val two = loader(mapOf(EntityRefScopeFilter.RESOURCE to b))
        return URLClassLoader(one.urLs + two.urLs, javaClass.classLoader).also { loaders += it }
    }

    @Test
    fun `INV-AC-8515 identical lines of two resources count as one and a conflicting property stops the start-up`() {
        val line = "POST\t/internal/v1/b\ttarget\n"
        EntityRefScopeFilter.from(twoResources(line, line))
        val same =
            start(mapOf(EntityRefScopeFilter.RESOURCE to line + line))
        reset()
        post("/internal/v1/b", """{"target":"E-002"}""", on = same).statusCode() shouldBe 403
        post("/internal/v1/b", """{"target":"E-001"}""", on = same).statusCode() shouldBe 200
        ErProbe.handled.get() shouldBe 1
        shouldThrow<IllegalStateException> {
            EntityRefScopeFilter.from(twoResources(line, "POST\t/internal/v1/b\tentityRef\n"))
        }.message.orEmpty() shouldNotContain "E-0"
    }

    @Test
    fun `INV-AC-8515 templates equal up to names are one operation and crossing ones stop the start-up`() {
        fun from(text: String) = EntityRefScopeFilter.from(loader(mapOf(EntityRefScopeFilter.RESOURCE to text)))
        shouldThrow<IllegalStateException> {
            from("POST\t/internal/v1/h/{a}\tentityRef\nPOST\t/internal/v1/h/{b}\ttarget\n")
        }
        from("POST\t/internal/v1/h/{a}\tentityRef\nPOST\t/internal/v1/h/{b}\tentityRef\n")
        shouldThrow<IllegalStateException> {
            from("POST\t/internal/v1/{a}/x\tentityRef\nPOST\t/internal/v1/y/{b}\ttarget\n")
        }.message.orEmpty().let {
            it shouldContain "POST"
            it shouldContain "/internal/v1/{a}/x"
        }
        from("POST\t/internal/v1/h/{a}\tentityRef\nPUT\t/internal/v1/h/{a}\ttarget\n")
        from("POST\t/internal/v1/h/{a}\tentityRef\nPOST\t/internal/v1/h/{a}/x\ttarget\n")
        from("POST\t/internal/v1/g/{id}\tentityRef\nPOST\t/internal/v1/g/special\ttarget\n")
    }

    @Test
    fun `INV-AC-8515 a malformed line stops the start-up and an absent resource touches no request`() {
        val bad =
            listOf(
                "POST\t/a",
                "POST\t/a\tx\ty",
                "\t/a\tx",
                "POST\ta\tx",
                "POST\t/a\t1x",
                "POST\t/a\t" + "x".repeat(41),
                "POST\t/a\tent ity",
                "POST\t/a\tx\nPOST\t/a\ty",
                "POST\t/a\tx\n\nPOST\t/b\tx",
            )
        for (text in bad) {
            val failure =
                shouldThrow<Exception> {
                    EntityRefScopeFilter.from(loader(mapOf(EntityRefScopeFilter.RESOURCE to text)))
                }
            failure.message.orEmpty() shouldContain EntityRefScopeFilter.RESOURCE
        }
        EntityRefScopeFilter.from(loader(emptyMap()))
        EntityRefScopeFilter.from(loader(mapOf(EntityRefScopeFilter.RESOURCE to "POST\t/a\tx1\n")))
        val none = start(emptyMap())
        reset()
        post("/internal/v1/a/7", """{"entityRef":"E-002"}""", on = none).statusCode() shouldBe 200
    }
}
