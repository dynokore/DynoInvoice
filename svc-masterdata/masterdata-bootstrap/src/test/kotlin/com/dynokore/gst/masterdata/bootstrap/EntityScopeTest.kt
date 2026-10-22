package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.Filter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private val SCOPE_TENANT = TenantId(UUID.randomUUID())

/** Counts the writes that reach the repository, standing for the use case double of the criterion. */
object WriteCounter {
    val appends = AtomicInteger()
}

/** Wraps the repository of the context so that every append is counted. */
class CountingRepositoryPostProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any =
        if (bean is CounterpartyRepository && !Proxy.isProxyClass(bean.javaClass)) {
            Proxy.newProxyInstance(
                CounterpartyRepository::class.java.classLoader,
                arrayOf(CounterpartyRepository::class.java),
            ) { _, method, args ->
                // Kotlin mangles the name of a method that takes a value class, so match by prefix
                if (method.name.startsWith("append")) WriteCounter.appends.incrementAndGet()
                try {
                    method.invoke(bean, *(args ?: emptyArray()))
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
        } else {
            bean
        }
}

/** The two identity filters of service-runtime, in the order the container applies them (-50, then -45). */
private fun registeredFilters(context: AssertableWebApplicationContext): Array<Filter> =
    listOf("endUserPrincipalBindingFilter", "entityRefScopeFilter")
        .map { name -> context.getBean(name, FilterRegistrationBean::class.java) }
        .sortedBy { it.order }
        .map { it.filter as Filter }
        .toTypedArray()

/**
 * INV-AC-8515: a body `entityRef` outside the end-user principal's entity set is refused with 403 SRT-004 by the
 * generic filter of service-runtime before the handler runs, driven by the contract resource the plugin emits
 * (INV-Q-8513 A). The service holds no entity-set check of its own.
 */
class EntityScopeTest {
    private val resolver =
        EndUserPrincipalResolver { header ->
            when (header) {
                "Bearer T-E1" -> {
                    EndUserPrincipal("IDN-OPERATOR", SCOPE_TENANT, setOf(EntityRef("E-001")))
                }

                "Bearer T-STALE" -> {
                    EndUserPrincipal("IDN-OPERATOR", SCOPE_TENANT, setOf(EntityRef("E-001")), entitySetCurrent = false)
                }

                else -> {
                    null
                }
            }
        }

    private fun run(block: (Scope) -> Unit) {
        BoundaryFixture
            .runner()
            .withBean(EndUserPrincipalResolver::class.java, { resolver })
            // a resolver bean switches on service-runtime's command servlets, whose API-15 store names its schema
            .withPropertyValues("service-runtime.idempotency.schema=masterdata")
            .withBean(CountingRepositoryPostProcessor::class.java)
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Scope(context))
            }
    }

    private class Scope(
        val context: AssertableWebApplicationContext,
    ) {
        val captured: CapturedEvents = context.getBean(CapturedEvents::class.java)
        val party = unique("CTP")
        private val appendsAtStart = WriteCounter.appends.get()
        private val mvc =
            MockMvcBuilders
                .webAppContextSetup(context.sourceApplicationContext as WebApplicationContext)
                .also { it.addFilters<DefaultMockMvcBuilder>(*registeredFilters(context)) }
                .build()

        fun rows(): Int =
            BoundaryFixture.asSuperuser { c ->
                c
                    .createStatement()
                    .executeQuery(
                        "select count(*) from masterdata.counterparty_version where party_ref = '$party'",
                    ).use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
            }

        /** The state a refused request must leave unchanged: handler calls, stored rows, emitted events. */
        fun state() = Triple(WriteCounter.appends.get() - appendsAtStart, rows(), captured.envelopes.size)

        fun post(
            body: String,
            token: String? = "T-E1",
            path: String = "resolution",
        ): Pair<Int, String> =
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/internal/v1/masterdata/counterparties/$party/$path")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .apply { if (token != null) header("Authorization", "Bearer $token") }
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andReturn()
                .response
                .let { it.status to it.contentAsString }
    }

    private fun gap(entity: String = "\"E-001\"") =
        """{"entityRef":$entity,"resolvedIdentifiers":[{"scheme":"UEN","value":"${unique("UEN")}"}],""" +
            """"evidenceRef":"EVD-1","legalName":"Scope Ltd",""" +
            """"address":{"addressLine1":"1 Raffles Place","postalCode":"048616"}}"""

    @Test
    fun `INV-AC-8515 an entity of the principal reaches the handler once and one event is emitted`() =
        run { scope ->
            assertThat(Proxy.isProxyClass(scope.context.getBean(CounterpartyRepository::class.java).javaClass)).isTrue()
            val (status, body) = scope.post(gap())
            assertThat(status).describedAs(body).isEqualTo(200)
            assertThat(scope.state()).isEqualTo(Triple(1, 1, 1))
            assertThat(
                scope.captured.envelopes
                    .single()
                    .entity.value,
            ).isEqualTo("E-001")
        }

    @Test
    fun `INV-AC-8515 an entity outside the set and its look-alikes are 403 SRT-004 with nothing stored or emitted`() =
        run { scope ->
            val bodies =
                listOf("E-002", "E-0010", "e-001", "E-001 ", " E-001", "E-00", "").map { entity ->
                    val before = scope.state()
                    val (status, body) = scope.post(gap("\"$entity\""))
                    assertThat(status).describedAs("entityRef '$entity': $body").isEqualTo(403)
                    assertThat(body).contains("SRT-004").doesNotContain("E-00")
                    assertThat(scope.state()).describedAs("entityRef '$entity'").isEqualTo(before)
                    body.replace(Regex("\"correlation[^,}]*"), "")
                }
            // the same answer whether or not the entity exists: an unknown and a known-elsewhere entity read alike
            assertThat(bodies.map { it.length }.toSet()).hasSize(1)
        }

    @Test
    fun `INV-AC-8515 a stale entity set refuses even an entity of the set`() =
        run { scope ->
            val (status, body) = scope.post(gap(), token = "T-STALE")
            assertThat(status).isEqualTo(403)
            assertThat(body).contains("SRT-004")
            assertThat(scope.state()).isEqualTo(Triple(0, 0, 0))
        }

    @Test
    fun `INV-AC-8515 no token is 401 and a token nobody knows is 401, nothing stored`() =
        run { scope ->
            assertThat(scope.post(gap(), token = null).first).isEqualTo(401)
            assertThat(scope.post(gap(), token = "T-UNKNOWN").first).isEqualTo(401)
            assertThat(scope.state()).isEqualTo(Triple(0, 0, 0))
        }

    @Test
    fun `INV-AC-8515 a request without entityRef is 400 SRT-001 and not 403`() =
        run { scope ->
            val body = """{"resolvedIdentifiers":[{"scheme":"UEN","value":"U1"}],"evidenceRef":"EVD-1"}"""
            val (status, answer) = scope.post(body)
            assertThat(status).isEqualTo(400)
            assertThat(answer).contains("SRT-001").doesNotContain("SRT-004")
            assertThat(scope.state()).isEqualTo(Triple(0, 0, 0))
        }

    @Test
    fun `INV-AC-8515 a malformed property or body is 400 SRT-001 before the handler`() =
        run { scope ->
            val malformed =
                listOf(
                    gap("1"),
                    gap("[\"E-001\"]"),
                    gap("null"),
                    gap("{\"v\":\"E-001\"}"),
                    gap().replaceFirst("{", "{\"entityRef\":\"E-001\","),
                    "[]",
                    "not json",
                    "\"E-001\"",
                    gap() + " {}",
                    gap().dropLast(1) + ",\"filler\":\"" + "x".repeat(1_048_576) + "\"}",
                )
            malformed.forEach { body ->
                val (status, answer) = scope.post(body)
                assertThat(status).describedAs(body.take(80)).isEqualTo(400)
                assertThat(answer).describedAs(body.take(80)).contains("SRT-001")
                assertThat(scope.state()).describedAs(body.take(80)).isEqualTo(Triple(0, 0, 0))
            }
        }

    @Test
    fun `INV-AC-8515 the property entityRef decides and the look-alike entityref is not read`() =
        run { scope ->
            val own = gap().replaceFirst("{", "{\"entityref\":\"E-002\",")
            val (status, body) = scope.post(own)
            assertThat(status).describedAs(body).isNotEqualTo(403)
            assertThat(status).describedAs(body).isEqualTo(200)
            assertThat(
                scope.captured.envelopes
                    .single()
                    .entity.value,
            ).isEqualTo("E-001")
            val other = gap().replaceFirst("\"entityRef\":\"E-001\"", "\"entityref\":\"E-001\"")
            val refused = scope.post(other)
            assertThat(refused.first).describedAs(refused.second).isEqualTo(400)
            assertThat(refused.second).contains("SRT-001")
        }

    @Test
    fun `INV-AC-8515 an operation the contract does not mark is not judged by the filter`() =
        run { scope ->
            val ingested =
                """{"entityRef":"E-002","evidenceRef":"EVD-9","providedBy":"INTERNAL",""" +
                    """"legalName":"Ingested Ltd"}"""
            val (status, body) = scope.post(ingested, path = "ingested-details")
            assertThat(status).describedAs(body).isNotEqualTo(403)
            assertThat(body).doesNotContain("SRT-004")
        }

    @Test
    fun `INV-AC-8515 the contract resource names resolveMasterDataGap and the main sources hold no entity-set check`() {
        val resources =
            javaClass.classLoader
                .getResources("META-INF/gst/entity-ref-operations.tsv")
                .toList()
        assertThat(resources).hasSize(1)
        assertThat(
            resources
                .single()
                .readText()
                .trim()
                .lines(),
        ).containsExactly("POST\t/internal/v1/masterdata/counterparties/{counterpartyRef}/resolution\tentityRef")
        val check = Regex("""\bcovers\s*\(|\bEndUserPrincipal\b|\.entities\b|\bentitySetCurrent\b""")
        val offenders =
            Files
                .list(Path.of(".."))
                .use { modules -> modules.filter { it.fileName.toString().startsWith("masterdata-") }.toList() }
                .flatMap { module ->
                    val main = module.resolve("src/main")
                    if (!Files.isDirectory(main)) {
                        emptyList()
                    } else {
                        Files
                            .walk(main)
                            .use { files -> files.filter { it.toString().endsWith(".kt") }.toList() }
                            .filter { check.containsMatchIn(Files.readString(it)) }
                    }
                }
        assertThat(offenders).isEmpty()
    }
}
