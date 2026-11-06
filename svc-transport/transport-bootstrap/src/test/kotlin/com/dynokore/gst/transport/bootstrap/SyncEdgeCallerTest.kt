package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.TransportDb
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.syncedge.CallerChecks
import com.dynokore.gst.transport.syncedge.ContractDocument
import com.dynokore.gst.transport.syncedge.HandlerCalls
import com.dynokore.gst.transport.syncedge.Operation
import com.dynokore.gst.transport.syncedge.SourceScan
import com.dynokore.gst.transport.syncedge.TestWorkloadVerifier
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/**
 * The other tests put `CallerTestConfig` (their own workload tokens) in every scanned context; this test brings its own
 * verifier, so the scan leaves that configuration out.
 */
private class WithoutCallerTestConfig : TypeExcludeFilter() {
    override fun match(
        metadataReader: MetadataReader,
        metadataReaderFactory: MetadataReaderFactory,
    ): Boolean = metadataReader.classMetadata.className == CallerTestConfig::class.java.name

    override fun equals(other: Any?): Boolean = other is WithoutCallerTestConfig

    override fun hashCode(): Int = WithoutCallerTestConfig::class.java.hashCode()
}

/**
 * INV-AC-8455 (API-25; owner ruling 2026-10-22): the service refuses, with 403 SRT-004 and nothing stored, a verified
 * caller that the `x-sync-edge` of the called operation does not name. The allowed callers are read from the contract
 * document the service ships; the enforcement is `service-runtime`'s, from the generated `sync-edge-callers.tsv`.
 * A request that reaches a stub answers 501 and stores nothing; one that reaches `submitPayload` (built) is accepted
 * and stores its attempt.
 */
class SyncEdgeCallerTest {
    private val loader = javaClass.classLoader

    @BeforeEach
    fun reset() {
        ApSimulator.reset()
    }

    private fun operations(): List<Operation> = ContractDocument.operations(ContractDocument.text(loader))

    private fun namedCallers(operations: List<Operation>): List<String> =
        operations
            .flatMap { it.callerFields }
            .map { it.removePrefix(CallerChecks.EXCEPT) }
            .filter { it != "any" }
            .distinct()
            .sorted()

    private fun generatedLines(): Map<String, String> =
        loader
            .getResources("META-INF/gst/sync-edge-callers.tsv")
            .toList()
            .flatMap { url ->
                url
                    .readText()
                    .lines()
                    .filter { it.isNotEmpty() }
                    .map { it.split('\t') }
            }.associate { (it[0] + " " + it[1]) to it[2] }

    private fun mapped(context: ApplicationContext): List<Pair<String, String>> =
        context
            .getBean(RequestMappingHandlerMapping::class.java)
            .handlerMethods.keys
            .flatMap { info ->
                val templates = info.pathPatternsCondition?.patternValues.orEmpty()
                templates.filter { it.startsWith(INTERNAL) }.flatMap { path ->
                    info.methodsCondition.methods.map { it.name to path }
                }
            }.sortedBy { it.second + it.first }

    /** `service-runtime.workload-identity.callers`: each service is the subject `sa-<service>`. */
    private fun properties(services: List<String>): List<String> =
        services.flatMapIndexed { index, service ->
            listOf(
                "service-runtime.workload-identity.callers[$index].service=$service",
                "service-runtime.workload-identity.callers[$index].subject=sa-$service",
            )
        }

    private fun serve(
        dir: Path,
        services: List<String>,
        classes: ClassLoader,
        calls: HandlerCalls,
        block: (ConfigurableApplicationContext, AdapterId) -> Unit,
    ) {
        TransportContext.secrets(dir)
        val primary = AdapterId("p-${UUID.randomUUID().toString().take(UNIQUE)}")
        val values =
            TransportContext.propertyValues(dir, TransportDb.jdbcUrl) +
                listOf(
                    "transport.ap.sandbox-endpoint=${ApSimulator.baseUrl}",
                    "transport.ap.adapters[0].id=${primary.value}",
                    "transport.ap.adapters[0].path=primary",
                ) + properties(services)
        SpringApplicationBuilder(TransportApplication::class.java, TestWorkloadVerifier::class.java)
            .initializers(
                ApplicationContextInitializer<ConfigurableApplicationContext> {
                    it.beanFactory.registerSingleton("withoutCallerTestConfig", WithoutCallerTestConfig())
                    it.beanFactory.registerSingleton("handlerCalls", calls)
                },
            ).web(WebApplicationType.SERVLET)
            .resourceLoader(DefaultResourceLoader(classes))
            .properties(*values.toTypedArray())
            .run("--server.port=0", "--management.server.port=0")
            .use { block(it, primary) }
    }

