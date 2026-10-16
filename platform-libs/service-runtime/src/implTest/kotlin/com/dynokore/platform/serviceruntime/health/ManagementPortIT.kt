package com.dynokore.platform.serviceruntime.health

import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.composition.StandInProductRuntimeConfiguration
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.BeanCreationException
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/**
 * INV-T-4125 (API-33, owner ruling INV-Q-4103 A; INV-REQ-1228 b5, INV-REQ-1123 b4): a running service composed as
 * TPL-18 writes it (`@Import(ServiceRuntimeConfiguration, StandInProductRuntimeConfiguration)` plus Spring Boot's
 * auto-configuration, Tomcat on both ports) and with service-runtime's defaults alone serves /health/live,
 * /health/ready, /metrics and /diagnostics on the management port 8081 without a token. On the application port
 * WorkloadTokenAuthenticationFilter still covers every path: the same paths answer 401 without a token and 404
 * with a valid one, and a business endpoint answers 401 without a token. The former `liveness` and `readiness`
 * group keys fail start-up (coordinator ruling INV-Q-4192).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagementPortIT {
    // One secret mount for every running service of this class (a @TempDir field is replaced after each test).
    private val secrets: Path = Files.createTempDirectory("secrets")

    private val http = HttpClient.newHttpClient()
    private val running = mutableListOf<ConfigurableApplicationContext>()

    // Command-line arguments, as a deployment's own settings: they outrank service-runtime's defaults, which
    // SpringApplication's default properties do not.
    private fun start(vararg properties: String): ConfigurableApplicationContext {
        val settings =
            listOf(
                "server.port=0",
                "service-runtime.workload-identity.audience=submission-svc",
                "gst.service=submission-svc",
                "gst.bindings.secret-store.mount-root=$secrets",
                // no database here: the composition without an application DataSource (Tpl16ServiceCompositionTest)
                "spring.autoconfigure.exclude=" +
                    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
                    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            ) + properties
        val arguments =
            settings.associate { it.substringBefore('=') to it.substringAfter('=') }.map { (key, value) ->
                "--$key=$value"
            }
        return SpringApplicationBuilder(Tpl18WebService::class.java)
            .web(WebApplicationType.SERVLET)
            .run(*arguments.toTypedArray())
            .also(running::add)
    }

    private val service by lazy { start() }

    private fun applicationPort(context: ConfigurableApplicationContext) =
        checkNotNull((context as WebServerApplicationContext).webServer).port

    private fun get(
        port: Int,
        path: String,
        token: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()
        token?.let { request.header(WorkloadToken.HEADER_NAME, it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    @AfterAll
    fun stop() {
        running.forEach(ConfigurableApplicationContext::close)
        Files.deleteIfExists(secrets)
    }

    @Test
    fun `health, metrics and diagnostics answer on 8081 without a token`() {
        service
        get(MANAGEMENT_PORT, "/health/live").let {
            it.statusCode() shouldBe 200
            it.body() shouldContain "\"status\":\"UP\""
        }
        get(MANAGEMENT_PORT, "/health/ready").let {
            it.statusCode() shouldBe 200
            it.body() shouldContain "\"status\":\"UP\""
        }
        get(MANAGEMENT_PORT, "/metrics").let {
            it.statusCode() shouldBe 200
            it.headers().firstValue("Content-Type").get() shouldStartWith "text/plain"
            it.body() shouldContain "# TYPE "
        }
        get(MANAGEMENT_PORT, "/diagnostics").let {
            it.statusCode() shouldBe 200
            it.body() shouldContain "\"health\""
        }
    }

    @Test
    fun `readiness is DOWN with 503 when the vault is down, so the probe fails`() {
        val vaultDown = start("management.server.port=0", "gst.bindings.secret-store.mount-root=$secrets/absent")
        val port = vaultDown.environment.getProperty("local.management.port")!!.toInt()
        get(port, "/health/ready").let {
            it.statusCode() shouldBe 503
            it.body() shouldContain "\"status\":\"DOWN\""
        }
        get(port, "/health/live").statusCode() shouldBe 200
    }

    @ParameterizedTest
    @ValueSource(strings = ["/health/live", "/health/ready", "/metrics", "/diagnostics", "/actuator/health", "/health"])
    fun `the application port serves none of them, and its filter has no exemption`(path: String) {
        val port = applicationPort(service)
        get(port, path).statusCode() shouldBe 401
        get(port, path, VALID).statusCode() shouldBe 404
    }

    @Test
    fun `a business endpoint on the application port still needs a workload token`() {
        val port = applicationPort(service)
        get(port, "/business").statusCode() shouldBe 401
        get(port, "/business", "forged").statusCode() shouldBe 401
        get(port, "/business", VALID).let {
            it.statusCode() shouldBe 200
            it.body() shouldBe "ok"
        }
    }

    @Test
    fun `a service may change the management port number`() {
        val moved = start("management.server.port=0")
        val port = moved.environment.getProperty("local.management.port")!!.toInt()
        (port == MANAGEMENT_PORT) shouldBe false
        get(port, "/health/live").statusCode() shouldBe 200
        get(applicationPort(moved), "/health/live", VALID).statusCode() shouldBe 404
    }

    @Test
    fun `start-up fails when the management port equals the application port`() {
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withUserConfiguration(ServiceRuntimeConfiguration::class.java)
            .withPropertyValues("service-runtime.workload-identity.audience=submission-svc")
            .let { runner ->
                // the defaults alone, and a service's own other port number, start
                runner.run { it.startupFailure shouldBe null }
                runner.withPropertyValues("management.server.port=9091", "server.port=9090").run {
                    it.startupFailure shouldBe
                        null
                }
                listOf(
                    arrayOf("management.server.port=9090", "server.port=9090"),
                    arrayOf("management.server.port=8080"),
                ).forEach { properties ->
                    runner.withPropertyValues(*properties).run { context ->
                        val failure = context.startupFailure.shouldBeInstanceOf<BeanCreationException>()
                        failure.mostSpecificCause.message!! shouldContain "must differ from server.port"
                    }
                }
            }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "management.endpoint.health.group.readiness.include=readinessState,db,eventLog",
            "management.endpoint.health.group.liveness.include=livenessState",
            "management.endpoint.health.group.readiness.show-details=always",
        ],
    )
    fun `start-up fails when a property source sets a former liveness or readiness group key`(property: String) {
        guardRunner().withPropertyValues(property).run { context ->
            val failure = context.startupFailure.shouldBeInstanceOf<BeanCreationException>()
            failure.mostSpecificCause.message!! shouldContain "Health groups are named live and ready"
            failure.mostSpecificCause.message!! shouldContain property.substringBefore('=')
        }
    }

    @Test
    fun `start-up fails on a former group key set as an environment variable`() {
        guardRunner()
            .withInitializer { context ->
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource(
                        "systemEnvironment",
                        mapOf<String, Any>("MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE" to "readinessState,db"),
                    ),
                )
            }.run { context ->
                context.startupFailure
                    .shouldBeInstanceOf<BeanCreationException>()
                    .mostSpecificCause.message!! shouldContain "group.ready.*"
            }
    }

    @Test
    fun `the new live and ready group keys start`() {
        guardRunner()
            .withPropertyValues(
                "management.endpoint.health.group.ready.include=readinessState,db,eventLog,vault",
                "management.endpoint.health.group.live.include=livenessState",
            ).run { it.startupFailure shouldBe null }
    }

    private fun guardRunner() =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withUserConfiguration(ServiceRuntimeConfiguration::class.java)
            .withPropertyValues("service-runtime.workload-identity.audience=submission-svc")

    private companion object {
        // Fixed port kept on purpose (INV-Q-5752): this test proves the API-33 default port 8081.
        // It cannot collide under parallel forks: it is the only test opening a fixed server port,
        // a test class runs in one fork, and each unit-test job runs in its own container network
        // namespace on the runner, so 8081 of one job is not 8081 of another job on the same host.
        const val MANAGEMENT_PORT = 8081
        const val VALID = "valid-workload-token"
    }
}

/**
 * TPL-18's application class with Spring Boot's auto-configuration, and one business endpoint. [OneValidToken] comes
 * first, as a service's bootstrap beans do (component scan registers them before the application class's `@Import`).
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import(
    OneValidToken::class,
    ServiceRuntimeConfiguration::class,
    StandInProductRuntimeConfiguration::class,
    BusinessController::class,
)
class Tpl18WebService

/** Stands in for the JWK-backed verifier: one token is valid. */
@Configuration(proxyBeanMethods = false)
class OneValidToken {
    @Bean
    fun workloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token ->
            if (token?.value ==
                "valid-workload-token"
            ) {
                WorkloadVerification.Verified("caller-svc")
            } else {
                WorkloadVerification.Refused("invalid")
            }
        }
}

@RestController
class BusinessController {
    @GetMapping("/business")
    fun business(): String = "ok"
}
