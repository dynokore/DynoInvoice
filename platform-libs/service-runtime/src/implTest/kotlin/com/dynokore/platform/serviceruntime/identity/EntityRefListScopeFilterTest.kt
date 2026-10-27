package com.dynokore.platform.serviceruntime.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.DefaultResourceLoader
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

private const val LIST_LINES = "POST\t/internal/v1/a\tentityRefs\nPOST\t/internal/v1/b\tentityRef,entityRefs\n"

/** INV-AC-9629: the filter judges every element of a list-valued entity reference. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityRefListScopeFilterTest {
    @TempDir
    lateinit var dir: Path

    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private val running = mutableListOf<ConfigurableApplicationContext>()
    private val loaders = mutableListOf<URLClassLoader>()

    private fun loader(text: String): URLClassLoader {
        val root = Files.createTempDirectory(dir, "cp")
        Files.createDirectories(root.resolve(EntityRefScopeFilter.RESOURCE).parent)
        Files.writeString(root.resolve(EntityRefScopeFilter.RESOURCE), text)
        return URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).also { loaders += it }
    }

    private val port by lazy {
        val context =
            SpringApplicationBuilder(ErConfig::class.java)
                .web(WebApplicationType.SERVLET)
                .resourceLoader(DefaultResourceLoader(loader(LIST_LINES)))
                .properties(mapOf<String, Any>("service-runtime.workload-identity.audience" to "probe-svc"))
                .run("--server.port=0", "--server.tomcat.threads.max=1", "--server.tomcat.threads.min-spare=1")
        running += context
        checkNotNull((context as WebServerApplicationContext).webServer).port
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
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
        token?.let { request.header("Authorization", it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** Asserts the answer and the handler call count by equality; [code] null means the handler's own 200. */
    private fun judge(
        body: String,
        status: Int,
        code: String?,
        path: String = "/internal/v1/a",
        token: String? = "Bearer T-E1",
    ) {
        ErProbe.handled.set(0)
        val answer = post(path, body, token)
        answer.statusCode() shouldBe status
        if (code != null) mapper.readTree(answer.body())["code"].asString() shouldBe code
        ErProbe.handled.get() shouldBe if (status == 200) 1 else 0
    }

    @Test
    fun `INV-AC-9629 an array of entities in the set and an absent or empty array continue`() {
        judge("""{"entityRefs":["E-001"]}""", 200, null)
        judge("""{"entityRefs":["E-001","E-100"]}""", 200, null)
        judge("""{"entityRefs":[]}""", 200, null)
        judge("{}", 200, null)
        judge("""{"entityRefs":"E-001"}""", 200, null)
    }

    @Test
    fun `INV-AC-9629 one element outside the set or a stale set is 403 SRT-004 in any position`() {
        for (body in listOf(
            """{"entityRefs":["E-001","E-002"]}""",
            """{"entityRefs":["E-002","E-001"]}""",
            """{"entityRefs":["E-001","E-0010"]}""",
            """{"entityRefs":["e-001"]}""",
            """{"entityRefs":["E-001","E-001","E-002"]}""",
        )) {
            judge(body, 403, "SRT-004")
        }
        judge("""{"entityRefs":["E-001"]}""", 403, "SRT-004", token = "Bearer T-STALE")
    }

    @Test
    fun `INV-AC-9629 an element that is not a string, a bad value or a repeated name is 400 SRT-001`() {
        for (body in listOf(
            """{"entityRefs":[1]}""",
            """{"entityRefs":["E-001",null]}""",
            """{"entityRefs":[{}]}""",
            """{"entityRefs":[[]]}""",
            """{"entityRefs":{}}""",
            """{"entityRefs":1}""",
            """{"entityRefs":null}""",
            """{"entityRefs":["E-001"],"entityRefs":["E-001"]}""",
            """{"entityRefs":[],"entityRefs":"E-001"}""",
            """{"entityRefs":["E-001"]""",
            "[]",
        )) {
            judge(body, 400, "SRT-001")
        }
        val over = """{"entityRefs":["E-001"],"p":"""" + "a".repeat(1048577) + "\"}"
        judge(over, 400, "SRT-001")
    }

    @Test
    fun `INV-AC-9629 the look-alike property entityrefs is never read`() {
        judge("""{"entityrefs":["E-002"],"entityRefs":["E-001"]}""", 200, null)
        judge("""{"entityrefs":["E-002"]}""", 200, null)
    }

    @Test
    fun `INV-AC-9629 no end-user token is 401 SRT-003`() {
        judge("""{"entityRefs":["E-001"]}""", 401, "SRT-003", token = null)
    }

    @Test
    fun `INV-AC-9629 a line with two names judges both the string and the array`() {
        judge("""{"entityRef":"E-002"}""", 403, "SRT-004", path = "/internal/v1/b")
        judge("""{"entityRefs":["E-002"]}""", 403, "SRT-004", path = "/internal/v1/b")
        judge("""{"entityRef":"E-001","entityRefs":["E-001","E-002"]}""", 403, "SRT-004", path = "/internal/v1/b")
        judge("""{"entityRef":"E-001","entityRefs":["E-100"]}""", 200, null, path = "/internal/v1/b")
        judge("""{"entityRef":"E-001","entityRef":"E-001"}""", 400, "SRT-001", path = "/internal/v1/b")
    }

    @Test
    fun `INV-AC-9629 a line whose names repeat, are empty or do not match stops the start-up`() {
        for (names in listOf("entityRefs,entityRefs", "entityRefs,", ",entityRefs", "entityRefs,1x", "a b", ",")) {
            val failure =
                shouldThrow<IllegalStateException> {
                    EntityRefScopeFilter.from(loader("POST\t/a\t$names\n"))
                }
            failure.message.orEmpty() shouldContain EntityRefScopeFilter.RESOURCE
        }
        EntityRefScopeFilter.from(loader("POST\t/a\tentityRef,entityRefs\n"))
    }
}