    /** `submitPayload` is built: a valid request for an entity of the test tenant is accepted. */
    private fun built(): Map<String, CallerChecks.Reach> {
        val payload = Base64.getEncoder().encodeToString("<Invoice/>".toByteArray())
        val body = """{"docUuid":"${UUID.randomUUID()}","version":1,"payload":"$payload","entityRef":"E-001"}"""
        return mapOf(
            "POST /internal/v1/transport/payloads" to CallerChecks.Reach(ACCEPTED, body),
            ENTITIES to CallerChecks.Reach(STUB, """{"entityRefs":["E-001"]}"""),
        )
    }

    @Test
    fun `INV-AC-8455 the service refuses a verified caller its x-sync-edge does not name`(
        @TempDir dir: Path,
    ) {
        val operations = operations()
        val services = namedCallers(operations)
        val lookAlikes = services.map { "$it-two" }
        val problems = mutableListOf<String>()
        TransportDb.superuser().use { connection ->
            val calls = HandlerCalls()
            serve(dir, services + lookAlikes, loader, calls) { context, primary ->
                Harness(context, primary, primary).assign(TestWorkloadVerifier.TENANT, "E-001", primary)
                val checks = CallerChecks(context, connection, services, lookAlikes, calls, built())
                val byLabel = operations.associateBy { it.label }
                val lines = generatedLines()
                val handlers = mapped(context)
                (handlers.isNotEmpty()) shouldBe true
                handlers.forEach { (method, path) ->
                    val operation = byLabel["$method $path"]
                    problems +=
                        if (operation == null) {
                            listOf("$method $path: mapped, but the contract document has no such operation")
                        } else {
                            checks.problems(operation, lines[operation.label])
                        }
                }
            }
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-8455 with the generated resource off the classpath every internal operation answers 403 SRT-004`(
        @TempDir dir: Path,
    ) {
        val operations = operations()
        val services = namedCallers(operations)
        val without = FilteredClassLoader(ClassPathResource("META-INF/gst/sync-edge-callers.tsv"))
        val problems = mutableListOf<String>()
        TransportDb.superuser().use { connection ->
            val calls = HandlerCalls()
            serve(dir, services, without, calls) { context, _ ->
                val checks = CallerChecks(context, connection, services, emptyList(), calls)
                val handlers = mapped(context)
                (handlers.isNotEmpty()) shouldBe true
                handlers.forEach { (method, path) ->
                    val operation = operations.first { it.label == "$method $path" }
                    val answer =
                        checks.send(
                            operation,
                            mapOf(
                                CallerChecks.TOKEN to "token-${services.first()}",
                                CallerChecks.AUTH to CallerChecks.END_USER,
                            ),
                        )
                    if (answer.statusCode() != 403 || "SRT-004" !in answer.body()) {
                        problems += "$method $path: ${answer.statusCode()} with the generated resource missing"
                    }
                }
            }
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-8455 a named caller sending an entity the end user does not cover is refused 403 SRT-004`(
        @TempDir dir: Path,
    ) {
        val operations = operations()
        val services = namedCallers(operations)
        val operation = operations.first { it.label == ENTITIES }
        val caller = services.first { it in operation.callerFields }
        TransportDb.superuser().use { connection ->
            val calls = HandlerCalls()
            serve(dir, services, loader, calls) { context, _ ->
                val checks =
                    CallerChecks(
                        context,
                        connection,
                        services,
                        emptyList(),
                        calls,
                        mapOf(ENTITIES to CallerChecks.Reach(STUB, """{"entityRefs":["E-999"]}""")),
                    )
                val before = calls.count(operation.label)
                val answer =
                    checks.send(
                        operation,
                        mapOf(
                            CallerChecks.TOKEN to "token-$caller",
                            CallerChecks.AUTH to CallerChecks.END_USER,
                        ),
                    )
                answer.statusCode() shouldBe 403
                ("SRT-004" in answer.body()) shouldBe true
                calls.count(operation.label) shouldBe before
            }
        }
    }

    @Test
    fun `INV-AC-8455 the main source sets hold no literal caller name passed to a caller check`() {
        val root = Path.of(System.getProperty("user.dir")).parent
        SourceScan.findings(root, namedCallers(operations()).toSet()).shouldBeEmpty()
    }

    private companion object {
        const val INTERNAL = "/internal/v1/"
        const val ACCEPTED = 202
        const val STUB = 501
        const val ENTITIES = "PUT /internal/v1/transport/connections/{connectionRef}/entities"
        const val UNIQUE = 12
    }
}
