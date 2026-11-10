package com.dynokore.platform.serviceruntime.http

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.ControllerAdviceBean
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The controller of INV-AC-19580. */
@RestController
class BindingProbeController {
    data class Item(
        val name: String,
    )

    @GetMapping("/probe")
    fun probe(
        @RequestParam packIdentifier: String,
        @RequestParam limit: Int,
    ) = "own:$packIdentifier:$limit"

    @PostMapping("/probe")
    fun post(
        @RequestBody item: Item,
    ) = "posted:${item.name}"

    @GetMapping("/boom")
    fun boom(): String = throw IllegalStateException("boom")
}

@Configuration
@ImportAutoConfiguration(
    TomcatServletWebServerAutoConfiguration::class,
    DispatcherServletAutoConfiguration::class,
    WebMvcAutoConfiguration::class,
    HttpMessageConvertersAutoConfiguration::class,
    JacksonAutoConfiguration::class,
    BindingFailureAutoConfiguration::class,
)
class BindingProbeApplication

@Configuration(proxyBeanMethods = false)
class OwnBeanConfiguration {
    @org.springframework.context.annotation.Bean
    fun ownBindingAdvice() = BindingFailureAdvice()
}

/** A service's own advice, no Order annotation (the default is the lowest precedence too). */
@RestControllerAdvice
class ServiceOwnAdvice {
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(): ResponseEntity<String> = ResponseEntity.status(422).body("service-own")
}

/**
 * INV-T-19581 (INV-AC-19580, INV-REQ-827 b3): real HTTP requests to a servlet application with the service-runtime
 * binding-failure auto-configuration.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BindingFailureAdviceIT {
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private val running = mutableListOf<ConfigurableApplicationContext>()

    private fun start(vararg sources: Class<*>) =
        SpringApplicationBuilder(*sources)
            .web(WebApplicationType.SERVLET)
            .run("--server.port=0", "--spring.main.banner-mode=off")
            .also(running::add)
            .let { checkNotNull((it as WebServerApplicationContext).webServer).port }

    private val plain by lazy { start(BindingProbeApplication::class.java, BindingProbeController::class.java) }
    private val withOwn by lazy {
        start(BindingProbeApplication::class.java, BindingProbeController::class.java, ServiceOwnAdvice::class.java)
    }
    private val without by lazy {
        start(WithoutAdviceApplication::class.java, BindingProbeController::class.java)
    }

    @Configuration
    @ImportAutoConfiguration(
        TomcatServletWebServerAutoConfiguration::class,
        DispatcherServletAutoConfiguration::class,
        WebMvcAutoConfiguration::class,
        HttpMessageConvertersAutoConfiguration::class,
        JacksonAutoConfiguration::class,
    )
    class WithoutAdviceApplication

    @AfterAll
    fun stop() = running.forEach(ConfigurableApplicationContext::close)

    private fun send(
        port: Int,
        method: String,
        path: String,
        body: String? = null,
    ): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun envelope(
        r: HttpResponse<String>,
        detail: String,
    ) {
        r.statusCode() shouldBe 400
        r.headers().firstValue("Content-Type").get() shouldBe Problems.CONTENT_TYPE
        val n = mapper.readTree(r.body())
        n.get("code").asString() shouldBe ClosedErrorCode.CONTRACT_VIOLATION.code
        n.get("type").asString() shouldBe "https://errors.invoice.internal/SRT-001"
        n.get("status").asInt() shouldBe 400
        n.get("severity").asString() shouldBe "BLOCK"
        n.get("disposition").asString() shouldBe "TERMINAL"
        n.get("correlationId").asString() shouldNotBe ""
        n.get("detail").asString() shouldBe detail
    }

    @Test
    fun `INV-AC-19580 a GET without packIdentifier is 400 SRT-001 in the envelope`() {
        envelope(send(plain, "GET", "/probe?limit=3"), "the request parameter 'packIdentifier' is missing")
    }

    @Test
    fun `INV-AC-19580 a GET with a wrong-typed limit is 400 SRT-001 and echoes no value`() {
        val r = send(plain, "GET", "/probe?packIdentifier=P&limit=abc%3Cscript%3E")
        envelope(r, "the request parameter 'limit' has the wrong type")
        r.body() shouldNotContain "abc<script>"
        r.body() shouldNotContain "abc"
    }

    @Test
    fun `INV-AC-19580 a POST with a body that is not the JSON of the class is 400 SRT-001 and echoes no word`() {
        val r = send(plain, "POST", "/probe", "zebra giraffe not json")
        envelope(r, "the request body does not match the contract")
        r.body() shouldNotContain "zebra"
        r.body() shouldNotContain "giraffe"
    }

    @Test
    fun `INV-AC-19580 control a GET with both parameters valid is 200 with the controller body`() {
        val r = send(plain, "GET", "/probe?packIdentifier=P&limit=3")
        r.statusCode() shouldBe 200
        r.body() shouldBe "own:P:3"
    }

    @Test
    fun `INV-AC-19580 look-alike controls 404 for an unknown path and the IllegalStateException unchanged`() {
        val unknown = send(plain, "GET", "/nowhere")
        unknown.statusCode() shouldBe 404
        unknown.body() shouldNotContain "SRT-001"
        val boom = send(plain, "GET", "/boom")
        val bare = send(without, "GET", "/boom")
        boom.statusCode() shouldBe bare.statusCode()
        boom.statusCode() shouldBe 500
        boom.body().replace(Regex("\"timestamp\":\"[^\"]*\""), "") shouldBe
            bare.body().replace(Regex("\"timestamp\":\"[^\"]*\""), "")
        boom.body() shouldNotContain "SRT-001"
    }

    @Test
    fun `INV-AC-19580 wiring one advice bean at the lowest precedence even when the service defines its own`() {
        start(BindingProbeApplication::class.java, BindingProbeController::class.java, OwnBeanConfiguration::class.java)
        val ctx = running.last()
        ctx.getBeansOfType(BindingFailureAdvice::class.java).size shouldBe 1
        ControllerAdviceBean
            .findAnnotatedBeans(ctx)
            .filter { it.beanType == BindingFailureAdvice::class.java }
            .map { it.order } shouldBe listOf(Ordered.LOWEST_PRECEDENCE)
    }

    @Test
    fun `INV-AC-19580 precedence a service advice wins for the body and the missing parameter stays SRT-001`() {
        val own = send(withOwn, "POST", "/probe", "zebra giraffe not json")
        own.statusCode() shouldBe 422
        own.body() shouldContain "service-own"
        envelope(send(withOwn, "GET", "/probe?limit=3"), "the request parameter 'packIdentifier' is missing")
    }
}
