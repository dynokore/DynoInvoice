package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.clients.EvaluationFacts
import com.dynokore.gst.canonical.adapters.clients.RulesClient
import com.dynokore.gst.canonical.adapters.clients.RulesEdge
import com.dynokore.gst.canonical.application.document.EntityConstitutions
import com.dynokore.platform.serviceruntime.client.InternalRestClientAutoConfiguration
import com.dynokore.platform.serviceruntime.identity.FlowContext
import com.dynokore.platform.serviceruntime.identity.FlowContextIssuer
import com.dynokore.platform.serviceruntime.identity.FlowContextOperations
import com.dynokore.platform.serviceruntime.identity.FlowContextPropagationInterceptor
import com.dynokore.platform.serviceruntime.identity.FlowEntity
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.ResilienceRegistry
import com.dynokore.platform.serviceruntime.resilience.ResilientEdge
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.http.client.ClientHttpRequestInterceptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * INV-AC-23637: the `rulesClient` bean of [ScopeEvaluationConfiguration] is built from the context's own
 * `RestClient.Builder` (service-runtime's InternalRestClientAutoConfiguration, which applies every
 * ClientHttpRequestInterceptor bean), so the flow context interceptor reaches the call with no wiring in the service.
 */
class RulesFlowContextWiringTest {
    private class InlineRegistry : ResilienceRegistry {
        override fun forEdge(edge: String): ResilientEdge =
            object : ResilientEdge {
                override fun <T> execute(
                    deadline: Deadline,
                    onCallNotPermitted: () -> T,
                    onConnectFailure: () -> T,
                    onTimeout: () -> T,
                    block: () -> T,
                ): T = Executors.newVirtualThreadPerTaskExecutor().use { it.submit<T> { block() }.get() }
            }
    }

    // not annotated: a nested @Configuration would be found by the component scan of the full-context tests
    class Collaborators {
        @Bean
        fun registry(): ResilienceRegistry = InlineRegistry()

        @Bean
        fun dsl(): DSLContext = DSL.using(SQLDialect.POSTGRES)

        @Bean
        fun constitutions(): EntityConstitutions =
            object : EntityConstitutions {
                override fun constitutionOf(
                    tenantId: java.util.UUID,
                    entityRef: String,
                ): String? = null
            }

        // the bean IdentityAutoConfiguration offers, by its name and type
        @Bean
        fun flowContextPropagationInterceptor(): ClientHttpRequestInterceptor =
            FlowContextPropagationInterceptor(
                FlowContextOperations.of(
                    listOf(
                        "test" to
                            "operationId\tmethod\tpath\n" +
                            "evaluateDocument\tPOST\t/internal/v1/rules/documents/evaluations\n",
                    ),
                ),
            )
    }

    private val headers = CopyOnWriteArrayList<String?>()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                headers += exchange.requestHeaders.getFirst(FlowContext.HEADER_NAME)
                exchange.requestBody.readAllBytes()
                val bytes = """{"scopeVerdict":"IN_SCOPE","documentTypeCode":"380"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
            start()
        }

    private fun runner() =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InternalRestClientAutoConfiguration::class.java))
            .withUserConfiguration(Collaborators::class.java, ScopeEvaluationConfiguration::class.java)
            .withPropertyValues(
                "${RulesEdge.URL_PROPERTY}=http://127.0.0.1:${server.address.port}",
                "canonical.masterdata-svc-url=http://127.0.0.1:1",
            )

    private val facts = EvaluationFacts("E-001", LocalDate.parse("2026-03-20"), "SR-STD", listOf("ARCS"), null)

    private fun held(compact: String) =
        FlowContext.begin(
            FlowContextIssuer { compact },
            Clock.systemUTC(),
            "tenant-1",
            FlowEntity.entity("E-001"),
            "MACHINE",
            "canonical-svc",
        )

    @Test
    fun `INV-AC-23637 the composed rulesClient sends the held context and sends none when none is held`() {
        try {
            runner().run { context ->
                context.startupFailure shouldBe null
                val client = context.getBean("rulesClient", RulesClient::class.java)
                FlowContext.runWith(held("header.payload.sig-W")) { client.evaluate(facts) }
                client.evaluate(facts)
                headers.toList() shouldBe listOf("header.payload.sig-W", null)
            }
        } finally {
            server.stop(0)
        }
    }
}
